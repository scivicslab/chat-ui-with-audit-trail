package com.scivicslab.chatui.core.provider;

import java.util.List;

/**
 * What a conversation did before this process started, read back from a record kept on disk, for
 * {@code get_chat_log} to show when its in-memory log holds fewer entries than were asked for
 * ({@code GetChatLogReadsPersistedHistory_261119_oo01}).
 *
 * @param source  where the entries were read from, e.g. {@code history:transcript}; every entry
 *                is shown with it, so a reader can tell them from the log's live entries
 * @param entries the entries, oldest first
 * @param note    why fewer entries came back than could have, e.g. that the record does not
 *                exist; {@code null} when nothing needs saying
 */
public record PersistedLog(String source, List<Entry> entries, String note) {

    /**
     * One recorded event.
     *
     * @param time epoch milliseconds the record gives for it
     * @param type what kind of event, e.g. {@code user}, {@code assistant}, {@code tool_use}
     * @param text what was said or done
     */
    public record Entry(long time, String type, String text) {}

    public PersistedLog {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** @return a log with no entries, saying why */
    public static PersistedLog none(String source, String note) {
        return new PersistedLog(source, List.of(), note);
    }
}
