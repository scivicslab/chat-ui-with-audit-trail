package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Listing a session's turns without reading all of them.
 *
 * <p>The archive holds a session of 1,417 turns. Asking for every turn's steps to draw a list of
 * questions answered with 3.3 MB; a window of twenty answers with eight kilobytes. These are the
 * pure shaping functions behind that — no database.</p>
 */
@DisplayName("IoLogView — a window of a session's turns")
class TurnWindowTest {

    /** A session of {@code turns} turns, each a model call and a tool run. */
    private static List<LogEntry> session(int turns) {
        List<LogEntry> out = new ArrayList<>();
        long id = 1;
        for (int turn = 1; turn <= turns; turn++) {
            out.add(entry(id++, turn, "llm", llmEntry("question of turn " + turn)));
            out.add(entry(id++, turn, "tool", "TOOL: write\nINPUT: {}\nOBSERVATION: done"));
        }
        return out;
    }

    private static LogEntry entry(long id, int turn, String kind, String message) {
        return new LogEntry(id, 1L, LocalDateTime.of(2026, 9, 9, 10, 0, 0).plusSeconds(id),
                "node", "turn" + turn + "/step1/" + kind, "action", LogLevel.INFO, message,
                null, null);
    }

    private static String llmEntry(String prompt) {
        return "REQUEST: {\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}"
                + "\nRESPONSE: an answer\nUSAGE: {}";
    }

    @Test
    void turnHeadsOf_returnsTheNewestTurnsFirst() {
        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(session(50), 0, 20);

        assertEquals(20, heads.size());
        assertEquals(50, heads.get(0).turn());
        assertEquals(31, heads.get(heads.size() - 1).turn());
    }

    @Test
    void turnHeadsOf_carriesWhatWasAsked_soATurnCanBeFoundByIt() {
        IoLogView.TurnHead newest = IoLogView.turnHeadsOf(session(50), 0, 20).get(0);

        assertEquals("question of turn 50", newest.question());
    }

    @Test
    void turnHeadsOf_showsTheEndOfAFoldedPrompt_whichIsWhereTheQuestionIs() {
        // This program sends the system prompt, the tool descriptions and the question as one user
        // message. Read from its start, every turn's line said "You are a helpful assistant…".
        String folded = "You are a helpful assistant with access to tools. "
                + "tool descriptions here. ".repeat(40)
                + "では新しいディレクトリを作ってください";
        List<LogEntry> raw = List.of(entry(1, 1, "llm", llmEntry(folded)));

        String question = IoLogView.turnHeadsOf(raw, 0, 1).get(0).question();

        assertTrue(question.endsWith("では新しいディレクトリを作ってください"),
                "the line must end with the question, got: " + question);
        assertTrue(question.startsWith("…"), "and say it was cut from the front");
        assertFalse(question.contains("You are a helpful"),
                "the one part every prompt shares must not be the part shown");
    }

    @Test
    void questionTail_ofAShortPrompt_isTheWholeOfIt() {
        assertEquals("what does this do?", IoLogView.questionTail("what does this do?"));
        assertEquals("", IoLogView.questionTail(null));
        assertEquals("a b", IoLogView.questionTail("a\n\n  b"));
    }

    @Test
    void turnHeadsOf_readsFurtherBackFromAGivenTurn() {
        List<IoLogView.TurnHead> older = IoLogView.turnHeadsOf(session(50), 31, 20);

        assertEquals(30, older.get(0).turn(), "the window starts below the turn asked for");
        assertEquals(11, older.get(older.size() - 1).turn());
    }

    @Test
    void turnHeadsOf_stopsAtTheOldestTurn() {
        List<IoLogView.TurnHead> all = IoLogView.turnHeadsOf(session(5), 0, 20);

        assertEquals(5, all.size());
        assertEquals(1, all.get(all.size() - 1).turn());
    }

    @Test
    void turnHeadsOf_ofNothing_isNothing() {
        assertTrue(IoLogView.turnHeadsOf(List.of(), 0, 20).isEmpty());
        assertTrue(IoLogView.turnHeadsOf(session(5), 0, 0).isEmpty());
        assertTrue(IoLogView.turnHeadsOf(session(5), 1, 20).isEmpty(),
                "nothing is numbered below turn 1");
    }

    @Test
    void turnHeadsOf_aTurnWithOnlyToolEntries_isStillListed() {
        // A turn interrupted before its model call still wrote tool entries; leaving it out of the
        // list would leave a gap nothing explains.
        List<LogEntry> raw = new ArrayList<>(session(2));
        raw.add(entry(99, 3, "tool", "TOOL: write\nINPUT: {}\nOBSERVATION: done"));

        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(raw, 0, 20);

        assertEquals(3, heads.get(0).turn());
        assertEquals("", heads.get(0).question());
    }

    @Test
    void stepTurnOf_readsTheTurnOutOfAStepLabel() {
        assertEquals(7, IoLogView.stepTurnOf("turn7/step1/llm"));
        assertEquals(1417, IoLogView.stepTurnOf("turn1417/step3/tool"));
        assertEquals(-1, IoLogView.stepTurnOf("something else"));
        assertEquals(-1, IoLogView.stepTurnOf(""));
    }

    @Test
    void turnHeadsOf_doesNotBuildTheSteps() {
        // What this test is really about is size: a TurnHead has no steps to carry, which is the
        // whole difference between eight kilobytes and three megabytes.
        IoLogView.TurnHead head = IoLogView.turnHeadsOf(session(3), 0, 1).get(0);

        assertEquals(2, IoLogView.TurnHead.class.getRecordComponents().length,
                "a listed turn is its number and its question, nothing else");
        assertEquals(3, head.turn());
    }
}
