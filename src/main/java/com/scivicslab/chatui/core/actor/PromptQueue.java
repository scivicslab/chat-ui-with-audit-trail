package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
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
 * <p>Backed by an index-addressable {@link List} (not a {@link java.util.Deque}) so the browser
 * can remove/reorder/pause specific queued items by position — see {@code removeAt}/
 * {@code moveInQueue}/{@code setAuto} ({@code QueueContentsEditing_260826_oo01}).</p>
 *
 * <p>This is a plain POJO actor — no CDI annotations, no synchronized blocks.
 * Thread safety is guaranteed by the actor's sequential message processing via ActorRef.</p>
 */
public class PromptQueue {

    private static final Logger LOG = Logger.getLogger(PromptQueue.class.getName());

    private final List<QueueItem> queue = new ArrayList<>();

    /**
     * This actor's own reference, set once by the generating side right after
     * {@code createChild} (mirrors {@code ChatSession.setActorSystem}). Lets
     * {@link #tryDispatch} — which must run on ChatSession's own thread, since it reads
     * {@code chat.isBusy()} and calls {@code chat.start(...)} — hand the actual
     * {@code queue.remove(0)} back to this actor's own thread via {@code self.ask(...)}, instead
     * of mutating {@code queue} directly from a foreign thread.
     */
    private ActorRef<PromptQueue> self;

    public void setSelf(ActorRef<PromptQueue> self) { this.self = self; }

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

        // New items always start auto=true (queued for immediate dispatch once ChatSession is
        // idle) — the browser can pause one afterward via setAuto(index, false), same as
        // quarkus-chat-ui3's own queue never lets the caller create a paused item directly.
        switch (mode) {
            case "cancel_and_send" -> {
                QueueItem item = new QueueItem(prompt, model, emitter, done, source, resultKey, noThink, true);
                queue.add(0, item);
                chatSessionRef.tell(ChatSession::cancel);
                emitter.accept(ChatEvent.info("Current prompt cancelled. Your message is queued."));
                LOG.info("cancel_and_send: cancelled current prompt, queued at front (queue size=" + queue.size() + ")");
            }
            default -> {
                // "queue" mode (default)
                QueueItem item = new QueueItem(prompt, model, emitter, done, source, resultKey, noThink, true);
                queue.add(item);
                emitter.accept(ChatEvent.info("Queued. Your message will be sent when the current prompt finishes."));
                LOG.info("queue: queued prompt (queue size=" + queue.size() + ")");
            }
        }

        requestDispatch(chatSessionRef, false);
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
        requestDispatch(chatSessionRef, false);
    }

    /**
     * Dispatches the front item immediately, ignoring its {@code auto} flag — the manual
     * "advance" action (browser-triggered, e.g. an empty-input Send, mirroring
     * {@code quarkus-chat-ui3}'s {@code sendFromQueue()}). Still requires {@code ChatSession} to
     * be idle, checked on ChatSession's own thread inside {@link #tryDispatch}.
     */
    public void advance(ActorRef<ChatSession> chatSessionRef) {
        requestDispatch(chatSessionRef, true);
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

    /**
     * Returns the prompt text, source, and auto flag of every currently-queued item, oldest
     * first — for display in the browser's Queue panel. {@code emitter}/{@code done} are
     * deliberately left out (not JSON-serializable, and irrelevant to the browser view).
     */
    public List<QueueEntry> snapshot() {
        return queue.stream().map(i -> new QueueEntry(i.prompt(), i.source(), i.auto())).toList();
    }

    /**
     * Removes the item at {@code index}, regardless of who queued it (
     * {@code QueueContentsEditing_260826_oo01} — deletion applies to every source, not just
     * human-typed items).
     *
     * @return {@code true} if an item was removed
     */
    public boolean removeAt(int index) {
        if (index < 0 || index >= queue.size()) return false;
        queue.remove(index);
        return true;
    }

    /**
     * Swaps the item at {@code index} with its neighbor in {@code direction} ({@code -1} = up,
     * i.e. earlier / sooner to send; {@code +1} = down).
     *
     * @return {@code true} if the swap happened
     */
    public boolean moveInQueue(int index, int direction) {
        int target = index + direction;
        if (index < 0 || index >= queue.size() || target < 0 || target >= queue.size()) return false;
        QueueItem tmp = queue.get(index);
        queue.set(index, queue.get(target));
        queue.set(target, tmp);
        return true;
    }

    /**
     * Sets whether the item at {@code index} auto-dispatches once it is at the front and
     * {@code ChatSession} is idle ({@code true}, the default), or waits for an explicit
     * {@link #advance} instead ({@code false}) — a manual checkpoint the browser can insert
     * into the queue.
     *
     * @return {@code true} if the item exists and was updated
     */
    public boolean setAuto(int index, boolean auto) {
        if (index < 0 || index >= queue.size()) return false;
        queue.set(index, queue.get(index).withAuto(auto));
        return true;
    }

    /**
     * One queued item's browser-facing summary.
     *
     * @param prompt the prompt text
     * @param source who queued it — {@code "human"} or {@code "agent:xxx"}
     * @param auto   whether this item auto-dispatches once it's at the front and idle
     */
    public record QueueEntry(String prompt, String source, boolean auto) {}

    // ---- Internal ----

    /**
     * Asks {@code chatSessionRef} to try dispatching, if idle — the only place outside this
     * class's own actor thread that ever touches the queue, and even here only indirectly via
     * {@link #tryDispatch}'s {@code self.ask(...)} round-trip.
     */
    private void requestDispatch(ActorRef<ChatSession> chatSessionRef, boolean force) {
        chatSessionRef.tell(chat -> tryDispatch(chat, chatSessionRef, force));
    }

    /**
     * Runs on {@code ChatSession}'s own actor thread (via {@code chatSessionRef.tell(...)} in
     * {@link #requestDispatch}) — safe to read {@code chat.isBusy()} and call
     * {@code chat.start(...)} directly. Does <strong>not</strong> touch {@code queue} directly:
     * that would mutate this actor's state from a foreign thread, exactly the race this method
     * exists to avoid. Instead it asks this actor's own thread (via {@link #self}) to pop the
     * front item atomically, and only proceeds if one was returned.
     *
     * <p>{@code chat.start(...)} and {@code chat.runUntilEnd()} (inherited from {@code
     * Interpreter}) are then called as plain Java methods, inside {@code ChatSession}'s own
     * {@code tell()} closure — not via {@code ChatSessionIIAR}'s generic {@code
     * callByActionName("runUntilEnd", ...)}, which would dispatch onto {@code IIActorSystem}'s
     * {@code ManagedThreadPool} and mutate ChatSession's fields off its own actor thread (see
     * {@code chat-session-agent-loop.yaml}'s own note, and {@code ChatSessionAgentLoop_260823_oo01}).
     * {@code runUntilEnd()} itself blocks this call until the turn reaches "end" — cheap, since
     * this is a virtual thread — so the whole turn, including every LLM call and tool execution
     * inside it, stays serialized on ChatSession's own thread.</p>
     *
     * @param force ignore the front item's {@code auto} flag ({@link #advance}'s manual
     *              dispatch) instead of respecting it (the default idle-triggered path — a
     *              paused, {@code auto=false} front item blocks dispatch entirely, exactly like
     *              {@code quarkus-chat-ui3}'s own queue never lets a later item skip ahead of a
     *              paused earlier one)
     */
    private void tryDispatch(ChatSession chat, ActorRef<ChatSession> chatSessionRef, boolean force) {
        if (chat.isBusy()) return;

        QueueItem item;
        try {
            item = self.ask(q -> q.popFront(!force)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to pop queue front", e);
            return;
        }
        if (item == null) return;

        chat.start(item.prompt(), item.model(), item.emitter(), chatSessionRef, item.done(), item.resultKey(),
                item.noThink());
        com.scivicslab.pojoactor.core.ActionResult result = chat.runUntilEnd();
        if (!result.isSuccess()) {
            LOG.warning("Agent loop did not reach 'end': " + result.getResult());
        }
    }

    /**
     * Pops and returns the front item, or {@code null} if the queue is empty or (when
     * {@code respectAuto}) the front item is paused ({@code auto=false}). The only method that
     * removes from {@code queue} for dispatch — always invoked via {@link #self}'s own actor
     * thread ({@link #tryDispatch}'s {@code self.ask(...)}), never called directly.
     */
    public QueueItem popFront(boolean respectAuto) {
        if (queue.isEmpty()) return null;
        if (respectAuto && !queue.get(0).auto()) return null;
        QueueItem item = queue.remove(0);
        LOG.info("Dequeuing prompt (remaining=" + queue.size() + "): " + truncate(item.prompt(), 80));
        return item;
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
            boolean noThink,
            boolean auto       // false = paused: waits for advanceNow instead of auto-dispatch
    ) {
        QueueItem withAuto(boolean newAuto) {
            return new QueueItem(prompt, model, emitter, done, source, resultKey, noThink, newAuto);
        }
    }
}
