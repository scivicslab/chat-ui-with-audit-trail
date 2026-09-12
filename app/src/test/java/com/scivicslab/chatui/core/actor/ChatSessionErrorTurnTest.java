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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code TurnErrorInConversation_260913_oo01}: a provider call that fails
 * without producing text ends the turn as a failure — an {@code error} history entry, an
 * {@code ERROR:} section in the llm step and in the conversation record, no empty answer.
 */
class ChatSessionErrorTurnTest {

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String tabId) { return 3L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Behaves like OpenAiCompatProvider when the server cannot be reached. */
    private static final class FailingProvider implements LlmProvider {
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            emitter.accept(ChatEvent.error("Error (https://localhost:28005): Unrecognized SSL message, plaintext connection?"));
        }
    }

    @Test
    void failedProviderCall_endsTheTurnAsARecordedFailure() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        FailingProvider provider = new FailingProvider();
        IIActorSystem system = new IIActorSystem("chat-session-error-turn-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", "01");
        }).join();

        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("東京の週間天気予報を教えて", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals(2, history.size(), history.toString());
        assertEquals("user", history.get(0).role());
        assertEquals("error", history.get(1).role(), "the failure is a history entry of its own role");
        assertTrue(history.get(1).content().startsWith("Error: Error (https://localhost:28005)"), history.get(1).content());
        assertFalse(history.stream().anyMatch(h -> "assistant".equals(h.role())), "no empty assistant answer");

        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/llm")
                        && r.content().contains("\nERROR:\nError (https://localhost:28005)")),
                "the llm step records the error: " + ioLog.records);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/conversation")
                        && r.content().contains("QUESTION:\n東京の週間天気予報を教えて\n\nERROR:\nError (https://localhost:28005)")),
                "the conversation record holds the error: " + ioLog.records);

        assertTrue(emitted.stream().anyMatch(e -> "error".equals(e.type())), "the error was relayed live");
        assertFalse(emitted.stream().anyMatch(e -> "delta".equals(e.type())), "no empty answer bubble");
        assertTrue(emitted.stream().anyMatch(e -> "result".equals(e.type())), "the turn still ends with result");
        assertFalse(iiar.isBusyDirect());
    }
}
