package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@link ChatUiActorSystem#recordExternalCall} — a call
 * {@code ArtexProxyResource} relays lands both permanently and in the live view, under a
 * {@code turnN/step1/llm} label the Sessions tab's trace viewer already knows how to read,
 * without being mistaken for a {@link ChatUiActorSystem}-run conversation
 * ({@code ArtexProxyLogging_261017_oo01}).
 */
@DisplayName("ChatUiActorSystem — recording an external caller's relayed call")
class ArtexProxyLoggingTest {

    @TempDir
    Path tempDir;

    private ChatUiActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ChatUiActorSystem();
        system.ioLogStore = new IoLogStore();
        // Public API only: dbPath/httpPort/compress are IoLogStore's own package-private fields,
        // not reachable from this package (core.actor) — useDatabaseAt is the one door in, and a
        // fresh @TempDir per test keeps this from colliding with any other test's own store even
        // at the default httpPort suffix.
        system.ioLogStore.useDatabaseAt(tempDir.resolve("artex-proxy-test").toString());
        system.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        system.ioLogStore.store().close();
    }

    /**
     * Reads a session's entries once everything written before has been committed. The log's writer
     * runs on a thread of its own, so a write returns before it reaches the database;
     * {@code flushWrites()} is the store's own way to say "wait for what I wrote", and with it a
     * read has nothing to poll for.
     */
    private List<LogEntry> entriesOf(long sessionId) {
        system.ioLogStore.store().flushWrites();
        return system.ioLogStore.store().getLogsByLevel(sessionId, LogLevel.DEBUG);
    }

    @Test
    void firstCall_getsTurn1Step1LlmLabel() {
        system.recordExternalCall("artex",
                "REQUEST:\n{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}\n\nRESPONSE:\nhi");

        long sessionId = system.ioLogStore.jobSessionIdOf(
                ChatUiActorSystem.EXTERNAL_PROXY_SESSION_PREFIX + "artex");
        List<LogEntry> entries = entriesOf(sessionId);
        assertEquals(1, entries.size(), entries.toString());
        assertEquals("turn1/step1/llm", entries.get(0).getLabel());
    }

    @Test
    void secondCall_fromTheSameCaller_getsTurn2() {
        system.recordExternalCall("artex", "REQUEST:\nfirst\n\nRESPONSE:\nok");
        system.recordExternalCall("artex", "REQUEST:\nsecond\n\nRESPONSE:\nok");

        long sessionId = system.ioLogStore.jobSessionIdOf(
                ChatUiActorSystem.EXTERNAL_PROXY_SESSION_PREFIX + "artex");
        List<LogEntry> entries = entriesOf(sessionId);
        assertEquals(2, entries.size(), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> "turn1/step1/llm".equals(e.getLabel())));
        assertTrue(entries.stream().anyMatch(e -> "turn2/step1/llm".equals(e.getLabel())));
    }

    @Test
    void twoDifferentCallers_eachCountTheirOwnTurnsFromOne() {
        system.recordExternalCall("artex", "REQUEST:\na\n\nRESPONSE:\nok");
        system.recordExternalCall("other-caller", "REQUEST:\nb\n\nRESPONSE:\nok");
        system.recordExternalCall("artex", "REQUEST:\nc\n\nRESPONSE:\nok");

        long artexSession = system.ioLogStore.jobSessionIdOf(
                ChatUiActorSystem.EXTERNAL_PROXY_SESSION_PREFIX + "artex");
        long otherSession = system.ioLogStore.jobSessionIdOf(
                ChatUiActorSystem.EXTERNAL_PROXY_SESSION_PREFIX + "other-caller");

        List<LogEntry> artexEntries = entriesOf(artexSession);
        List<LogEntry> otherEntries = entriesOf(otherSession);
        assertTrue(artexEntries.stream().anyMatch(e -> "turn1/step1/llm".equals(e.getLabel())));
        assertTrue(artexEntries.stream().anyMatch(e -> "turn2/step1/llm".equals(e.getLabel())));
        assertTrue(otherEntries.stream().anyMatch(e -> "turn1/step1/llm".equals(e.getLabel())),
                "a second caller's own first call is its own turn 1, not a continuation of artex's: "
                        + otherEntries);
    }

    /**
     * The whole reason for {@link IoLogStore#ensureJobSession} over {@code ensureSession}: a
     * restart must not try to rebuild this caller into a {@link ConversationSquad}, which has no
     * {@link ChatSessionIIAR} behind it here and so cannot actually run.
     */
    @Test
    void anExternalCallersRecordIsNotMistakenForAConversation() {
        system.recordExternalCall("artex", "REQUEST:\na\n\nRESPONSE:\nok");

        assertFalse(system.ioLogStore.resumableConversationSquads().contains(
                ChatUiActorSystem.EXTERNAL_PROXY_SESSION_PREFIX + "artex"));
        SessionSummary session = system.ioLogStore.store().listSessions(10).stream()
                .filter(s -> s.getWorkflowName() != null && s.getWorkflowName().contains("artex"))
                .findFirst().orElseThrow();
        assertTrue(session.getWorkflowName().startsWith("chat-ui-job-"), session.getWorkflowName());
    }

    @Test
    void theCallAlsoReachesTheLiveView() {
        system.recordExternalCall("artex", "REQUEST:\na\n\nRESPONSE:\nok");

        List<RecentEntriesAccumulator.Entry> live = system.getExternalProxyLogEntries();
        assertEquals(1, live.size(), live.toString());
        assertEquals("artex", live.get(0).source());
    }
}
