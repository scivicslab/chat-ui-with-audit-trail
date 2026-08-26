package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure unit test for {@link PromptQueue}'s dispatch path — specifically the fix that moved
 * {@code queue} mutation for dispatch off ChatSession's actor thread and back onto PromptQueue's
 * own (via {@code self.ask(popFront)}), plus the new {@code auto}/{@code advance},
 * {@code removeAt}, and {@code moveInQueue} browser-editing operations. No CDI, no network: a
 * fake {@link LlmProvider} records prompts it receives and can be told to block the first call
 * (via a latch) so a test can observe genuinely-pending queue state before releasing it.
 */
class PromptQueueDispatchTest {

    private static final class RecordingProvider implements LlmProvider {
        final List<String> receivedPrompts = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch holdLatch = new CountDownLatch(1);
        volatile boolean holdNext = false;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            receivedPrompts.add(prompt);
            if (holdNext) {
                holdNext = false;
                try { holdLatch.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            emitter.accept(ChatEvent.delta("reply #" + receivedPrompts.size()));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private record Rig(ActorRef<ChatSession> chatRef, ActorRef<PromptQueue> queueRef, RecordingProvider provider) {}

    private static Rig setUp() {
        RecordingProvider provider = new RecordingProvider();
        IIActorSystem system = new IIActorSystem("prompt-queue-dispatch-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();

        ActorRef<PromptQueue> queueRef = iiar.createChild(iiar.getName() + ".queue", new PromptQueue());
        queueRef.tellNow(q -> q.setSelf(queueRef)).join();

        return new Rig(iiar.asChatSessionRef(), queueRef, provider);
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        fail("condition not met within " + timeoutMs + "ms");
    }

    @Test
    void enqueue_whenIdle_dispatchesImmediately() {
        Rig r = setUp();
        r.queueRef().tellNow(q -> q.enqueue("hello", null, "queue", e -> {}, r.chatRef(), "human")).join();
        waitUntil(() -> !r.provider().receivedPrompts.isEmpty(), 2000);
        assertEquals(1, r.provider().receivedPrompts.size());
        // step 1 prefixes the system prompt (ChatSessionAgentLoop_260823_oo01) — check the tail.
        assertTrue(r.provider().receivedPrompts.get(0).endsWith("\n\nhello"));
    }

    @Test
    void pausedItem_blocksAutoDispatch_untilAdvance() {
        Rig r = setUp();
        r.provider().holdNext = true;
        r.queueRef().tellNow(q -> q.enqueue("first", null, "queue", e -> {}, r.chatRef(), "human")).join();
        waitUntil(() -> r.provider().receivedPrompts.size() == 1, 2000); // "first" is now blocked inside sendPrompt

        r.queueRef().tellNow(q -> q.enqueue("second", null, "queue", e -> {}, r.chatRef(), "human")).join();
        waitUntil(() -> r.queueRef().askNow(PromptQueue::getQueueSize).join() == 1, 1000);

        Boolean paused = r.queueRef().askNow(q -> q.setAuto(0, false)).join();
        assertTrue(paused);

        r.provider().holdLatch.countDown(); // let "first" finish -> onPromptComplete -> tryDispatch(force=false)
        waitUntil(() -> !r.chatRef().askNow(ChatSession::isBusy).join(), 2000);
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertEquals(1, r.provider().receivedPrompts.size(), "paused item must not auto-dispatch");
        assertEquals(1, r.queueRef().askNow(PromptQueue::getQueueSize).join());

        r.queueRef().tellNow(q -> q.advance(r.chatRef())).join();
        waitUntil(() -> r.provider().receivedPrompts.size() == 2, 2000);
        // step 1 of a new turn prefixes the system prompt again — check the tail.
        assertTrue(r.provider().receivedPrompts.get(1).endsWith("\n\nsecond"));
        assertEquals(0, r.queueRef().askNow(PromptQueue::getQueueSize).join());
    }

    @Test
    void removeAt_and_moveInQueue_operateOnPendingItems() {
        Rig r = setUp();
        r.provider().holdNext = true;
        r.queueRef().tellNow(q -> q.enqueue("first", null, "queue", e -> {}, r.chatRef(), "human")).join();
        waitUntil(() -> r.provider().receivedPrompts.size() == 1, 2000); // chat now busy, holding

        r.queueRef().tellNow(q -> q.enqueue("second", null, "queue", e -> {}, r.chatRef(), "human")).join();
        r.queueRef().tellNow(q -> q.enqueue("third", null, "queue", e -> {}, r.chatRef(), "human")).join();
        waitUntil(() -> r.queueRef().askNow(PromptQueue::getQueueSize).join() == 2, 1000);

        Boolean moved = r.queueRef().askNow(q -> q.moveInQueue(1, -1)).join(); // "third" up past "second"
        assertTrue(moved);
        List<PromptQueue.QueueEntry> snap = r.queueRef().askNow(PromptQueue::snapshot).join();
        assertEquals("third", snap.get(0).prompt());
        assertEquals("second", snap.get(1).prompt());

        Boolean removed = r.queueRef().askNow(q -> q.removeAt(0)).join();
        assertTrue(removed);
        snap = r.queueRef().askNow(PromptQueue::snapshot).join();
        assertEquals(1, snap.size());
        assertEquals("second", snap.get(0).prompt());

        r.provider().holdLatch.countDown(); // release the held first turn so no thread leaks past the test
    }
}
