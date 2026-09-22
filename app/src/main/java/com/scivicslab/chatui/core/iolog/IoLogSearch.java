package com.scivicslab.chatui.core.iolog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Searches every conversation this instance has recorded at once
 * ({@code CrossConversationLogSearch_260913_oo01}).
 *
 * <p>The Sessions tab shows one conversation: the tab that is open. This reads the whole database
 * instead, and a conversation whose actor was removed is in it on the same terms as one that is
 * still on the screen — {@code loader.removeChild} takes the actor out of the registry and leaves
 * what it said in H2. Once the ConversationSquad is gone, this is the only way back to what was said in it.</p>
 *
 * <p>The match is a substring of the message, not a word. Japanese text has no spaces to split on,
 * and a morphological analyser drops proper nouns its dictionary does not carry, so a substring
 * match is what finds a program name typed mid-sentence. Modelled on
 * {@code quarkus-AI-workspace}'s {@code ConversationLogSearch}, which reads the same schema; that
 * one searches several instances' databases merged into one file, this one searches the database
 * this process is writing.</p>
 */
@ApplicationScoped
public class IoLogSearch {

    private static final Logger LOG = Logger.getLogger(IoLogSearch.class.getName());

    /** How much of the message is shown around the match. */
    private static final int SNIPPET_MARGIN = 140;

    /** What {@link IoLogStore} prefixes a ConversationSquad's name with to name its session. */
    private static final String CONVERSATION_PREFIX = "chat-ui-conversation-";

    @Inject
    IoLogStore ioLog;

    /**
     * One matching entry.
     *
     * @param logId        the row in {@code logs}, for reading the whole entry
     * @param sessionId    the conversation it belongs to
     * @param when         when it was recorded
     * @param conversation the ConversationSquad it was recorded in, e.g. {@code project1/chat-01}
     * @param agent        which actor wrote it
     * @param label        the entry's label, e.g. {@code turn7/step1/llm}
     * @param turn         the turn number read out of the label, or {@code -1} when it holds none
     * @param snippet      the text around the match
     */
    public record Hit(long logId, long sessionId, String when, String conversation, String agent,
                      String label, int turn, String snippet) {}

    /**
     * What one search found.
     *
     * @param hits    the matching entries, newest first
     * @param limited true when more matched than were returned
     * @param error   what went wrong, or {@code ""} when nothing did
     */
    public record Result(List<Hit> hits, boolean limited, String error) {}

    /**
     * What the database holds, for the screen to say what was searched.
     *
     * @param present       whether the log database could be read at all
     * @param conversations how many sessions it holds
     * @param entries       how many log entries it holds
     * @param newest        the most recent entry's time, or {@code ""} when there are none
     * @param error         what went wrong, or {@code ""} when nothing did
     */
    public record Overview(boolean present, long conversations, long entries, String newest,
                           String error) {}

    /** @return how much there is to search, for the screen to show */
    public Overview overview() {
        String sql = """
                SELECT (SELECT COUNT(*) FROM sessions) AS conversations,
                       (SELECT COUNT(*) FROM logs) AS entries,
                       (SELECT MAX(timestamp) FROM logs) AS newest
                """;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new Overview(true, rs.getLong("conversations"), rs.getLong("entries"),
                        text(rs.getTimestamp("newest")), "");
            }
            return new Overview(true, 0, 0, "", "");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the I/O log database", e);
            return new Overview(false, 0, 0, "", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Finds the entries whose text contains {@code query}.
     *
     * @param query what to look for; blank finds nothing rather than everything
     * @param limit how many entries to return at most
     * @return the matching entries, newest first
     */
    public Result search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), false, "");
        }
        // One row over the limit, so that "there are more" is known without counting every match.
        String sql = """
                SELECT l.id, l.session_id, l.timestamp, l.node_id, l.label, l.message,
                       s.workflow_name
                FROM logs l JOIN sessions s ON s.id = l.session_id
                WHERE LOWER(l.message) LIKE ?
                ORDER BY l.timestamp DESC
                LIMIT ?
                """;
        List<Hit> hits = new ArrayList<>();
        boolean limited = false;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + query.toLowerCase() + "%");
            ps.setInt(2, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hits.size() == limit) {
                        limited = true;
                        break;
                    }
                    String label = nz(rs.getString("label"));
                    hits.add(new Hit(
                            rs.getLong("id"),
                            rs.getLong("session_id"),
                            text(rs.getTimestamp("timestamp")),
                            conversationOf(rs.getString("workflow_name")),
                            nz(rs.getString("node_id")),
                            label,
                            turnOf(label),
                            snippet(rs.getString("message"), query)));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "I/O log search failed", e);
            return new Result(List.of(), false, String.valueOf(e.getMessage()));
        }
        return new Result(List.copyOf(hits), limited, "");
    }

    /**
     * Opens the log database for one call.
     *
     * <p>A short-lived connection of its own, the way the deletes in {@link IoLogStore} take one:
     * a read here then never waits on, or holds up, the writer the conversations are being
     * recorded through.</p>
     */
    private Connection open() throws Exception {
        return DriverManager.getConnection(ioLog.jdbcUrl());
    }

    /**
     * @param workflowName the session's recorded name
     * @return the ConversationSquad it names, e.g. {@code project1/chat-01}; the name unchanged when
     *         it is not a conversation's (a session some other program wrote into this file)
     */
    static String conversationOf(String workflowName) {
        String name = nz(workflowName);
        return name.startsWith(CONVERSATION_PREFIX) ? name.substring(CONVERSATION_PREFIX.length()) : name;
    }

    /**
     * The turn an entry belongs to, so that opening a hit can show the whole turn it was part of.
     *
     * @param label an entry's label, e.g. {@code turn7/step1/llm}
     * @return the number after {@code turn}, or {@code -1} when the label is not a turn's
     */
    static int turnOf(String label) {
        String text = nz(label);
        if (!text.startsWith("turn")) {
            return -1;
        }
        int end = 4;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 4) {
            return -1;
        }
        try {
            return Integer.parseInt(text.substring(4, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Cuts the text around the first match.
     *
     * @param message the whole entry
     * @param query   what was searched for
     * @return the text around the match, with an ellipsis on each side that was cut
     */
    static String snippet(String message, String query) {
        if (message == null) {
            return "";
        }
        String flat = message.replaceAll("\\s+", " ").strip();
        int at = flat.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0) {
            // The match was on the unflattened text: a query spanning a line break.
            return flat.length() <= SNIPPET_MARGIN * 2 ? flat
                    : flat.substring(0, SNIPPET_MARGIN * 2) + "…";
        }
        int from = Math.max(0, at - SNIPPET_MARGIN);
        int to = Math.min(flat.length(), at + query.length() + SNIPPET_MARGIN);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    private static String text(Timestamp t) {
        return t == null ? "" : t.toLocalDateTime().toString().replace('T', ' ');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
