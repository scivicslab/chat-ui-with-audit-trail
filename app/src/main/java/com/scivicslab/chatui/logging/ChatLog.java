package com.scivicslab.chatui.logging;

import com.scivicslab.chatui.core.provider.PersistedLog;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code get_chat_log} returns for one conversation: the newest entries of its in-memory log,
 * with the entries it is short of filled in front from the conversation's persisted history
 * ({@code GetChatLogReadsPersistedHistory_261119_oo01}).
 *
 * @param entries     the entries, oldest first
 * @param historyNote why the persisted history gave fewer entries than were missing, or
 *                    {@code null}
 */
public record ChatLog(List<RecentEntriesAccumulator.Entry> entries, String historyNote) {

    /** Reads a conversation's persisted history; see {@code LlmProvider.persistedLog}. */
    @FunctionalInterface
    public interface History {
        /**
         * @param limit  how many entries at most
         * @param before epoch milliseconds; only entries recorded earlier
         * @return the entries, oldest first; never {@code null}
         */
        PersistedLog read(int limit, long before);
    }

    /**
     * @param live    the conversation's in-memory log, oldest first
     * @param wanted  how many entries the caller asked for
     * @param history read only when {@code live} holds fewer than {@code wanted}
     * @return at most {@code wanted} entries, oldest first
     */
    public static ChatLog fill(List<RecentEntriesAccumulator.Entry> live, int wanted, History history) {
        if (live.size() >= wanted) {
            return new ChatLog(List.copyOf(live.subList(live.size() - wanted, live.size())), null);
        }
        // The in-memory log drops nothing until it holds more than any count a caller may ask for,
        // so its oldest entry is the first one written since this process started: history older
        // than that cannot also be among the live entries.
        long before = live.isEmpty() ? Long.MAX_VALUE : live.get(0).time();
        PersistedLog persisted = history.read(wanted - live.size(), before);
        List<RecentEntriesAccumulator.Entry> all = new ArrayList<>();
        for (PersistedLog.Entry e : persisted.entries()) {
            all.add(new RecentEntriesAccumulator.Entry(e.time(), persisted.source(), e.type(), e.text()));
        }
        all.addAll(live);
        return new ChatLog(List.copyOf(all.subList(Math.max(0, all.size() - wanted), all.size())), persisted.note());
    }
}
