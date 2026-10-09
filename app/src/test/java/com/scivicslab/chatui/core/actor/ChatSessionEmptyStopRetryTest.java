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
 * {@code EmptyStopRetry_261009_oo01}: a provider call that ends with no tool call, no text and no
 * error is not a final answer — a genuine one always has prose. Observed on
 * {@code qwen3.8-flash-next-iq3_xxs} through chat-ui-with-audit-trail on 2026-10-09: the model's
 * chain of thought ran to {@code finish_reason=stop} without ever writing {@code <invoke>} or an
 * answer, after a long tool observation. A faithful retry of the exact same request, moments
 * later, came back with a well-formed tool call — so {@code stepExpectingAction} now retries once,
 * same prompt, as its own step, before giving up.
 */
class ChatSessionEmptyStopRetryTest {

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Each {@code sendPrompt} call plays the next script in order; the last one repeats. */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<Consumer<Consumer<ChatEvent>>> scripts;
        private int calls = 0;
        ScriptedProvider(List<Consumer<Consumer<ChatEvent>>> scripts) { this.scripts = scripts; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            int i = Math.min(calls, scripts.size() - 1);
            calls++;
            scripts.get(i).accept(emitter);
        }
        int callCount() { return calls; }
    }

    /** finish_reason=stop with nothing written: no delta, no reasoning, no error. */
    private static final Consumer<Consumer<ChatEvent>> EMPTY_STOP = emitter -> { };

    private ChatSessionIIAR newSession(ScriptedProvider provider, CapturingIoLog ioLog) throws Exception {
        IIActorSystem system = new IIActorSystem("chat-session-empty-stop-retry-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", "01");
        }).join();
        return iiar;
    }

    @Test
    void emptyStopThenRealAnswer_retriesOnceAndKeepsTheRetry() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(
                EMPTY_STOP,
                emitter -> emitter.accept(ChatEvent.delta("Answered on retry."))));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("question", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        assertEquals(2, provider.callCount(), "one retry, not more");

        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals(2, history.size(), history.toString());
        assertEquals("assistant", history.get(1).role());
        assertEquals("Answered on retry.", history.get(1).content());

        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/llm")),
                "the empty attempt is its own recorded step: " + ioLog.records);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step2/llm")
                        && r.content().contains("Answered on retry.")),
                "the retry is a second step, not an overwrite: " + ioLog.records);

        assertTrue(emitted.stream().anyMatch(e -> "thinking".equals(e.type())
                        && e.content() != null && e.content().contains("retrying")),
                "the retry is visible, not silent");
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void emptyStopTwiceInARow_givesUpAfterOneRetryRatherThanLooping() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(EMPTY_STOP, EMPTY_STOP));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("question", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        assertEquals(2, provider.callCount(), "retried exactly once, then stopped rather than looping");

        // recordHistory silently drops blank content (by design — see its own javadoc), so a turn
        // that stays empty after the retry adds no assistant entry at all, same as it did before
        // this fix. The retry's effect to check here is that it ran exactly once and the turn
        // still ended cleanly, not stuck mid-turn.
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals(1, history.size(), history.toString());
        assertEquals("user", history.get(0).role());

        assertTrue(emitted.stream().anyMatch(e -> "result".equals(e.type())), "the turn still ends with result");
        assertFalse(iiar.isBusyDirect());
    }
}
