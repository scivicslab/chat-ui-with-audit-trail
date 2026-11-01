package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line the provider treats as a command must be recognised as one while a turn is running.
 * Asking the conversation's actor would wait behind the turn, time out, and let the line be sent
 * to the model as an ordinary prompt; the direct read answers at once.
 */
class CommandWhileBusyTest {

    private static final class SlashProvider implements LlmProvider {
        final CountDownLatch turnStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public boolean isCommand(String input) { return input != null && input.startsWith("/"); }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            turnStarted.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            emitter.accept(ChatEvent.delta("done"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void aCommandIsRecognisedWhileATurnIsRunning() throws Exception {
        SlashProvider provider = new SlashProvider();
        IIActorSystem system = new IIActorSystem("command-while-busy-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        ActorRef<PromptQueue> queueRef = iiar.createChild(iiar.getName() + ".queue", new PromptQueue());
        queueRef.tellNow(q -> q.setSelf(queueRef)).join();

        queueRef.tellNow(q -> q.enqueue("long question", null, "queue", e -> {}, iiar.asChatSessionRef(), "human")).join();
        assertTrue(provider.turnStarted.await(2, TimeUnit.SECONDS), "the turn must be running");
        assertTrue(iiar.isBusyDirect());

        long t0 = System.nanoTime();
        boolean command = iiar.isCommandDirect("/compact");
        boolean prompt = iiar.isCommandDirect("compact");
        long ms = (System.nanoTime() - t0) / 1_000_000;

        provider.release.countDown();
        assertTrue(command, "a slash line is a command even while the turn runs");
        assertFalse(prompt, "an ordinary line is not");
        assertTrue(ms < 500, "answered at once, not after the turn (" + ms + " ms)");
    }
}
