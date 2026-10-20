package com.scivicslab.chatui.core.iolog;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import com.scivicslab.turingworkflow.plugins.logdb.SessionStatus;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link DistributedLogStore} whose calls go through an actor that wraps a plain store.
 *
 * <p>The plain store ({@code H2LogStore}) runs each call on the thread that makes it. This puts
 * an actor in front of it, so that the actor's mailbox is the queue: a log line is {@code tell}'d
 * and the caller moves on; a read or a session start is {@code ask}'d and returns after every
 * write told before it, from any thread, in the order the actor received them. Nothing here
 * starts a thread of its own or keeps a queue of its own.</p>
 *
 * <p>{@link #getConnection()} hands out the store's read connection as it is: a caller that reads
 * through it directly is not behind the mailbox, so it calls {@link #flushWrites()} first if it
 * needs what it has just written.</p>
 */
public final class ActorBackedLogStore implements DistributedLogStore {

    private static final Logger LOG = Logger.getLogger(ActorBackedLogStore.class.getName());

    private final ActorSystem actors = new ActorSystem("io-log");
    private final ActorRef<DistributedLogStore> store;
    private final Connection readConnection;

    /** @param plain the store to wrap; this takes it over and closes it in {@link #close()} */
    public ActorBackedLogStore(DistributedLogStore plain) {
        this.readConnection = plain.getConnection();
        this.store = actors.actorOf("io-log.store", plain);
    }

    /** A write nobody waits for: told to the actor; one that fails is logged, not lost. */
    private void tell(String what, java.util.function.Consumer<DistributedLogStore> write) {
        store.tell(write).exceptionally(e -> {
            LOG.log(Level.WARNING, "I/O log " + what + " failed", e);
            return null;
        });
    }

    /** A call whose answer the caller needs: asked of the actor, returned after every earlier tell. */
    private <R> R ask(java.util.function.Function<DistributedLogStore, R> call) {
        try {
            return store.ask(call).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    @Override public Connection getConnection() { return readConnection; }

    @Override public long startSession(String workflowName, int nodeCount) {
        return ask(s -> s.startSession(workflowName, nodeCount));
    }

    @Override public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount) {
        return ask(s -> s.startSession(workflowName, overlayName, inventoryName, nodeCount));
    }

    @Override public long startSession(String workflowName, String overlayName, String inventoryName, int nodeCount,
                                       String cwd, String gitCommit, String gitBranch,
                                       String commandLine, String pluginVersion, String pluginCommit) {
        return ask(s -> s.startSession(workflowName, overlayName, inventoryName, nodeCount,
                cwd, gitCommit, gitBranch, commandLine, pluginVersion, pluginCommit));
    }

    @Override public void log(long sessionId, String nodeId, LogLevel level, String message) {
        tell("log", s -> s.log(sessionId, nodeId, level, message));
    }

    @Override public void log(long sessionId, String nodeId, String label, LogLevel level, String message) {
        tell("log", s -> s.log(sessionId, nodeId, label, level, message));
    }

    @Override public void logAction(long sessionId, String nodeId, String label,
                                    String actionName, int exitCode, long durationMs, String output) {
        tell("action record", s -> s.logAction(sessionId, nodeId, label, actionName, exitCode, durationMs, output));
    }

    @Override public void markNodeSuccess(long sessionId, String nodeId) {
        tell("node result", s -> s.markNodeSuccess(sessionId, nodeId));
    }

    @Override public void markNodeFailed(long sessionId, String nodeId, String reason) {
        tell("node result", s -> s.markNodeFailed(sessionId, nodeId, reason));
    }

    @Override public void endSession(long sessionId, SessionStatus status) {
        ask(s -> {
            s.endSession(sessionId, status);
            return null;
        });
    }

    @Override public List<LogEntry> getLogsByNode(long sessionId, String nodeId) {
        return ask(s -> s.getLogsByNode(sessionId, nodeId));
    }

    @Override public List<LogEntry> getLogsByLevel(long sessionId, LogLevel minLevel) {
        return ask(s -> s.getLogsByLevel(sessionId, minLevel));
    }

    @Override public SessionSummary getSummary(long sessionId) {
        return ask(s -> s.getSummary(sessionId));
    }

    @Override public long getLatestSessionId() {
        return ask(DistributedLogStore::getLatestSessionId);
    }

    @Override public List<SessionSummary> listSessions(int limit) {
        return ask(s -> s.listSessions(limit));
    }

    /** Returns once every write told before the call has been done, as an ask behind them does. */
    @Override public void flushWrites() {
        ask(s -> {
            s.flushWrites();
            return null;
        });
    }

    /** Closes the wrapped store after the writes told before, then stops the actor system. */
    @Override public void close() throws Exception {
        try {
            ask(s -> {
                try {
                    s.close();
                } catch (Exception e) {
                    throw new IllegalStateException("closing the I/O log failed", e);
                }
                return null;
            });
        } finally {
            actors.terminate();
        }
    }
}
