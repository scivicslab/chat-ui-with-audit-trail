package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * How the four conversation-inspection tools ({@code get_chat_status}, {@code list_chat_models},
 * {@code set_chat_model}, {@code get_chat_log}) and {@code ask_chat} write what they found.
 *
 * <p>Kept apart from the tools so the wording can be tested without an actor system. Every result is
 * plain lines of {@code key: value}, or an {@code error: ...} line, the way the other tools answer.</p>
 */
public final class ChatInspection {

    private ChatInspection() {}

    /** Most log entries one {@code get_chat_log} call returns, however many are asked for. */
    public static final int MAX_LOG_ENTRIES = 200;
    /** How many {@code get_chat_log} returns when the caller names no number. */
    public static final int DEFAULT_LOG_ENTRIES = 20;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * @param status the values of one conversation, in the order they are written
     * @return one {@code key: value} line per entry
     */
    public static String formatStatus(Map<String, Object> status) {
        return status.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    /** @return one line per model, {@code name (type, server)}, or a sentence saying there are none */
    public static String formatModels(List<LlmProvider.ModelEntry> models) {
        if (models == null || models.isEmpty()) return "no models are listed for this conversation's provider";
        return models.stream()
                .map(m -> m.name() + " (" + nonBlank(m.type()) + ", " + nonBlank(m.server()) + ")")
                .collect(Collectors.joining("\n"));
    }

    /**
     * Whether {@code model} may be set, given what the conversation's provider lists.
     *
     * <p>A name the provider does not list is refused, with the list in the answer. The broker
     * renamed its models once, and a conversation left on the old name failed every call with a
     * 404 that said nothing about the name. When the provider lists nothing, any name is let through,
     * because there is nothing to compare it with.</p>
     *
     * @return {@code null} when the name may be set, otherwise the {@code error: ...} line
     */
    public static String refusalFor(String model, List<LlmProvider.ModelEntry> available) {
        if (model == null || model.isBlank()) return "error: model is required";
        if (available == null || available.isEmpty()) return null;
        for (LlmProvider.ModelEntry m : available) {
            if (model.equals(m.name())) return null;
        }
        return "error: '" + model + "' is not one of this conversation's models; available:\n"
                + available.stream().map(LlmProvider.ModelEntry::name).collect(Collectors.joining("\n"));
    }

    /**
     * @param entries a conversation's log, oldest first
     * @param count   how many of the newest to return; {@code null} or not positive means
     *                {@link #DEFAULT_LOG_ENTRIES}, and more than {@link #MAX_LOG_ENTRIES} is cut to it
     * @return the newest {@code count} entries, oldest first, one per line as {@code HH:mm:ss source type data}
     */
    public static String formatLog(List<RecentEntriesAccumulator.Entry> entries, Integer count) {
        if (entries == null || entries.isEmpty()) return "the log is empty";
        int n = (count == null || count <= 0) ? DEFAULT_LOG_ENTRIES : Math.min(count, MAX_LOG_ENTRIES);
        List<RecentEntriesAccumulator.Entry> tail = entries.subList(Math.max(0, entries.size() - n), entries.size());
        return tail.stream()
                .map(e -> TIME.format(Instant.ofEpochMilli(e.time())) + " " + e.source() + " " + e.type()
                        + " " + oneLine(e.data()))
                .collect(Collectors.joining("\n"));
    }

    /** How a turn ended and how much it used, for the line {@code ask_chat} adds to a reply. */
    public static final String ENDED_ANSWERED = "answered";
    /** The turn stopped because its workflow's step limit was reached. */
    public static final String ENDED_STEP_LIMIT = "step-limit";

    /**
     * @param ending    {@link #ENDED_ANSWERED} or {@link #ENDED_STEP_LIMIT}
     * @param modelCalls how many times the turn called the model
     * @param toolCalls  how many tools the turn ran
     * @return the one line {@code ask_chat} puts after the reply
     */
    public static String endingLine(String ending, int modelCalls, int toolCalls) {
        return "[ended: " + ending + "; model calls: " + modelCalls + "; tool calls: " + toolCalls + "]";
    }

    private static String nonBlank(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
