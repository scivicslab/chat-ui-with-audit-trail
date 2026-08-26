package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.SseConnection;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.pojoactor.core.ActorRef;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestSseElementType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Bridges the browser and the {@code ActorSystem} — see {@code ChatResourceDesign_260823_oo01}.
 *
 * <p>Holds no {@code ActorRef} fields; every endpoint takes {@code tabId} and resolves the
 * relevant actor through {@link ChatUiActorSystem} on each call, the same shape the reference
 * {@code ChatResource} (quarkus-chat-ui/core) uses for its single global conversation.</p>
 */
@Path("/api")
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    // ── SSE stream ────────────────────────────────────────────────────────────

    /**
     * Opens (or re-opens, on reconnect) the SSE stream for one tab's conversation.
     *
     * @param tabId conversation tab identifier
     * @return the event stream, each element a JSON-serialized {@link ChatEvent}
     */
    @GET
    @Path("/tabs/{tabId}/chat/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestSseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream(@PathParam("tabId") String tabId) {
        actorSystem.createTab(tabId);
        ActorRef<SseConnection> sseRef = actorSystem.getSseConnection(tabId);
        try {
            return sseRef.ask(SseConnection::openStream).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to open SSE stream for tab " + tabId + ": " + e.getMessage());
            return Multi.createFrom().empty();
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    /**
     * Submits a prompt to one tab's conversation. Always returns immediately with an
     * acknowledgement; the actual content streams back over the already-open SSE connection
     * ({@link #stream}) — see {@code ChatSessionAgentLoop_260823_oo01} "なぜ`sendPrompt`は同期的に
     * 応答を返せないか" (the same reasoning applies here: the agent loop can run many LLM calls).
     *
     * @param tabId conversation tab identifier
     * @param body  {@code {"text": "...", "model": "..." (optional)}}
     * @return {@code {"type":"accepted"}}, or a 400 if {@code text} is missing/blank
     */
    @POST
    @Path("/tabs/{tabId}/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response chat(@PathParam("tabId") String tabId, Map<String, Object> body) {
        Object textVal = body != null ? body.get("text") : null;
        Object modelVal = body != null ? body.get("model") : null;
        Object noThinkVal = body != null ? body.get("noThink") : null;
        String text = textVal != null ? String.valueOf(textVal) : null;
        String model = modelVal != null && !String.valueOf(modelVal).isBlank() ? String.valueOf(modelVal) : null;
        boolean noThink = Boolean.TRUE.equals(noThinkVal);
        if (text == null || text.isBlank()) {
            return Response.status(400).entity(Map.of("type", "error", "message", "text is required")).build();
        }

        actorSystem.createTab(tabId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(tabId);
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        ActorRef<SseConnection> sseRef = actorSystem.getSseConnection(tabId);

        java.util.function.Consumer<ChatEvent> emitter = event -> sseRef.tell(a -> a.emit(event));
        promptQueueRef.tell(q -> q.enqueue(text, model, "queue", emitter,
                chatSessionIIAR.asChatSessionRef(), "human", null, new CompletableFuture<Void>(), noThink));

        return Response.ok(Map.of("type", "accepted")).build();
    }

    /**
     * Returns one tab's committed conversation history, for hydrating the left pane on load.
     *
     * @param tabId conversation tab identifier
     * @return up to the last 200 {@link ChatSession.HistoryEntry} records
     */
    @GET
    @Path("/tabs/{tabId}/conversation")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatSession.HistoryEntry> conversation(@PathParam("tabId") String tabId) {
        actorSystem.createTab(tabId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(tabId);
        try {
            return chatSessionIIAR.ask(interp -> ((ChatSession) interp).getHistory(200)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to read conversation for tab " + tabId + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Reports one tab's {@link PromptQueue} state, for the left pane's Queue indicator — this
     * project queues on the server (whenever {@code ChatSession} is busy), unlike
     * {@code quarkus-chat-ui3}'s client-side-only draft queue.
     *
     * @param tabId conversation tab identifier
     * @return {@code {"size": N, "hasPending": bool}}
     */
    @GET
    @Path("/tabs/{tabId}/queue")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> queue(@PathParam("tabId") String tabId) {
        actorSystem.createTab(tabId);
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        try {
            List<PromptQueue.QueueEntry> items = promptQueueRef.ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS);
            return Map.of("size", items.size(), "hasPending", !items.isEmpty(), "items", items);
        } catch (Exception e) {
            LOG.warning("Failed to read queue state for tab " + tabId + ": " + e.getMessage());
            return Map.of("size", 0, "hasPending", false, "items", List.of());
        }
    }

    /**
     * Returns one tab's recent log entries (the right-pane "System Log" tab, scoped to the
     * currently active tab — {@code 150_TabScopedLogging_260826_oo01}).
     *
     * @param tabId conversation tab identifier
     * @return recent entries from that tab's log multiplexer, oldest first; empty if the tab
     *         doesn't exist yet (no messages sent, nothing logged)
     */
    @GET
    @Path("/tabs/{tabId}/log")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RecentEntriesAccumulator.Entry> tabLog(@PathParam("tabId") String tabId) {
        List<RecentEntriesAccumulator.Entry> entries = actorSystem.getTabLogEntries(tabId);
        return entries != null ? entries : List.of();
    }

    /**
     * Removes the queued item at {@code index}, regardless of who queued it —
     * {@code QueueContentsEditing_260826_oo01} applies deletion to every source, not just
     * human-typed items.
     *
     * @param tabId conversation tab identifier
     * @param index position in the queue (0 = next to send)
     */
    @DELETE
    @Path("/tabs/{tabId}/queue/{index}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeQueueItem(@PathParam("tabId") String tabId, @PathParam("index") int index) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        if (promptQueueRef == null) return Response.status(404).build();
        try {
            boolean removed = promptQueueRef.ask(q -> q.removeAt(index)).get(5, TimeUnit.SECONDS);
            return removed ? Response.ok(Map.of("type", "removed")).build() : Response.status(404).build();
        } catch (Exception e) {
            LOG.warning("Failed to remove queue item " + index + " for tab " + tabId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Swaps the queued item at {@code index} with its neighbor.
     *
     * @param tabId conversation tab identifier
     * @param index position in the queue
     * @param body  {@code {"direction": "up"|"down"}}
     */
    @POST
    @Path("/tabs/{tabId}/queue/{index}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response moveQueueItem(@PathParam("tabId") String tabId, @PathParam("index") int index,
                                   Map<String, Object> body) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        if (promptQueueRef == null) return Response.status(404).build();
        int direction = "down".equals(body != null ? body.get("direction") : null) ? 1 : -1;
        try {
            boolean moved = promptQueueRef.ask(q -> q.moveInQueue(index, direction)).get(5, TimeUnit.SECONDS);
            return moved ? Response.ok(Map.of("type", "moved")).build() : Response.status(409).build();
        } catch (Exception e) {
            LOG.warning("Failed to move queue item " + index + " for tab " + tabId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Sets whether the queued item at {@code index} auto-dispatches once it's at the front and
     * {@code ChatSession} is idle, or waits as a manual checkpoint until {@link #advanceQueue}.
     *
     * @param tabId conversation tab identifier
     * @param index position in the queue
     * @param body  {@code {"auto": true|false}}
     */
    @POST
    @Path("/tabs/{tabId}/queue/{index}/auto")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setQueueItemAuto(@PathParam("tabId") String tabId, @PathParam("index") int index,
                                      Map<String, Object> body) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        if (promptQueueRef == null) return Response.status(404).build();
        boolean auto = !Boolean.FALSE.equals(body != null ? body.get("auto") : null);
        try {
            boolean set = promptQueueRef.ask(q -> q.setAuto(index, auto)).get(5, TimeUnit.SECONDS);
            return set ? Response.ok(Map.of("type", "updated")).build() : Response.status(404).build();
        } catch (Exception e) {
            LOG.warning("Failed to set auto for queue item " + index + " for tab " + tabId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Dispatches the queue's front item immediately, ignoring its {@code auto} flag — the
     * browser's manual "send the next one" action (empty-input Send), or the explicit resume
     * after pausing an item via {@link #setQueueItemAuto}.
     *
     * @param tabId conversation tab identifier
     */
    @POST
    @Path("/tabs/{tabId}/queue/advance")
    @Produces(MediaType.APPLICATION_JSON)
    public Response advanceQueue(@PathParam("tabId") String tabId) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(tabId);
        if (promptQueueRef == null || chatSessionIIAR == null) return Response.status(404).build();
        promptQueueRef.tell(q -> q.advance(chatSessionIIAR.asChatSessionRef()));
        return Response.ok(Map.of("type", "accepted")).build();
    }

    /**
     * Lists the models available from one tab's provider.
     *
     * @param tabId conversation tab identifier
     * @return the provider's {@link LlmProvider.ModelEntry} list
     */
    @GET
    @Path("/tabs/{tabId}/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LlmProvider.ModelEntry> models(@PathParam("tabId") String tabId) {
        actorSystem.createTab(tabId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(tabId);
        try {
            return chatSessionIIAR.ask(interp -> ((ChatSession) interp).getAvailableModels()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to list models for tab " + tabId + ": " + e.getMessage());
            return List.of();
        }
    }

    // ── Tab list (browser tab switcher) ─────────────────────────────────────────

    /**
     * Lists the ids of all conversation tabs created so far, for the browser's tab switcher.
     *
     * @return tab ids, sorted for a stable display order
     */
    @GET
    @Path("/tabs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> tabs() {
        return actorSystem.getTabIds().stream().sorted().toList();
    }

    /**
     * Creates {@code tabId} if it does not already exist. {@code POST /tabs/{tabId}/chat} also
     * creates the tab lazily on first prompt, so this exists only for the "+ New Tab" button,
     * which needs the (initially empty) tab to appear in the tab list before any prompt is sent.
     *
     * @param tabId conversation tab identifier
     * @return {@code {"type": "created"}}
     */
    @POST
    @Path("/tabs/{tabId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTab(@PathParam("tabId") String tabId) {
        actorSystem.createTab(tabId);
        return Response.ok(Map.of("type", "created")).build();
    }
}
