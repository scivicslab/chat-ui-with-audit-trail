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
 * <p>Holds no {@code ActorRef} fields; every endpoint takes {@code chatId} and resolves the
 * relevant actor through {@link ChatUiActorSystem} on each call, the same shape the reference
 * {@code ChatResource} (quarkus-chat-ui/core) uses for its single global conversation.</p>
 */
@Path("/api/projects")
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class.getName());

    @Inject
    ChatUiActorSystem actorSystem;

    // ── Projects ──────────────────────────────────────────────────────────────

    /**
     * Creates a new project and its first conversation
     * ({@code ProjectScopedActorTree_260829_oo01}).
     *
     * @return {@code {"projectId": "project<N>"}}; its first conversation's id is {@code "01"}
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response createProject() {
        return Response.ok(Map.of("projectId", actorSystem.createProject())).build();
    }

    /**
     * Points a project at a working directory and loads that directory's {@code AGENTS.md} — or
     * {@code CLAUDE.md} when there is no {@code AGENTS.md} — as instructions for every conversation
     * in that project ({@code SkillAndAgentsFile_260830_oo01}).
     *
     * @param projectId  the project to point
     * @param workingDir the directory's path; an empty body clears it
     * @return {@code {"type":"ok","message":...}}, or 400 with the reason
     */
    @POST
    @Path("/{projectId}/working-dir")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setWorkingDir(@PathParam("projectId") String projectId, String workingDir) {
        String trimmed = workingDir == null ? "" : workingDir.strip();
        String outcome = actorSystem.setProjectWorkingDir(projectId,
                trimmed.isEmpty() ? null : java.nio.file.Path.of(trimmed).toAbsolutePath());
        if (outcome.startsWith("error:")) {
            return Response.status(400).entity(Map.of("type", "error", "message", outcome)).build();
        }
        return Response.ok(Map.of("type", "ok", "message", outcome)).build();
    }

    /**
     * Switches this conversation's prompt-construction sub-workflow — the file that decides what
     * text each agent-loop step sends ({@code DocRetrievalAgentLoop_260830_oo01} 型2).
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @param file      classpath file name under {@code /workflows/}, e.g.
     *                  {@code prompt-construction-doc-retrieval.yaml}
     * @return {@code {"type":"ok","promptWorkflow":...}}, or 404 if the conversation does not exist
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/prompt-workflow")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setPromptWorkflow(@PathParam("projectId") String projectId,
                                       @PathParam("chatId") String chatId, String file) {
        ChatSessionIIAR session = actorSystem.getChatSession(projectId, chatId);
        if (session == null) {
            return Response.status(404).entity(Map.of("type", "error", "message", "no such conversation")).build();
        }
        String name = file == null ? "" : file.strip();
        if (name.isEmpty()) {
            return Response.status(400).entity(Map.of("type", "error", "message", "file name required")).build();
        }
        session.tell(a -> ((ChatSession) a).setPromptWorkflowFile(name));
        return Response.ok(Map.of("type", "ok", "promptWorkflow", name)).build();
    }

    /**
     * Starts a plan on this conversation from a YAML body, without going through its LLM
     * ({@code DirectPlanSubmission_260830_oo01}). Returns as soon as the plan has been handed to
     * its runner; the result appears in this conversation's log when it finishes.
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @param yaml      the plan, as Turing-workflow YAML text
     * @return {@code {"type": "accepted"}}, or 400 with the reason
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/plan")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitPlan(@PathParam("projectId") String projectId,
                                @PathParam("chatId") String chatId, String yaml) {
        actorSystem.createChat(projectId, chatId);
        String error = actorSystem.submitPlan(projectId, chatId, yaml);
        if (error != null) {
            return Response.status(400).entity(Map.of("type", "error", "message", error)).build();
        }
        return Response.ok(Map.of("type", "accepted")).build();
    }

    /**
     * Asks this conversation's plan runner to stop.
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return {@code {"type": "stopping"}}, or 404 if the conversation has no plan runner
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/plan/stop")
    @Produces(MediaType.APPLICATION_JSON)
    public Response stopPlan(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        if (!actorSystem.stopPlan(projectId, chatId)) {
            return Response.status(404).entity(Map.of("type", "error", "message", "no plan running")).build();
        }
        return Response.ok(Map.of("type", "stopping")).build();
    }

    // ── SSE stream ────────────────────────────────────────────────────────────

    /**
     * Opens (or re-opens, on reconnect) the SSE stream for one tab's conversation.
     *
     * @param chatId conversation tab identifier
     * @return the event stream, each element a JSON-serialized {@link ChatEvent}
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/chat/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestSseElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ActorRef<SseConnection> sseRef = actorSystem.getSseConnection(projectId, chatId);
        try {
            return sseRef.ask(SseConnection::openStream).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to open SSE stream for tab " + chatId + ": " + e.getMessage());
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
     * @param chatId conversation tab identifier
     * @param body  {@code {"text": "...", "model": "..." (optional)}}
     * @return {@code {"type":"accepted"}}, or a 400 if {@code text} is missing/blank
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response chat(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId, Map<String, Object> body) {
        Object textVal = body != null ? body.get("text") : null;
        Object modelVal = body != null ? body.get("model") : null;
        Object noThinkVal = body != null ? body.get("noThink") : null;
        String text = textVal != null ? String.valueOf(textVal) : null;
        String model = modelVal != null && !String.valueOf(modelVal).isBlank() ? String.valueOf(modelVal) : null;
        boolean noThink = Boolean.TRUE.equals(noThinkVal);
        if (text == null || text.isBlank()) {
            return Response.status(400).entity(Map.of("type", "error", "message", "text is required")).build();
        }

        actorSystem.createChat(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        ActorRef<SseConnection> sseRef = actorSystem.getSseConnection(projectId, chatId);

        java.util.function.Consumer<ChatEvent> emitter = event -> sseRef.tell(a -> a.emit(event));
        promptQueueRef.tell(q -> q.enqueue(text, model, "queue", emitter,
                chatSessionIIAR.asChatSessionRef(), "human", null, new CompletableFuture<Void>(), noThink));

        return Response.ok(Map.of("type", "accepted")).build();
    }

    /**
     * Returns one tab's committed conversation history, for hydrating the left pane on load. Reads
     * a safely-published snapshot directly ({@code BusyStateReadableSnapshot_260828_oo01}) instead
     * of asking the tab's actor — so this keeps working even while the tab is busy (e.g. mid
     * {@code ask_chat} wait), rather than queueing behind it and timing out.
     *
     * @param chatId conversation tab identifier
     * @return up to the last 200 {@link ChatSession.HistoryEntry} records
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/conversation")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatSession.HistoryEntry> conversation(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        return chatSessionIIAR.getHistorySnapshotDirect();
    }

    /**
     * Reports whether one tab is currently busy processing a turn — read directly, so it works even
     * while the tab is busy ({@code BusyStateReadableSnapshot_260828_oo01}).
     *
     * @param chatId conversation tab identifier
     * @return {@code {"busy": true|false}}
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        return Map.of("busy", chatSessionIIAR.isBusyDirect());
    }

    /**
     * Reports one tab's {@link PromptQueue} state, for the left pane's Queue indicator — this
     * project queues on the server (whenever {@code ChatSession} is busy), unlike
     * {@code quarkus-chat-ui3}'s client-side-only draft queue.
     *
     * @param chatId conversation tab identifier
     * @return {@code {"size": N, "hasPending": bool}}
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/queue")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> queue(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        try {
            List<PromptQueue.QueueEntry> items = promptQueueRef.ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS);
            return Map.of("size", items.size(), "hasPending", !items.isEmpty(), "items", items);
        } catch (Exception e) {
            LOG.warning("Failed to read queue state for tab " + chatId + ": " + e.getMessage());
            return Map.of("size", 0, "hasPending", false, "items", List.of());
        }
    }

    /**
     * Returns one tab's recent log entries (the right-pane "System Log" tab, scoped to the
     * currently active tab — {@code 150_TabScopedLogging_260826_oo01}).
     *
     * @param chatId conversation tab identifier
     * @return recent entries from that tab's log multiplexer, oldest first; empty if the tab
     *         doesn't exist yet (no messages sent, nothing logged)
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/log")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RecentEntriesAccumulator.Entry> tabLog(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        List<RecentEntriesAccumulator.Entry> entries = actorSystem.getChatLogEntries(projectId, chatId);
        return entries != null ? entries : List.of();
    }

    /**
     * Removes the queued item at {@code index}, regardless of who queued it —
     * {@code QueueContentsEditing_260826_oo01} applies deletion to every source, not just
     * human-typed items.
     *
     * @param chatId conversation tab identifier
     * @param index position in the queue (0 = next to send)
     */
    @DELETE
    @Path("/{projectId}/chats/{chatId}/queue/{index}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeQueueItem(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId, @PathParam("index") int index) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        if (promptQueueRef == null) return Response.status(404).build();
        try {
            boolean removed = promptQueueRef.ask(q -> q.removeAt(index)).get(5, TimeUnit.SECONDS);
            return removed ? Response.ok(Map.of("type", "removed")).build() : Response.status(404).build();
        } catch (Exception e) {
            LOG.warning("Failed to remove queue item " + index + " for tab " + chatId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Swaps the queued item at {@code index} with its neighbor.
     *
     * @param chatId conversation tab identifier
     * @param index position in the queue
     * @param body  {@code {"direction": "up"|"down"}}
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/queue/{index}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response moveQueueItem(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId, @PathParam("index") int index,
                                   Map<String, Object> body) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        if (promptQueueRef == null) return Response.status(404).build();
        int direction = "down".equals(body != null ? body.get("direction") : null) ? 1 : -1;
        try {
            boolean moved = promptQueueRef.ask(q -> q.moveInQueue(index, direction)).get(5, TimeUnit.SECONDS);
            return moved ? Response.ok(Map.of("type", "moved")).build() : Response.status(409).build();
        } catch (Exception e) {
            LOG.warning("Failed to move queue item " + index + " for tab " + chatId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Sets whether the queued item at {@code index} auto-dispatches once it's at the front and
     * {@code ChatSession} is idle, or waits as a manual checkpoint until {@link #advanceQueue}.
     *
     * @param chatId conversation tab identifier
     * @param index position in the queue
     * @param body  {@code {"auto": true|false}}
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/queue/{index}/auto")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setQueueItemAuto(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId, @PathParam("index") int index,
                                      Map<String, Object> body) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        if (promptQueueRef == null) return Response.status(404).build();
        boolean auto = !Boolean.FALSE.equals(body != null ? body.get("auto") : null);
        try {
            boolean set = promptQueueRef.ask(q -> q.setAuto(index, auto)).get(5, TimeUnit.SECONDS);
            return set ? Response.ok(Map.of("type", "updated")).build() : Response.status(404).build();
        } catch (Exception e) {
            LOG.warning("Failed to set auto for queue item " + index + " for tab " + chatId + ": " + e.getMessage());
            return Response.status(500).build();
        }
    }

    /**
     * Dispatches the queue's front item immediately, ignoring its {@code auto} flag — the
     * browser's manual "send the next one" action (empty-input Send), or the explicit resume
     * after pausing an item via {@link #setQueueItemAuto}.
     *
     * @param chatId conversation tab identifier
     */
    @POST
    @Path("/{projectId}/chats/{chatId}/queue/advance")
    @Produces(MediaType.APPLICATION_JSON)
    public Response advanceQueue(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        ActorRef<PromptQueue> promptQueueRef = actorSystem.getPromptQueue(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        if (promptQueueRef == null || chatSessionIIAR == null) return Response.status(404).build();
        promptQueueRef.tell(q -> q.advance(chatSessionIIAR.asChatSessionRef()));
        return Response.ok(Map.of("type", "accepted")).build();
    }

    /**
     * Lists the models available from one tab's provider.
     *
     * @param chatId conversation tab identifier
     * @return the provider's {@link LlmProvider.ModelEntry} list
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/models")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LlmProvider.ModelEntry> models(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        try {
            return chatSessionIIAR.ask(interp -> ((ChatSession) interp).getAvailableModels()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to list models for tab " + chatId + ": " + e.getMessage());
            return List.of();
        }
    }

    // ── Agent Loop tab (read-only workflow YAML viewer, tab-scoped) ─────────────

    /** One entry in a tab's workflow catalog. {@code name} is the classpath basename (no {@code .yaml}). */
    public record WorkflowInfo(String name, String title) {}

    /**
     * Lists the workflow YAML files configured for one tab: its agent-loop workflow ({@code
     * ChatSessionIIAR.agentLoopWorkflowFile}) and its prompt-construction sub-workflow ({@code
     * ChatSession.promptWorkflowFile}) — each tab can be configured independently (today both
     * default to the same file since only one of each exists on the classpath).
     *
     * @param chatId conversation tab identifier
     * @return this tab's workflow catalog, agent loop first
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/workflows")
    @Produces(MediaType.APPLICATION_JSON)
    public List<WorkflowInfo> workflows(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId) {
        actorSystem.createChat(projectId, chatId);
        ChatSessionIIAR chatSessionIIAR = actorSystem.getChatSession(projectId, chatId);
        String agentLoopFile = chatSessionIIAR.getAgentLoopWorkflowFile();
        String promptFile;
        try {
            promptFile = chatSessionIIAR.ask(interp -> ((ChatSession) interp).getPromptWorkflowFile())
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warning("Failed to read prompt workflow file for tab " + chatId + ": " + e.getMessage());
            promptFile = null;
        }
        List<WorkflowInfo> catalog = new java.util.ArrayList<>();
        catalog.add(new WorkflowInfo(stripYamlExt(agentLoopFile), "Agent Loop"));
        if (promptFile != null) {
            catalog.add(new WorkflowInfo(stripYamlExt(promptFile), "Prompt Construction"));
        }
        return catalog;
    }

    /**
     * Returns one workflow's read-only YAML, read fresh from the classpath (so it always reflects
     * the file actually bundled in this build — no per-tab in-memory copy).
     *
     * @param chatId conversation tab identifier (unused beyond confirming the tab exists — the YAML
     *              itself is a classpath resource, not per-tab state)
     * @param name  the workflow's classpath basename, as returned by {@link #workflows}
     * @return {@code {"name": ..., "yaml": ..., "editable": false}}, or 404 if not found
     */
    @GET
    @Path("/{projectId}/chats/{chatId}/workflows/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response workflowYaml(@PathParam("projectId") String projectId, @PathParam("chatId") String chatId, @PathParam("name") String name) {
        String resource = "/workflows/" + name + ".yaml";
        try (java.io.InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "unknown workflow: " + name)).build();
            }
            String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return Response.ok(Map.of("name", name, "yaml", yaml, "editable", false)).build();
        } catch (Exception e) {
            LOG.warning("Failed to read workflow " + name + ": " + e.getMessage());
            return Response.status(500).entity(Map.of("error", "read failed")).build();
        }
    }

    private static String stripYamlExt(String fileName) {
        return fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    // ── Tab list (browser tab switcher) ─────────────────────────────────────────

    /**
     * Lists the ids of one project's conversations, for the browser's switcher.
     *
     * @param projectId owning project's id
     * @return conversation ids within that project, sorted for a stable display order
     */
    @GET
    @Path("/{projectId}/chats")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> chats(@PathParam("projectId") String projectId) {
        String prefix = projectId + "/chat-";
        return actorSystem.getChatNames().stream()
                .filter(name -> name.startsWith(prefix))
                .map(name -> name.substring(prefix.length()))
                .sorted()
                .toList();
    }

    /**
     * Creates the conversation if it does not already exist. Sending a prompt also creates it
     * lazily, so this exists only for the "+ New Chat" button, which needs the (initially empty)
     * conversation to appear in the list before any prompt is sent.
     *
     * @param projectId owning project's id
     * @param chatId    conversation id within that project
     * @return {@code {"type": "created"}}
     */
    @POST
    @Path("/{projectId}/chats/{chatId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response createChat(@PathParam("projectId") String projectId,
                               @PathParam("chatId") String chatId,
                               @jakarta.ws.rs.QueryParam("under") String under) {
        try {
            actorSystem.createChat(projectId, chatId, under);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("type", "error", "message", e.getMessage())).build();
        }
        return Response.ok(Map.of("type", "created")).build();
    }
}
