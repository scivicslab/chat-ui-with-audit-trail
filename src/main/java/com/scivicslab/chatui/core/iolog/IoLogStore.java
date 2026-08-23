package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.H2LogStore;
import com.scivicslab.turingworkflow.plugins.logdb.SessionStatus;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the H2 store that persists the complete Claude I/O (the Sessions tab), plus the
 * conversation-scoped session id. Application-scoped so the store and the conversation session
 * outlive any single turn.
 *
 * <p>One H2 "session" = one conversation: opened on first use and reused across turns;
 * {@link #resetSession()} (a "new conversation" / {@code /clear}) ends it. The DB path is a startup
 * property ({@code chat-ui.iolog.db-path}, default {@code chat-ui-iolog}) with the instance's HTTP
 * port appended, so each instance opens its own file (e.g. {@code ./chat-ui-iolog-18090.mv.db}). The
 * port suffix keeps two instances from sharing one H2 store via {@code AUTO_SERVER}.</p>
 *
 * <p>Logging is best-effort: if the DB cannot be opened, the rest of the app keeps working and the
 * complete log is simply unavailable. Writes go straight to the store's own single writer thread, so
 * any thread may call {@link #record}.</p>
 */
@ApplicationScoped
public class IoLogStore {

    private static final Logger LOG = Logger.getLogger(IoLogStore.class.getName());

    @ConfigProperty(name = "chat-ui.iolog.db-path", defaultValue = "chat-ui-iolog")
    String dbPath;

    // The instance's HTTP port, appended to the DB path so two instances on different ports never open
    // the same H2 file (H2 opens with AUTO_SERVER=TRUE; without this a second process would connect
    // into the first instance's database and the two would write to one shared store).
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    // Lossless MVStore compression: the logged content is repetitive, so compression shrinks the
    // .mv.db file with no loss. Applies to newly written data.
    @ConfigProperty(name = "chat-ui.iolog.compress", defaultValue = "true")
    boolean compress;

    /** The configured db-path with the instance's HTTP port appended (per-instance DB isolation). */
    private String dbPathForPort() {
        return dbPath + "-" + httpPort;
    }

    private DistributedLogStore store;
    private long sessionId = -1;
    private boolean failed = false;

    private synchronized void ensureStore() {
        if (store != null || failed) {
            return;
        }
        try {
            store = new H2LogStore(Path.of(dbPathForPort()), compress);
            LOG.info("I/O log DB opened: " + Path.of(dbPathForPort()).toAbsolutePath() + ".mv.db"
                    + " (compress=" + compress + ")");
        } catch (Exception e) {
            failed = true;
            LOG.log(Level.SEVERE, "Failed to open I/O log DB; complete logging disabled", e);
        }
    }

    /** Returns the current conversation's session id, opening a session on first use; -1 if unavailable. */
    public synchronized long ensureSession() {
        ensureStore();
        if (store == null) {
            return -1;
        }
        if (sessionId < 0) {
            try {
                sessionId = store.startSession("chat-ui-conversation", 1);
                LOG.info("I/O log session started: " + sessionId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "startSession failed", e);
                return -1;
            }
        }
        return sessionId;
    }

    /** The current conversation's log session id, or -1 if none. */
    public synchronized long currentSessionId() {
        return sessionId;
    }

    /** Ends the current conversation session (called on "new conversation" / clear). */
    public synchronized void resetSession() {
        if (store != null && sessionId >= 0) {
            try {
                store.endSession(sessionId, SessionStatus.COMPLETED);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "endSession failed", e);
            }
        }
        sessionId = -1;
    }

    /** The shared store (read side for the Sessions view). May be null if logging is disabled. */
    public synchronized DistributedLogStore store() {
        ensureStore();
        return store;
    }

    /**
     * Writes one entry to the given session. Thread-safe: the store's single writer thread serializes
     * all writes, so any caller (any thread) may call this directly. No-op if logging is off or the
     * session id is invalid.
     */
    public void record(long sessionId, String node, String label, String content) {
        if (sessionId < 0) {
            return;
        }
        DistributedLogStore s = store();
        if (s == null) {
            return;
        }
        try {
            s.logAction(sessionId, node, label, "output", 0, 0L, content);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "I/O log record failed", e);
        }
    }

    // ---- Maintenance: prune old / unwanted log data ------------------------
    //
    // Deletes run over a short-lived, separate JDBC connection (H2 AUTO_SERVER mode allows multiple
    // connections to the same DB), so they never touch the store's read/write connections. The active
    // conversation session is always excluded, so a delete never contends with rows being written.

    /** The same JDBC URL H2LogStore uses, for a short-lived maintenance connection. */
    private String jdbcUrl() {
        return "jdbc:h2:" + Path.of(dbPathForPort()).toAbsolutePath() + ";AUTO_SERVER=TRUE"
                + (compress ? ";COMPRESS=TRUE" : "");
    }

    /**
     * Deletes one session and all of its logs / node_results. Refuses to delete the active
     * conversation session. Returns the number of sessions deleted (1 or 0), or -1 on error.
     */
    public synchronized int deleteSession(long id) {
        if (id < 0) return 0;
        if (id == sessionId) {
            LOG.warning("Refusing to delete the active conversation session " + id);
            return 0;
        }
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
            c.setAutoCommit(false);
            try {
                update(c, "DELETE FROM logs WHERE session_id = ?", id);
                update(c, "DELETE FROM node_results WHERE session_id = ?", id);
                int n = update(c, "DELETE FROM sessions WHERE id = ?", id);
                c.commit();
                LOG.info("Deleted log session " + id + " (" + n + " row)");
                return n;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "deleteSession failed for " + id, e);
            return -1;
        }
    }

    /**
     * Deletes every session started more than {@code days} days ago (and its logs / node_results),
     * excluding the active conversation session. Returns the number of sessions deleted, or -1 on error.
     */
    public synchronized int deleteSessionsOlderThan(int days) {
        if (days < 0) return 0;
        String pred = "started_at < DATEADD('DAY', ?, CURRENT_TIMESTAMP) AND id <> ?";
        try (Connection c = DriverManager.getConnection(jdbcUrl())) {
            c.setAutoCommit(false);
            try {
                update(c, "DELETE FROM logs WHERE session_id IN (SELECT id FROM sessions WHERE " + pred + ")",
                        -days, sessionId);
                update(c, "DELETE FROM node_results WHERE session_id IN (SELECT id FROM sessions WHERE " + pred + ")",
                        -days, sessionId);
                int n = update(c, "DELETE FROM sessions WHERE " + pred, -days, sessionId);
                c.commit();
                LOG.info("Deleted " + n + " session(s) older than " + days + " day(s)");
                return n;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "deleteSessionsOlderThan failed", e);
            return -1;
        }
    }

    /** Runs one parameterized update/delete and returns the affected-row count. */
    private static int update(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    @PreDestroy
    void shutdown() {
        if (store != null) {
            try {
                store.close();
            } catch (Exception e) {
                // shutting down; ignore
            }
        }
    }
}
