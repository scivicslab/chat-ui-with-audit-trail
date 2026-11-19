package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.PersistedLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code get_chat_log} after a restart, for a Claude Code conversation: its history read from the
 * transcript Claude Code keeps for the session named in chat-ui's session file. Every file here is
 * a fixture in a temporary directory; the live {@code ~/.claude} is never read.
 */
@Tag("GetChatLogReadsPersistedHistory_261119_oo01")
@DisplayName("A Claude Code conversation's history comes from its session transcript")
class ClaudeTranscriptLogTest {

    @TempDir
    Path dir;

    private static final String SESSION = "0b6f3c1e-1111-4222-8333-444455556666";

    private static final List<String> TRANSCRIPT = List.of(
            "{\"type\":\"summary\",\"summary\":\"not a message\"}",
            "{\"type\":\"user\",\"isMeta\":true,\"timestamp\":\"2026-11-19T00:10:00.000Z\",\"message\":{\"role\":\"user\",\"content\":\"<command-name>/model</command-name>\"}}",
            "{\"type\":\"user\",\"timestamp\":\"2026-11-19T00:10:01.000Z\",\"message\":{\"role\":\"user\",\"content\":\"What is in hello.txt?\"}}",
            "{\"type\":\"assistant\",\"timestamp\":\"2026-11-19T00:10:02.000Z\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"hidden\"},{\"type\":\"text\",\"text\":\"I will read it.\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{\"file_path\":\"hello.txt\"}}]}}",
            "{\"type\":\"user\",\"timestamp\":\"2026-11-19T00:10:03.000Z\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\"hello harness\"}]}]}}",
            "not json at all",
            "{\"type\":\"assistant\",\"timestamp\":\"2026-11-19T00:10:04.000Z\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"It says \\\"hello harness\\\".\"}]}}");

    private Path sessionFile() {
        return dir.resolve("harness-sessions").resolve("1-p-c-claude");
    }

    private Path projects() {
        return dir.resolve("projects");
    }

    private void writeSessionFile(String sessionId) throws IOException {
        Files.createDirectories(sessionFile().getParent());
        Files.writeString(sessionFile(), "sessionId=" + sessionId + "\nmodel=opus\n");
    }

    private Path writeTranscript(List<String> lines) throws IOException {
        Path t = projects().resolve("-home-someone-works").resolve(SESSION + ".jsonl");
        Files.createDirectories(t.getParent());
        Files.write(t, lines, StandardCharsets.UTF_8);
        return t;
    }

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private PersistedLog read(int limit, long before) {
        return new ClaudeTranscriptLog(sessionFile(), projects(), ClaudeTranscriptLog.DEFAULT_TAIL_BYTES).read(limit, before);
    }

    @Test
    void read_sessionAndTranscript_givesUserAssistantAndToolEntriesOldestFirst() throws IOException {
        writeSessionFile(SESSION);
        writeTranscript(TRANSCRIPT);

        PersistedLog log = read(200, Long.MAX_VALUE);

        assertEquals("history:transcript", log.source());
        assertNull(log.note());
        assertEquals(List.of(
                "user What is in hello.txt?",
                "assistant I will read it.",
                "tool_use Read {\"file_path\":\"hello.txt\"}",
                "tool_result hello harness",
                "assistant It says \"hello harness\"."),
                log.entries().stream().map(e -> e.type() + " " + e.text()).toList(),
                "summary, isMeta, thinking and unreadable lines are not entries");
        assertEquals(at("2026-11-19T00:10:01.000Z"), log.entries().get(0).time(), "the line's own timestamp");
        // The columns get_chat_log prints (ChatInspection.formatLog lives in the app, not here).
        System.out.println("get_chat_log entries, Claude Code (UTC):\n" + String.join("\n", log.entries().stream()
                .map(e -> Instant.ofEpochMilli(e.time()).toString().substring(11, 19) + " " + log.source() + " "
                        + e.type() + " " + e.text()).toList()));
    }

    @Test
    void read_limitAndBefore_giveOnlyTheNewestOlderEntries() throws IOException {
        writeSessionFile(SESSION);
        writeTranscript(TRANSCRIPT);

        PersistedLog log = read(2, at("2026-11-19T00:10:04.000Z"));

        assertEquals(List.of("tool_use", "tool_result"), log.entries().stream().map(PersistedLog.Entry::type).toList());
    }

    @Test
    void read_transcriptLargerThanTheTail_readsOnlyItsEnd() throws IOException {
        writeSessionFile(SESSION);
        Path t = projects().resolve("-home-someone-works").resolve(SESSION + ".jsonl");
        Files.createDirectories(t.getParent());
        String filler = "x".repeat(900);
        int lines = 6_000;   // about 5.6 MB, well over the 2 MiB tail
        try (Writer w = Files.newBufferedWriter(t, StandardCharsets.UTF_8)) {
            w.write("{\"type\":\"user\",\"timestamp\":\"2026-11-18T00:00:00.000Z\",\"message\":{\"role\":\"user\",\"content\":\"FIRST LINE\"}}\n");
            for (int i = 0; i < lines; i++) {
                w.write("{\"type\":\"assistant\",\"timestamp\":\"2026-11-19T00:00:00.000Z\",\"message\":{\"role\":\"assistant\",\"content\":\"line "
                        + i + " " + filler + "\"}}\n");
            }
        }
        assertTrue(Files.size(t) > 2 * ClaudeTranscriptLog.DEFAULT_TAIL_BYTES, "the fixture is over twice the tail");

        PersistedLog log = read(Integer.MAX_VALUE, Long.MAX_VALUE);

        int n = log.entries().size();
        assertTrue(n > 1_000 && n < 2_400, "only the lines in the last 2 MiB, got " + n);
        assertTrue(log.entries().get(n - 1).text().startsWith("line " + (lines - 1) + " "), "the newest line is there");
        assertTrue(log.entries().get(0).text().startsWith("line "), "the cut first line is dropped, not parsed half: "
                + log.entries().get(0).text().substring(0, 20));
        assertFalse(log.entries().stream().anyMatch(e -> e.text().equals("FIRST LINE")), "the start of the file is not read");
    }

    @Test
    void read_noSessionFile_saysSoWithoutAnException() {
        PersistedLog log = read(20, Long.MAX_VALUE);

        assertEquals(List.of(), log.entries());
        assertTrue(log.note().startsWith("no Claude Code session file for this conversation ("), log.note());
    }

    @Test
    void read_sessionFileButNoTranscript_saysSoWithoutAnException() throws IOException {
        writeSessionFile(SESSION);
        Files.createDirectories(projects());

        PersistedLog log = read(20, Long.MAX_VALUE);

        assertEquals(List.of(), log.entries());
        assertEquals("Claude Code transcript " + SESSION + ".jsonl not found under " + projects(), log.note());
    }

    @Test
    void read_sessionIdThatIsNotAPlainId_isNotLookedUp() throws IOException {
        writeSessionFile("../../etc/passwd");

        PersistedLog log = read(20, Long.MAX_VALUE);

        assertEquals(List.of(), log.entries());
        assertTrue(log.note().contains("not a session id"), log.note());
    }

    @Test
    void persistedLog_claudeCodeProvider_readsTheTranscriptOfItsOwnSessionFile() throws IOException {
        writeSessionFile(SESSION);
        writeTranscript(TRANSCRIPT);
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("default", dir.resolve("harness-sessions"), 1, "sonnet", null, projects()),
                "p", "c", dir, ToolSet.HARNESS);

        PersistedLog log = provider.persistedLog(1, Long.MAX_VALUE);

        assertEquals("history:transcript", log.source());
        assertEquals(List.of("It says \"hello harness\"."), log.entries().stream().map(PersistedLog.Entry::text).toList());
    }
}
