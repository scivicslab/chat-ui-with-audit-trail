package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for reading a recorded conversation back out of a session's rows
 * ({@code ConversationRestoreOnRestart_260904_oo01}). No database: the rows are built here, exactly
 * as {@code ChatSession.recordConversationIo} writes them.
 *
 * <p>What this holds in place is that a restart restores what was asked and what was answered, and
 * only that — the step rows of the same session, whose REQUEST holds the constructed prompt rather
 * than the question, must not be mistaken for conversation.</p>
 */
class ConversationRestoreTest {

    private static long nextId = 1;

    /** A row in the exact shape ChatSession.recordConversationIo writes. */
    private static LogEntry conversationRow(int turn, String question, String answer) {
        String message = ChatSession.CONVERSATION_QUESTION_MARKER + "\n" + question
                + "\n\n" + ChatSession.CONVERSATION_ANSWER_MARKER + "\n" + answer;
        return row("turn" + turn + "/conversation", message);
    }

    private static LogEntry row(String label, String message) {
        return new LogEntry(nextId++, 1L, LocalDateTime.now(), "agent", label, null,
                LogLevel.INFO, message, null, null);
    }

    @Test
    void everyRecordedTurnComesBackOldestFirst() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                conversationRow(1, "私の好きな色は青です", "覚えました"),
                conversationRow(2, "何色だと言いましたか", "青です")), 50);

        assertEquals(2, turns.size());
        assertEquals("私の好きな色は青です", turns.get(0).question());
        assertEquals("覚えました", turns.get(0).answer());
        assertEquals("何色だと言いましたか", turns.get(1).question());
        assertEquals("青です", turns.get(1).answer());
    }

    /** Turn order comes from the label, not from the order the rows happen to arrive in. */
    @Test
    void turnsAreOrderedByTurnNumber() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                conversationRow(3, "third", "c"),
                conversationRow(1, "first", "a"),
                conversationRow(2, "second", "b")), 50);

        assertEquals(List.of("first", "second", "third"),
                turns.stream().map(IoLogView.Turn::question).toList());
    }

    /**
     * The step rows of the same session carry the constructed prompt — system prompt, skill
     * catalog, then the question — under a different label. Restoring from those would put that
     * whole preamble on screen as if the user had typed it.
     */
    @Test
    void stepRowsAreNotMistakenForConversation() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                row("turn1/step1/llm", "REQUEST:\n{\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"You are a helpful assistant with access to tools...\"}]}"
                        + "\n\nRESPONSE:\nsome reply"),
                row("turn1/step1/tool", "TOOL: read\nOBSERVATION:\nfile contents"),
                conversationRow(1, "本当の質問", "本当の回答")), 50);

        assertEquals(1, turns.size(), "only the conversation row counts");
        assertEquals("本当の質問", turns.get(0).question());
    }

    /** Only the most recent turns are kept, and they stay in order. */
    @Test
    void onlyTheMostRecentTurnsAreKept() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                conversationRow(1, "q1", "a1"),
                conversationRow(2, "q2", "a2"),
                conversationRow(3, "q3", "a3")), 2);

        assertEquals(List.of("q2", "q3"),
                turns.stream().map(IoLogView.Turn::question).toList());
    }

    /** A half-written entry is skipped rather than restored as a turn with an empty side. */
    @Test
    void anEntryMissingItsAnswerIsSkipped() {
        List<IoLogView.Turn> turns = IoLogView.conversationOf(List.of(
                row("turn1/conversation", "QUESTION:\nasked but never answered"),
                conversationRow(2, "q2", "a2")), 50);

        assertEquals(1, turns.size());
        assertEquals("q2", turns.get(0).question());
    }

    /** A question or an answer that itself spans blank lines survives the round trip intact. */
    @Test
    void multiLineTextSurvivesTheRoundTrip() {
        String answer = "# 見出し\n\n本文の段落。\n\n- 箇条書き\n- もう1つ";
        List<IoLogView.Turn> turns =
                IoLogView.conversationOf(List.of(conversationRow(1, "質問\n\n2行目", answer)), 50);

        assertEquals(1, turns.size());
        assertEquals("質問\n\n2行目", turns.get(0).question());
        assertEquals(answer, turns.get(0).answer());
    }

    @Test
    void nothingIsRestoredWhenTheLimitIsZeroOrThereAreNoRows() {
        assertTrue(IoLogView.conversationOf(List.of(conversationRow(1, "q", "a")), 0).isEmpty());
        assertTrue(IoLogView.conversationOf(List.of(), 50).isEmpty());
    }

    /**
     * The turn a resumed session must go on from. Every label counts, not only the conversation
     * ones, so that the numbering continues past a turn that was interrupted before it finished.
     */
    @Test
    void theTurnToContinueFromIsTheHighestAnyLabelCarries() {
        assertEquals(3, IoLogView.lastTurnNumberOf(List.of(
                conversationRow(1, "q1", "a1"),
                conversationRow(2, "q2", "a2"),
                row("turn3/step1/llm", "REQUEST:\n...\n\nRESPONSE:\ninterrupted here"))));
    }

    /** A session recorded before conversation entries existed still says where its turns stopped. */
    @Test
    void aSessionWithOnlyStepRowsStillSaysWhereItsTurnsStopped() {
        assertEquals(2, IoLogView.lastTurnNumberOf(List.of(
                row("turn1/step1/llm", "REQUEST:\n...\n\nRESPONSE:\n..."),
                row("turn1/step1/tool", "TOOL: read\nOBSERVATION:\n..."),
                row("turn2/step1/llm", "REQUEST:\n...\n\nRESPONSE:\n..."))));
    }

    @Test
    void aSessionWithNoTurnAtAllStartsFromTheBeginning() {
        assertEquals(0, IoLogView.lastTurnNumberOf(List.of()));
        assertEquals(0, IoLogView.lastTurnNumberOf(List.of(row("startup", "no turn in this label"))));
    }
}
