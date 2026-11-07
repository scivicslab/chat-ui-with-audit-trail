package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A turn writes its id and the request it answers into the I/O log ({@code turnN/ids}), so an id
 * seen on the screen can be searched for there ({@code TurnAndRequestIds_261107_oo01}). The turn
 * here fails, which is when the entry matters most: it is written when the turn starts. The asker
 * of a failed turn is told why there is no answer.
 */
class TurnIdsInTheLogTest {

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 3L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Behaves like OpenAiCompatProvider when the server reports an error. */
    private static final class FailingProvider implements LlmProvider {
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            emitter.accept(ChatEvent.error("Server error (http://x): restart both"));
        }
    }

    @Test
    void aTurnRecordsItsIdsAndAFailedOneTellsTheAskerWhy() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        FailingProvider provider = new FailingProvider();
        IIActorSystem system = new IIActorSystem("turn-ids-in-the-log-test");
        try {
            ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-27.chat", provider, Optional.empty(), ioLog, system);
            system.addIIActor(iiar);
            ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
            iiar.tellNow(a -> {
                ((ChatSession) a).setProviderName(providerRef.getName());
                ((ChatSession) a).setChatIdentity("project1", "27");
            }).join();

            String requestId = "project1/chat-01:s47:t614:r1";
            CompletableFuture<TurnReply> done = new CompletableFuture<>();
            List<ChatEvent> emitted = new ArrayList<>();
            ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
            chatRef.tellNow(c -> {
                c.start("[request " + requestId + " from project1/chat-01]\nRun the job.", null, emitted::add,
                        chatRef, done, requestId, false, List.of(),
                        ChatSession.ASK_CHAT_SOURCE_PREFIX + "project1/chat-01", requestId);
                c.runUntilEnd();
                c.onPromptComplete(emitted::add, done, chatRef);
            }).join();

            CapturingIoLog.Rec ids = ioLog.records.stream()
                    .filter(r -> r.label().equals("turn1/ids")).findFirst().orElse(null);
            assertTrue(ids != null, "the turn wrote its ids: " + ioLog.records);
            assertEquals("TURN_ID: project1/chat-27:s3:t1\nREQUEST_ID: " + requestId, ids.content());

            TurnReply reply = done.get(5, TimeUnit.SECONDS);
            assertNull(reply.reply(), "a failed turn has no answer");
            assertEquals("its turn failed: Server error (http://x): restart both", reply.ending());
        } finally {
            system.terminate();
        }
    }
}
