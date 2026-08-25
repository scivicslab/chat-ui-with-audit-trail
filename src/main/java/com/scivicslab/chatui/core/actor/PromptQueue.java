package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Actor that manages message queueing when the ChatSession is busy processing a prompt.
 *
 * <p>Supports two modes:</p>
 * <ul>
 *   <li><b>queue</b> - Accept the message, hold it, and send it when ChatSession becomes idle.
 *       If ChatSession is already idle, dispatches immediately.</li>
 *   <li><b>cancel_and_send</b> - Cancel the current prompt, add the new message to the front
 *       of the queue, and send it once ChatSession becomes idle.</li>
 * </ul>
 *
 * <p>This is a plain POJO actor — no CDI annotations, no synchronized blocks.
 * Thread safety is guaranteed by the actor's sequential message processing via ActorRef.</p>
 */
public class PromptQueue {

    private static final Logger LOG = Logger.getLogger(PromptQueue.class.getName());

    private final Deque<QueueItem> queue = new ArrayDeque<>();

    /**
     * Enqueues a prompt. Convenience overload without resultKey (resultKey defaults to null).
     */
    public void enqueue(String prompt, String model, String mode,
                        Consumer<ChatEvent> emitter,
                        ActorRef<ChatSession> chatSessionRef,
                        String source) {
        enqueue(prompt, model, mode, emitter, chatSessionRef, source, null);
    }

    /**
     * Main entry point for enqueuing a prompt when ChatSession may be busy.
     *
     * <p>After adding to the queue, immediately asks ChatSession if it is idle.
     * If idle, dispatches the next item without waiting for a tick.</p>
     *
     * @param prompt          the prompt text
     * @param model           the model to use (may be null)
     * @param mode            one of "queue", "cancel_and_send"
     * @param emitter         callback to send ChatEvent responses to the client
     * @param chatSessionRef  reference to the ChatSession
     * @param source          "human" or "agent:xxx"
     * @param resultKey       UUID for MCP result tracking, or null for human prompts
     */
    public void enqueue(String prompt, String model, String mode,
                        Consumer<ChatEvent> emitter,
                        ActorRef<ChatSession> chatSessionRef,
                        String source, String resultKey) {

        CompletableFuture<Void> done = new CompletableFuture<>();
        enqueue(prompt, model, mode, emitter, chatSessionRef, source, resultKey, done);
    }

    /**
     * Enqueues a prompt with a caller-supplied {@code done} future.
     *
     * <p>Use this overload when the caller needs to block until the prompt completes
     * (e.g. MCP {@code tools/call} handler waiting for the LLM result).</p>
     *
     * @param done externally created future that is completed when ChatSession finishes the prompt
     */
    public void enqueue(String prompt, String model, String mode,
                        Consumer<ChatEvent> emitter,
                        ActorRef<ChatSession> chatSessionRef,
                        String source, String resultKey,
                        CompletableFuture<Void> done) {
        enqueue(prompt, model, mode, emitter, chatSessionRef, source, resultKey, done, false);
    }

    public void enqueue(String prompt, String model, String mode,
                        Consumer<ChatEvent> emitter,
                        ActorRef<ChatSession> chatSessionRef,
                        String source, String resultKey,
                        CompletableFuture<Void> done, boolean noThink) {

        switch (mode) {
            case "cancel_and_send" -> {
                QueueItem item = new QueueItem(prompt, model, emitter, done, source, resultKey, noThink);
                queue.addFirst(item);
                chatSessionRef.tell(ChatSession::cancel);
                emitter.accept(ChatEvent.info("Current prompt cancelled. Your message is queued."));
                LOG.info("cancel_and_send: cancelled current prompt, queued at front (queue size=" + queue.size() + ")");
            }
            default -> {
                // "queue" mode (default)
                QueueItem item = new QueueItem(prompt, model, emitter, done, source, resultKey, noThink);
                queue.addLast(item);
                emitter.accept(ChatEvent.info("Queued. Your message will be sent when the current prompt finishes."));
                LOG.info("queue: queued prompt (queue size=" + queue.size() + ")");
            }
        }

        // Attempt immediate dispatch if ChatSession is already idle
        chatSessionRef.ask(ChatSession::isBusy).thenAccept(busy -> {
            if (!busy) {
                chatSessionRef.tell(chat -> dequeueAndSend(chat, chatSessionRef));
            }
        });
    }

    /**
     * Removes all agent-sourced messages from the queue, leaving human-typed messages intact.
     * Called on cancel to stop ongoing agent conversations without discarding
     * messages the human has already queued up.
     *
     * <p>Agent messages have source values of the form {@code "agent:xxx"}
     * (e.g. {@code "agent:localhost:28900"}).</p>
     */
    public void clearAgentMessages() {
        int before = queue.size();
        queue.removeIf(e -> e.source() != null && e.source().startsWith("agent:"));
        int removed = before - queue.size();
        if (removed > 0) {
            LOG.info("queue: cleared " + removed + " agent messages on cancel");
        }
    }

    /**
     * Called when ChatSession finishes a prompt. Triggers immediate dequeue attempt
     * if the queue is not empty.
     *
     * @param chatSessionRef reference to the ChatSession
     */
    public void onPromptComplete(ActorRef<ChatSession> chatSessionRef) {
        if (queue.isEmpty()) return;

        chatSessionRef.tell(chat -> dequeueAndSend(chat, chatSessionRef));
    }

    /**
     * Returns the current number of items in the queue.
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Returns true if the queue has pending items.
     */
    public boolean hasPending() {
        return !queue.isEmpty();
    }

    // ---- Internal ----

    /**
     * Dequeues the next item and runs it through ChatSession's agent loop.
     * Runs within ChatSession's message context (via tell), so isBusy() is safe to read directly.
     *
     * <p>{@code chat.start(...)} and {@code chat.runUntilEnd()} (inherited from {@code Interpreter})
     * are called here as plain Java methods, inside this actor's own {@code tell()} closure — not
     * via {@code ChatSessionIIAR}'s generic {@code callByActionName("runUntilEnd", ...)}, which
     * would dispatch onto {@code IIActorSystem}'s {@code ManagedThreadPool} and mutate ChatSession's
     * fields off its own actor thread (see {@code chat-session-agent-loop.yaml}'s own note, and
     * {@code ChatSessionAgentLoop_260823_oo01}). {@code runUntilEnd()} itself blocks this call until
     * the turn reaches "end" — cheap, since this is a virtual thread — so the whole turn, including
     * every LLM call and tool execution inside it, stays serialized on ChatSession's own thread.</p>
     */
    private void dequeueAndSend(ChatSession chat, ActorRef<ChatSession> chatSessionRef) {
        if (queue.isEmpty()) return;
        if (chat.isBusy()) return;

        QueueItem item = queue.pollFirst();
        if (item == null) return;

        LOG.info("Dequeuing prompt (remaining=" + queue.size() + "): "
                + truncate(item.prompt(), 80));

        chat.start(item.prompt(), item.model(), item.emitter(), chatSessionRef, item.done(), item.resultKey(),
                item.noThink());
        com.scivicslab.pojoactor.core.ActionResult result = chat.runUntilEnd();
        if (!result.isSuccess()) {
            LOG.warning("Agent loop did not reach 'end': " + result.getResult());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Represents a queued prompt with its metadata.
     */
    public record QueueItem(
            String prompt,
            String model,
            Consumer<ChatEvent> emitter,
            CompletableFuture<Void> done,
            String source,     // "human" | "agent:xxx" (e.g. "agent:localhost:28900")
            String resultKey,  // UUID for MCP result tracking, null for human prompts
            boolean noThink
    ) {}
}
