package com.scivicslab.chatui.core.iolog;

import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The record port of one owner of state: its own session of the I/O log
 * ({@code EachActorRecordsItsOwnState_261118_oo01}). An actor that holds state is given one when it is created, writes
 * one entry under its own label each time its state changes, and reads the last one back when it starts. The last
 * entry of a label is the current value, so each entry carries the owner's whole state for that label.
 *
 * <p>A conversation's session is {@code chat-ui-conversation-<name>}, a project's {@code chat-ui-project-<id>}, the
 * same sessions the earlier code wrote to, so what it wrote reads back through this port. Reading only looks for
 * the session: a read never opens one. The first write opens it, or continues the one the log still holds open.</p>
 *
 * <p>Not an actor and holds no state of its own: the owner's mailbox orders its writes, and the store behind the
 * log answers a read after every write told before it.</p>
 */
public final class SessionEntries {

    private static final Logger LOG = Logger.getLogger(SessionEntries.class.getName());

    private final String owner;
    private final IoLogStore log;
    private final IoLogView view;
    private final LongSupplier toWrite;
    private final LongSupplier toRead;

    private SessionEntries(String owner, IoLogStore log, IoLogView view, LongSupplier toWrite, LongSupplier toRead) {
        this.owner = owner;
        this.log = log;
        this.view = view;
        this.toWrite = toWrite;
        this.toRead = toRead;
    }

    /**
     * @param log  the I/O log, or {@code null} when logging is off (then writes are dropped and reads find nothing)
     * @param view what reads the log back, or {@code null} likewise
     * @param name the conversation's qualified name, e.g. {@code "project1/chat-02"}
     */
    public static SessionEntries ofConversation(IoLogStore log, IoLogView view, String name) {
        return log == null ? off(name)
                : new SessionEntries(name, log, view, () -> log.ensureSession(name), () -> log.findResumableSession(name));
    }

    /**
     * @param log       the I/O log, or {@code null} when logging is off
     * @param view      what reads the log back, or {@code null} likewise
     * @param projectId the project's id, e.g. {@code "project1"}
     */
    public static SessionEntries ofProject(IoLogStore log, IoLogView view, String projectId) {
        return log == null ? off(projectId)
                : new SessionEntries(projectId, log, view, () -> log.ensureProjectSession(projectId),
                        () -> log.findProjectSession(projectId));
    }

    private static SessionEntries off(String owner) {
        return new SessionEntries(owner, null, null, () -> -1, () -> -1);
    }

    /** Writes one entry under {@code label}; the next {@link #latest} of that label returns it. */
    public void write(String label, String content) {
        if (log == null) return;
        long session = toWrite.getAsLong();
        if (session >= 0) log.record(session, "agent", label, content);
    }

    /** @return the last entry under {@code label} as written, or {@code null} when there is none */
    public String latest(String label) {
        long session = sessionToRead();
        if (session < 0) return null;
        try {
            return view.latestOf(session, label);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the " + label + " of " + owner, e);
            return null;
        }
    }

    /**
     * The last entry under {@code label} that is a JSON object holding {@code key}: how an owner reads its value out
     * of an older entry that carried several owners' values together, which later entries without it follow.
     *
     * @return the parsed entry, or {@code null} when no entry under that label holds the key
     */
    public org.json.JSONObject latestWithKey(String label, String key) {
        long session = sessionToRead();
        if (session < 0) return null;
        try {
            return view.latestWithKey(session, label, key);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the " + label + " of " + owner, e);
            return null;
        }
    }

    private long sessionToRead() {
        return log == null || view == null ? -1 : toRead.getAsLong();
    }
}
