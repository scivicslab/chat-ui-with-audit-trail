package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.logging.LogTap;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Serves the right-pane "System Log" tab — the most recent server-wide log records captured by
 * {@link LogTap} (every logger in the JVM, not scoped to any one conversation tab).
 */
@Path("/api/logs")
public class LogsResource {

    @Inject
    LogTap logTap;

    /**
     * Returns the most recent log entries.
     *
     * @return up to 500 {@link LogTap.Entry} records, oldest first
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<LogTap.Entry> logs() {
        return logTap.recent(500);
    }
}
