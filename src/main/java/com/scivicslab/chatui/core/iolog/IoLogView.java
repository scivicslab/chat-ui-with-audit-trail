package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read/shaping side of the complete I/O log (Sessions tab). Pulls a session's {@link LogEntry} rows
 * from the {@link DistributedLogStore} and shapes them with the Java Stream API: group by agent,
 * filter, sort, preview, cap, and reconstruct the per-turn trace. Ported from quarkus-chat-ui3; the
 * stored-message marker format ({@code turnN/stepM/llm|tool} labels + REQUEST/RESPONSE/REASONING/
 * USAGE / TOOL/INPUT/OBSERVATION sections) is identical, so the same shaping applies to Claude turns.
 */
@ApplicationScoped
public class IoLogView {

    /** Default and maximum number of entries returned by one logs() call. */
    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;
    /** Preview length (chars) returned in the list; the full message is fetched on expand. */
    static final int PREVIEW_CHARS = 500;

    @Inject
    IoLogStore ioLog;

    /** One agent (actor / AI agent) in a session, with its log-line count. The agent-axis index. */
    public record AgentInfo(String agent, long lines) {}

    /** One shaped log entry for the list. {@code message} is a preview; full text via fullMessage(). */
    public record Entry(long id, String time, String agent, String label, String level,
                        int levelValue, String message, int chars, boolean truncated,
                        int promptTokens, int completionTokens) {}

    /** A bounded page of entries. {@code limited} is true when more matched than were returned. */
    public record Page(List<Entry> entries, int returned, boolean limited) {}

    /** Filter for logs(): any field may be null/blank to mean "no constraint". */
    public record Filter(String agent, String label, String level, String q,
                         String since, String until, int limit) {}

    /** One step of the reconstructed trace (an {@code llm} or {@code tool} step). */
    public record TraceStep(long id, String label, String kind, String thought, String toolCalls,
                            String reason, String toolName, String toolInput, String observation,
                            int obsChars, int promptTokens, int completionTokens, boolean finalAnswer) {}

    /** One conversation turn: the user's prompt and the ordered trace steps for that turn. */
    public record TraceTurn(int turn, String userPrompt, List<TraceStep> steps) {}

    // ── Public API (DB-backed) ──────────────────────────────────────────────

    /** Lists the agents in a session with their line counts (agent-axis index). */
    public List<AgentInfo> agents(long sessionId) {
        return agentsOf(allLogs(sessionId));
    }

    /** Returns a shaped, filtered, bounded page of one session's I/O. */
    public Page logs(long sessionId, Filter f) {
        List<LogEntry> raw = (f.agent() != null && !f.agent().isBlank())
                ? store().getLogsByNode(sessionId, f.agent())
                : allLogs(sessionId);
        return shape(raw, f);
    }

    /** Returns the full (untruncated) message of one entry, or "" if not found. */
    public String fullMessage(long sessionId, long logId) {
        return allLogs(sessionId).stream()
                .filter(e -> e.getId() == logId)
                .map(e -> e.getMessage() == null ? "" : e.getMessage())
                .findFirst()
                .orElse("");
    }

    /** Reconstructs the per-turn trace for a session. */
    public List<TraceTurn> trace(long sessionId) {
        return traceOf(allLogs(sessionId));
    }

    private List<LogEntry> allLogs(long sessionId) {
        DistributedLogStore s = store();
        // DEBUG is the lowest level, so "at least DEBUG" returns every row of the session.
        return s == null ? List.of() : s.getLogsByLevel(sessionId, LogLevel.DEBUG);
    }

    private DistributedLogStore store() {
        return ioLog.store();
    }

    // ── Pure shaping (unit-testable: no DB) ───────────────────────────────────

    /** Length of the observation digest shown in the trace (the full size is reported separately). */
    static final int OBS_DIGEST = 240;
    private static final java.util.regex.Pattern TURN_LABEL =
            java.util.regex.Pattern.compile("turn(\\d+)/step(\\d+)/(llm|tool)");

    static List<TraceTurn> traceOf(List<LogEntry> raw) {
        List<LogEntry> ordered = raw.stream()
                .sorted(Comparator.comparing(IoLogView::ts).thenComparing(LogEntry::getId))
                .toList();
        Map<Integer, List<TraceStep>> byTurn = new java.util.LinkedHashMap<>();
        Map<Integer, String> userPrompts = new java.util.HashMap<>();
        for (LogEntry e : ordered) {
            java.util.regex.Matcher m = TURN_LABEL.matcher(nz(e.getLabel()));
            if (!m.find()) continue;
            int turn = Integer.parseInt(m.group(1));
            String kind = m.group(3);
            String msg = nz(e.getMessage());
            TraceStep step;
            if ("tool".equals(kind)) {
                String name = between(msg, "TOOL:", "INPUT:", "OBSERVATION:");
                String input = between(msg, "INPUT:", "OBSERVATION:");
                String obs = between(msg, "OBSERVATION:");
                step = new TraceStep(e.getId(), nz(e.getLabel()), "tool", "", "", "", name, input,
                        digest(obs), obs.length(), -1, -1, false);
            } else {
                String thought = between(msg, "RESPONSE:", "REASONING:", "TOOL_CALLS:", "USAGE:");
                String toolCalls = between(msg, "TOOL_CALLS:", "USAGE:");
                boolean finalAnswer = toolCalls.isBlank();
                step = new TraceStep(e.getId(), nz(e.getLabel()), "llm", thought, toolCalls,
                        extractReasons(toolCalls), "", "", "", 0,
                        parseTokenCount(msg, "promptTokens="), parseTokenCount(msg, "completionTokens="),
                        finalAnswer);
                userPrompts.computeIfAbsent(turn,
                        k -> extractUserPrompt(between(msg, "REQUEST:", "RESPONSE:", "REASONING:", "TOOL_CALLS:", "USAGE:")));
            }
            byTurn.computeIfAbsent(turn, k -> new java.util.ArrayList<>()).add(step);
        }
        return byTurn.entrySet().stream()
                .map(en -> new TraceTurn(en.getKey(), userPrompts.getOrDefault(en.getKey(), ""), en.getValue()))
                .sorted(Comparator.comparingInt(TraceTurn::turn))
                .toList();
    }

    static String extractReasons(String toolCallsText) {
        if (toolCallsText == null || toolCallsText.isBlank()) return "";
        java.util.List<String> reasons = new java.util.ArrayList<>();
        for (String line : toolCallsText.split("\n")) {
            int b = line.indexOf('{');
            if (b < 0) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode args = TRACE_MAPPER.readTree(line.substring(b));
                String r = args.path("reason").asText("");
                if (!r.isBlank()) reasons.add(r);
            } catch (Exception ignore) {
                // malformed line: skip, keep any reasons already parsed
            }
        }
        return String.join("  /  ", reasons);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper TRACE_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static String extractUserPrompt(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode messages =
                    TRACE_MAPPER.readTree(requestJson).path("messages");
            String last = "";
            if (messages.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode msg : messages) {
                    if ("user".equals(msg.path("role").asText())) {
                        last = msg.path("content").asText("");
                    }
                }
            }
            return last;
        } catch (Exception ex) {
            return "";
        }
    }

    /** Returns the text between {@code start} and the first following {@code stops} marker (or end). Pure. */
    static String between(String msg, String start, String... stops) {
        if (msg == null) return "";
        int i = msg.indexOf(start);
        if (i < 0) return "";
        int from = i + start.length();
        int end = msg.length();
        for (String s : stops) {
            int k = msg.indexOf(s, from);
            if (k >= 0 && k < end) end = k;
        }
        return msg.substring(from, end).strip();
    }

    /** One-line, length-capped digest of an observation for the trace view. Pure. */
    static String digest(String s) {
        String t = (s == null ? "" : s).replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > OBS_DIGEST ? t.substring(0, OBS_DIGEST) : t;
    }

    /** Distinct agents with line counts, sorted by name. Pure. */
    static List<AgentInfo> agentsOf(List<LogEntry> raw) {
        Map<String, Long> counts = raw.stream()
                .collect(Collectors.groupingBy(e -> nz(e.getNodeId()), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(en -> new AgentInfo(en.getKey(), en.getValue()))
                .sorted(Comparator.comparing(AgentInfo::agent))
                .toList();
    }

    /** Filters (label/level/text/time), sorts by time, caps, and maps to preview entries. Pure. */
    static Page shape(List<LogEntry> raw, Filter f) {
        int limit = f.limit() <= 0 ? DEFAULT_LIMIT : Math.min(f.limit(), MAX_LIMIT);
        LogLevel min = parseLevel(f.level());
        LocalDateTime since = parseTime(f.since());
        LocalDateTime until = parseTime(f.until());
        String q = (f.q() == null || f.q().isBlank()) ? null : f.q().toLowerCase();
        String label = (f.label() == null || f.label().isBlank()) ? null : f.label().toLowerCase();

        List<LogEntry> matched = raw.stream()
                .filter(e -> label == null || nz(e.getLabel()).toLowerCase().contains(label))
                .filter(e -> min == null || (e.getLevel() != null && e.getLevel().isAtLeast(min)))
                .filter(e -> q == null || nz(e.getMessage()).toLowerCase().contains(q))
                .filter(e -> since == null || (e.getTimestamp() != null && !e.getTimestamp().isBefore(since)))
                .filter(e -> until == null || (e.getTimestamp() != null && !e.getTimestamp().isAfter(until)))
                .sorted(Comparator.comparing(IoLogView::ts))
                .toList();

        List<Entry> page = matched.stream()
                .limit(limit)
                .map(IoLogView::toEntry)
                .toList();
        return new Page(page, page.size(), matched.size() > page.size());
    }

    private static Entry toEntry(LogEntry e) {
        String msg = nz(e.getMessage());
        boolean trunc = msg.length() > PREVIEW_CHARS;
        String preview = trunc ? msg.substring(0, PREVIEW_CHARS) : msg;
        String level = e.getLevel() == null ? "" : e.getLevel().name();
        int levelValue = e.getLevel() == null ? 0 : e.getLevel().getPriority();
        return new Entry(e.getId(), String.valueOf(e.getTimestamp()), nz(e.getNodeId()),
                nz(e.getLabel()), level, levelValue, preview, msg.length(), trunc,
                parseTokenCount(msg, "promptTokens="), parseTokenCount(msg, "completionTokens="));
    }

    static int parseTokenCount(String msg, String key) {
        if (msg == null) return -1;
        int i = msg.lastIndexOf(key);
        if (i < 0) return -1;
        int j = i + key.length();
        int start = j;
        while (j < msg.length() && Character.isDigit(msg.charAt(j))) j++;
        if (j == start) return -1;
        try { return Integer.parseInt(msg.substring(start, j)); }
        catch (NumberFormatException ex) { return -1; }
    }

    private static LogLevel parseLevel(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LogLevel.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s.trim()); }
        catch (Exception e) { return null; }
    }

    private static LocalDateTime ts(LogEntry e) {
        return e.getTimestamp() == null ? LocalDateTime.MIN : e.getTimestamp();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
