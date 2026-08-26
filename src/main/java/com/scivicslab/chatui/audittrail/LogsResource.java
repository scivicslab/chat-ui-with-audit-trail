package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.logging.LogTap;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Serves the two server-wide, non-tab-scoped log views: the raw {@link LogTap} capture ({@code
 * GET /api/logs}, unchanged) and the system-wide log multiplexer ({@code GET /api/system-log},
 * new — {@code 150_TabScopedLogging_260826_oo01}). The two differ in scope: {@code LogTap} catches
 * every JVM logger unconditionally; the system-wide multiplexer only sees what each tab's own log
 * multiplexer explicitly forwards to it, plus framework noise via {@code MultiplexerLogHandler}.
 */
@Path("/api")
public class LogsResource {

    @Inject
    LogTap logTap;

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Returns the most recent log entries captured by {@link LogTap}.
     *
     * @return up to 500 {@link LogTap.Entry} records, oldest first
     */
    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LogTap.Entry> logs() {
        return logTap.recent(500);
    }

    /**
     * Returns the system-wide log multiplexer's recent entries — every conversation tab's log
     * activity, tagged with the originating tab id.
     *
     * @return recent entries, oldest first
     */
    @GET
    @Path("/system-log")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RecentEntriesAccumulator.Entry> systemLog() {
        return actorSystem.getSystemLogEntries();
    }
}
