package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the Sessions tab (the complete LLM I/O log — request/response, including the full
 * system prompt, per LLM/tool call within the agent loop). Thin wrapper over {@link IoLogView},
 * which already does the shaping (ported unchanged from {@code quarkus-chat-ui3}).
 */
@Path("/api/sessions")
public class SessionsResource {

    @Inject
    IoLogStore ioLog;

    @Inject
    IoLogView ioLogView;

    /**
     * Lists sessions (most recent first).
     *
     * @return session summaries, shaped as {@code {sessionId, workflowName, startedAt, endedAt, status, totalLogEntries}}
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> sessions() {
        DistributedLogStore store = ioLog.store();
        if (store == null) return List.of();
        return store.listSessions(200).stream().map(SessionsResource::toMap).toList();
    }

    /**
     * Reconstructs one session's per-turn trace — the Sessions tab's primary view.
     *
     * @param id the session id
     * @return the ordered {@link IoLogView.TraceTurn} list
     */
    @GET
    @Path("/{id}/trace")
    @Produces(MediaType.APPLICATION_JSON)
    public List<IoLogView.TraceTurn> trace(@PathParam("id") long id) {
        return ioLogView.trace(id);
    }

    /**
     * Deletes one session and all of its logs. Refuses to delete the active conversation session.
     *
     * @param id the session id
     * @return {@code {"deleted": 1 or 0}}, or 500 on error
     */
    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteSession(@PathParam("id") long id) {
        int n = ioLog.deleteSession(id);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n)).build();
    }

    /**
     * Bulk-deletes sessions started more than {@code days} days ago (active session excluded).
     *
     * @param days age threshold in days
     * @return {@code {"deleted": N, "olderThanDays": days}}, or 500 on error
     */
    @DELETE
    @Path("/old")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteOldSessions(@QueryParam("days") @DefaultValue("30") int days) {
        int n = ioLog.deleteSessionsOlderThan(days);
        if (n < 0) return Response.status(500).entity(Map.of("error", "delete failed")).build();
        return Response.ok(Map.of("deleted", n, "olderThanDays", days)).build();
    }

    /**
     * Returns one log entry's full, untruncated text — lazily fetched when a trace message is
     * expanded in the browser (the trace itself only carries digests/previews).
     *
     * @param id    the session id
     * @param logId the log entry id
     * @return {@code {"message": "..."}}
     */
    @GET
    @Path("/{id}/entry/{logId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> entry(@PathParam("id") long id, @PathParam("logId") long logId) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("message", ioLogView.fullMessage(id, logId));
        return out;
    }

    private static Map<String, Object> toMap(SessionSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("workflowName", s.getWorkflowName());
        m.put("startedAt", String.valueOf(s.getStartedAt()));
        m.put("endedAt", s.getEndedAt() == null ? null : String.valueOf(s.getEndedAt()));
        m.put("status", String.valueOf(s.getStatus()));
        m.put("totalLogEntries", s.getTotalLogEntries());
        return m;
    }
}
