package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.SseConnection;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
        String text = textVal != null ? String.valueOf(textVal) : null;
        String model = modelVal != null && !String.valueOf(modelVal).isBlank() ? String.valueOf(modelVal) : null;
        if (text == null || text.isBlank()) {
            return Response.status(400).entity(Map.of("type", "error", "message", "text is required")).build();
        }

        actorSystem.createTab(tabId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(tabId);
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(tabId);
        ActorRef<SseConnection> sseRef = actorSystem.getSseConnection(tabId);

        java.util.function.Consumer<ChatEvent> emitter = event -> sseRef.tell(a -> a.emit(event));
        promptQueueRef.tell(q -> q.enqueue(text, model, "queue", emitter,
                chatSessionIIAR.asChatSessionRef(), "human", null, new CompletableFuture<Void>()));

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
            int size = promptQueueRef.ask(PromptQueue::getQueueSize).get(5, TimeUnit.SECONDS);
            return Map.of("size", size, "hasPending", size > 0);
        } catch (Exception e) {
            LOG.warning("Failed to read queue state for tab " + tabId + ": " + e.getMessage());
            return Map.of("size", 0, "hasPending", false);
        }
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
}
