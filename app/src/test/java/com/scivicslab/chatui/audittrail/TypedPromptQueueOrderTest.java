package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.TurnReply;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the person types to the liaison is queued like any other prompt: in arrival order, not in the
 * urgent rank (ConversationReportingLines_261108_oo01). A reply from a subordinate that was waiting
 * first is sent first; the person can still move their prompt ahead by hand in the Queue panel.
 */
class TypedPromptQueueOrderTest {

    /** Holds its first prompt until released, so that the conversation is busy and later prompts wait. */
    private static final class HoldingProvider implements LlmProvider {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            if (started.getCount() > 0) {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void thePersonsPromptToTheLiaison_doesNotGoAheadOfAReplyQueuedEarlier() throws Exception {
        HoldingProvider provider = new HoldingProvider();
        IIActorSystem system = new IIActorSystem("typed-prompt-queue-order-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat-01", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        ActorRef<PromptQueue> queueRef = iiar.createChild(iiar.getName() + ".queue", new PromptQueue());
        queueRef.tellNow(q -> q.setSelf(queueRef)).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();

        try {
            // A turn is running, so whatever arrives now waits in the queue.
            queueRef.tellNow(q -> q.enqueue("running", null, "queue", e -> { }, chatRef, "human")).join();
            assertTrue(provider.started.await(5, TimeUnit.SECONDS), "the first turn did not start");

            // A subordinate's reply arrives first ...
            queueRef.tellNow(q -> q.enqueue("[reply to request project1/chat-01:s1:t1:r1, from chat-02]", null,
                    "queue", e -> { }, chatRef, ChatSession.ASK_CHAT_REPLY_SOURCE_PREFIX + "chat-02", null,
                    new CompletableFuture<TurnReply>(), false, true, List.of(), null, false)).join();
            // ... then the person types to the liaison.
            queueRef.tellNow(q -> ChatResource.enqueueTyped(q, "what is the status?", null, e -> { }, chatRef,
                    "screen", false, true, List.of())).join();

            List<PromptQueue.QueueEntry> waiting = queueRef.askNow(PromptQueue::snapshot).join();
            assertEquals(List.of("[reply to request project1/chat-01:s1:t1:r1, from chat-02]", "what is the status?"),
                    waiting.stream().map(PromptQueue.QueueEntry::prompt).toList(),
                    "the reply that was queued first must still be sent first");
            assertEquals(false, waiting.get(1).urgent(), "the person's prompt is not in the urgent rank");
        } finally {
            provider.release.countDown();
        }
    }
}
