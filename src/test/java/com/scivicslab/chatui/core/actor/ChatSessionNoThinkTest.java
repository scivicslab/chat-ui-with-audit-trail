package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the {@code noThink} flag's path from {@link ChatSession#start} through
 * {@link ChatSession#stepExpectingAction()} into the {@link ProviderContext} passed to the
 * provider. No CDI, no network: the fake {@link LlmProvider} below just records the
 * {@code noThink} value it was called with and replies with a plain final answer (no tool
 * call), the shape {@code stepExpectingAction()} needs to reach {@code finish()} on step one.
 */
class ChatSessionNoThinkTest {

    private static final class RecordingProvider implements LlmProvider {
        volatile boolean lastNoThink;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            lastNoThink = ctx.noThink();
            emitter.accept(ChatEvent.delta("final answer, no tool call"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static void runTurn(RecordingProvider provider, boolean noThink) {
        IIActorSystem system = new IIActorSystem("chat-session-nothink-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        // resolveActorPath (used by "actor: this" in the agent-loop YAML) looks self up by name
        // in the iiActors map even when selfActorRef is already set — must register it, same as
        // ChatUiActorSystem.createTab does for the real thing.
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("hello", null, event -> {}, chatRef, new CompletableFuture<>(), null, noThink);
            c.runUntilEnd();
        }).join();
    }

    @Test
    void start_withNoThinkTrue_reachesProviderAsTrue() {
        RecordingProvider provider = new RecordingProvider();
        runTurn(provider, true);
        assertTrue(provider.lastNoThink, "expected noThink=true to reach the provider's ProviderContext");
    }

    @Test
    void start_withNoThinkFalse_reachesProviderAsFalse() {
        RecordingProvider provider = new RecordingProvider();
        runTurn(provider, false);
        assertFalse(provider.lastNoThink);
    }
}
