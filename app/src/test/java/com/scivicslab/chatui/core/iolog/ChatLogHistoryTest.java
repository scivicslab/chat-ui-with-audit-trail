package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.agent.ChatInspection;
import com.scivicslab.chatui.core.provider.PersistedLog;
import com.scivicslab.chatui.logging.ChatLog;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code get_chat_log} after a restart: the in-memory log filled in front from the persisted
 * history, and a local-LLM conversation's history read from a fixture I/O log written through
 * {@link IoLogStore} itself, the way {@code ChatSession} writes it. Never the live database.
 */
@Tag("GetChatLogReadsPersistedHistory_261119_oo01")
@DisplayName("get_chat_log fills what its in-memory log is short of from the persisted history")
class ChatLogHistoryTest {

    @TempDir
    static Path tempDir;

    private static IoLogStore store;

    @BeforeAll
    static void recordAConversation() {
        store = new IoLogStore();
        store.dbPath = tempDir.resolve("history-test").toString();
        store.httpPort = 28098;
        store.compress = false;

        long s = store.ensureSession("project1/chat-02");
        store.record(s, "agent", "settings", "provider=openai-compat");
        store.record(s, "agent", "turn1/step1/llm", "REQUEST:\n{}\nRESPONSE:\nI will read it.");
        store.record(s, "agent", "turn1/step1/tool", "TOOL:\nread_file\nOBSERVATION:\nhello harness");
        store.record(s, "agent", "turn1/conversation", "QUESTION:\nWhat is in hello.txt?\n\nANSWER:\nIt says \"hello harness\".");
        store.record(s, "agent", "turn1/answer-meta", "MODEL: m\nDURATION_MS: 5");
        store.record(s, "agent", "turn2/conversation", "QUESTION:\nAnd now?\n\nERROR:\nconnection refused");
        store.shutdown();
    }

    private static RecentEntriesAccumulator.Entry live(long time, String data) {
        return new RecentEntriesAccumulator.Entry(time, "ChatSession", "INFO", data);
    }

    private static ChatLog.History history(int n, long firstTime) {
        List<PersistedLog.Entry> all = IntStream.range(0, n)
                .mapToObj(i -> new PersistedLog.Entry(firstTime + i, "assistant", "h" + i)).toList();
        return (limit, before) -> {
            List<PersistedLog.Entry> older = all.stream().filter(e -> e.time() < before).toList();
            return new PersistedLog("history:test", older.subList(Math.max(0, older.size() - limit), older.size()), null);
        };
    }

    @Test
    void fill_emptyLiveLog_returnsTheWantedHistoryOldestFirstAndMarked() {
        ChatLog log = ChatLog.fill(List.of(), 5, history(50, 1_000));

        assertEquals(List.of("h45", "h46", "h47", "h48", "h49"),
                log.entries().stream().map(RecentEntriesAccumulator.Entry::data).toList());
        assertTrue(log.entries().stream().allMatch(e -> e.source().equals("history:test")
                && e.type().equals("assistant")), log.entries().toString());
        assertEquals(1_045, log.entries().get(0).time(), "the record's own time");
        assertNull(log.historyNote());
    }

    @Test
    void fill_liveAndHistory_putsHistoryBeforeLiveAndOnlyOlderThanLive() {
        List<long[]> calls = new ArrayList<>();
        ChatLog.History h = history(50, 1_000);
        ChatLog log = ChatLog.fill(List.of(live(1_030, "live-a"), live(1_060, "live-b")), 5, (limit, before) -> {
            calls.add(new long[] {limit, before});
            return h.read(limit, before);
        });

        assertEquals(List.of("h27", "h28", "h29", "live-a", "live-b"),
                log.entries().stream().map(RecentEntriesAccumulator.Entry::data).toList(),
                "history ends before the oldest live entry, so nothing is shown twice");
        assertEquals(1, calls.size());
        assertEquals(3, calls.get(0)[0], "asks only for the missing count");
        assertEquals(1_030, calls.get(0)[1], "before = the oldest live entry's time");
    }

    @Test
    void fill_liveLogHoldsEnough_doesNotReadTheHistory() {
        List<RecentEntriesAccumulator.Entry> live = IntStream.range(0, 30).mapToObj(i -> live(i, "l" + i)).toList();
        ChatLog log = ChatLog.fill(live, 20, (limit, before) -> fail("history must not be read"));

        assertEquals(20, log.entries().size());
        assertEquals("l10", log.entries().get(0).data());
    }

    @Test
    void fill_defaultAndMaximumCounts_cutTheHistoryTo20And200() {
        assertEquals(20, ChatLog.fill(List.of(), ChatInspection.logCount(null), history(500, 0)).entries().size());
        assertEquals(20, ChatLog.fill(List.of(), ChatInspection.logCount(0), history(500, 0)).entries().size());
        assertEquals(200, ChatLog.fill(List.of(), ChatInspection.logCount(1_000), history(500, 0)).entries().size());
        assertEquals(200, ChatInspection.MAX_LOG_ENTRIES);
    }

    @Test
    void read_localLlmConversation_givesUserAssistantAndStepEntriesNewestLast() {
        PersistedLog log = new IoLogHistory(store).read("project1/chat-02", 200, Long.MAX_VALUE);

        assertEquals("history:iolog", log.source());
        assertNull(log.note());
        List<String> lines = log.entries().stream().map(e -> e.type() + " " + e.text()).toList();
        assertEquals(List.of(
                "step turn1/step1/llm REQUEST:\n{}\nRESPONSE:\nI will read it.",
                "step turn1/step1/tool TOOL:\nread_file\nOBSERVATION:\nhello harness",
                "user What is in hello.txt?",
                "assistant It says \"hello harness\".",
                "user And now?",
                "assistant Error: connection refused"), lines,
                "settings, answer-meta and ids records are not entries");
    }

    @Test
    void read_limit_readsOnlyTheNewestRecords() {
        PersistedLog log = new IoLogHistory(store).read("project1/chat-02", 3, Long.MAX_VALUE);

        assertEquals(List.of("assistant", "user", "assistant"),
                log.entries().stream().map(PersistedLog.Entry::type).toList(), log.entries().toString());
        assertEquals("Error: connection refused", log.entries().get(2).text());
    }

    @Test
    void read_beforeEveryRecord_givesNothing() {
        PersistedLog log = new IoLogHistory(store).read("project1/chat-02", 200, 0);

        assertEquals(List.of(), log.entries());
    }

    @Test
    void getChatLog_conversationWithNeitherLiveNorPersistedHistory_saysSoWithoutAnException() {
        IoLogHistory history = new IoLogHistory(store);
        ChatLog log = ChatLog.fill(List.of(), 20, (limit, before) -> history.read("project1/chat-77", limit, before));

        assertEquals(List.of(), log.entries());
        assertEquals("the log is empty\nhistory: no I/O log session for this conversation",
                ChatInspection.formatChatLog(log, null));
    }

    @Test
    void formatLog_restartedLocalLlmConversation_showsTheHistoryThenTheLiveEntry() {
        IoLogHistory history = new IoLogHistory(store);
        ChatLog log = ChatLog.fill(List.of(live(System.currentTimeMillis() + 60_000, "turn 3 started")), 4,
                (limit, before) -> history.read("project1/chat-02", limit, before));

        String text = ChatInspection.formatChatLog(log, 4);
        System.out.println("get_chat_log(chatId=02, count=4), local LLM:\n" + text);
        String[] lines = text.split("\n");
        assertEquals(4, lines.length, text);
        assertTrue(lines[0].matches("\\d\\d:\\d\\d:\\d\\d history:iolog assistant It says \"hello harness\"\\."), lines[0]);
        assertTrue(lines[1].endsWith(" history:iolog user And now?"), lines[1]);
        assertTrue(lines[2].endsWith(" history:iolog assistant Error: connection refused"), lines[2]);
        assertTrue(lines[3].endsWith(" ChatSession INFO turn 3 started"), lines[3]);
    }
}
