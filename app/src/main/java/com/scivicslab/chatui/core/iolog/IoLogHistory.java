package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.core.provider.PersistedLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a conversation's history back from the I/O log, for {@code get_chat_log} after a restart
 * when the conversation's provider keeps no record of its own
 * ({@code GetChatLogReadsPersistedHistory_261119_oo01}).
 *
 * <p>Each turn's question and answer ({@code turnN/conversation}) become a {@code user} and an
 * {@code assistant} entry; each step's record ({@code turnN/stepM/...}) becomes a {@code step}
 * entry. Records that are not a turn's ({@code settings}, {@code name}) and a turn's bookkeeping
 * ({@code ids}, {@code answer-meta}) are left out. Every session the conversation's name was
 * recorded under is read, ended ones included, newest record first and no more than asked for.</p>
 */
public final class IoLogHistory {

    /** The {@code source} every entry read here is shown with. */
    public static final String SOURCE = "history:iolog";

    /** What {@link IoLogStore} prefixes a ConversationSquad's name with to name its session. */
    private static final String CONVERSATION_PREFIX = "chat-ui-conversation-";

    private final IoLogStore ioLog;

    /** @param ioLog the store the conversations are recorded in; {@code null} where there is none */
    public IoLogHistory(IoLogStore ioLog) {
        this.ioLog = ioLog;
    }

    /**
     * @param conversationSquadName the conversation's name, e.g. {@code project1/chat-02}
     * @param limit                 how many entries at most
     * @param before                epoch milliseconds; only records written earlier
     * @return the entries, oldest first; never {@code null}, and an exception is never thrown
     */
    public PersistedLog read(String conversationSquadName, int limit, long before) {
        if (ioLog == null) return PersistedLog.none(SOURCE, "this instance keeps no I/O log");
        if (limit <= 0) return PersistedLog.none(SOURCE, null);
        String workflowName = CONVERSATION_PREFIX + conversationSquadName;
        String sql = """
                SELECT l.id, l.timestamp, l.label, l.message
                FROM logs l JOIN sessions s ON s.id = l.session_id
                WHERE s.workflow_name = ?
                  AND (l.label LIKE 'turn%/conversation' OR l.label LIKE 'turn%/step%')
                """ + (before == Long.MAX_VALUE ? "" : "  AND l.timestamp < ?\n") + """
                ORDER BY l.timestamp DESC, l.id DESC
                LIMIT ?
                """;
        // A short-lived connection of its own, as IoLogSearch takes one: a read here never waits
        // on, or holds up, the writer the conversations are being recorded through.
        try (Connection c = DriverManager.getConnection(ioLog.jdbcUrl())) {
            if (!hasSession(c, workflowName)) {
                return PersistedLog.none(SOURCE, "no I/O log session for this conversation");
            }
            List<PersistedLog.Entry> newestFirst = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                ps.setString(i++, workflowName);
                if (before != Long.MAX_VALUE) ps.setTimestamp(i++, new Timestamp(before));
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long time = rs.getTimestamp("timestamp").getTime();
                        addNewestFirst(newestFirst, time, rs.getString("label"), rs.getString("message"));
                    }
                }
            }
            List<PersistedLog.Entry> kept = newestFirst.subList(0, Math.min(limit, newestFirst.size()));
            List<PersistedLog.Entry> oldestFirst = new ArrayList<>(kept);
            Collections.reverse(oldestFirst);
            return new PersistedLog(SOURCE, oldestFirst, null);
        } catch (Exception e) {
            return PersistedLog.none(SOURCE, "the I/O log could not be read: " + e.getMessage());
        }
    }

    private static boolean hasSession(Connection c, String workflowName) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sessions WHERE workflow_name = ? LIMIT 1")) {
            ps.setString(1, workflowName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Adds the entries one record makes, newest first: an answer goes before its question. */
    private static void addNewestFirst(List<PersistedLog.Entry> out, long time, String label, String message) {
        if (label.endsWith("/conversation")) {
            IoLogView.Turn turn = IoLogView.parseConversationEntry(message);
            if (turn == null) return;
            out.add(new PersistedLog.Entry(time, "assistant",
                    turn.error() != null ? "Error: " + turn.error() : turn.answer()));
            out.add(new PersistedLog.Entry(time, "user", turn.question()));
        } else {
            out.add(new PersistedLog.Entry(time, "step", label + " " + (message == null ? "" : message)));
        }
    }
}
