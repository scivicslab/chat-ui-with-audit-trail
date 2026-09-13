package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A supervising turn — one that asked a worker and judged its reply — as the Sessions tab reads it
 * ({@code SupervisorTurnInTheRecord_260913_oo01}).
 *
 * <p>Exercises the load-bearing path: such a turn used to write only its {@code turnN/conversation}
 * row, which no list and no trace reads. With the request written as a tool step and the judgement
 * as an LLM step, the turn appears in the window and its trace holds both, including the criteria
 * the verdict was made against.</p>
 */
@Tag("SupervisorTurnInTheRecord_260913_oo01")
@DisplayName("IoLogView — a supervising turn's request and judgement")
class SupervisorTurnTest {

    private static final String CRITERIA_PROMPT =
            "Judge the text below against these criteria:\nThe text must be written in full sentences."
            + "\n\nIf it meets them as-is, reply with exactly:\nACCEPT\n\nText:\nPOJO-actor is …";

    /** One supervising turn: ask the worker, judge, ask again with the feedback, judge again. */
    private static List<LogEntry> supervisingTurn() {
        List<LogEntry> out = new ArrayList<>();
        long id = 1;
        out.add(entry(id++, 7, 1, "tool", "TOOL: ask_worker\nINPUT:\nproject1/chat-02\n"
                + "POJO-actor とは何かを、3 文で説明してください。\nOBSERVATION:\nPOJO-actor is …"));
        out.add(entry(id++, 7, 2, "llm", llmEntry(CRITERIA_PROMPT, "REVISE: 出典が無い")));
        out.add(entry(id++, 7, 3, "tool", "TOOL: ask_worker\nINPUT:\nproject1/chat-02\n"
                + "Please revise your previous answer to address this feedback:\nREVISE: 出典が無い"
                + "\nOBSERVATION:\nPOJO-actor is … (revised)"));
        out.add(entry(id++, 7, 4, "llm", llmEntry(CRITERIA_PROMPT, "ACCEPT")));
        return out;
    }

    private static LogEntry entry(long id, int turn, int step, String kind, String message) {
        return new LogEntry(id, 1L, LocalDateTime.of(2026, 9, 13, 13, 0, 0).plusSeconds(id),
                "agent", "turn" + turn + "/step" + step + "/" + kind, "action", LogLevel.INFO,
                message, null, null);
    }

    private static String llmEntry(String prompt, String response) {
        return "REQUEST: {\"messages\":[{\"role\":\"user\",\"content\":"
                + org.json.JSONObject.quote(prompt) + "}]}"
                + "\n\nRESPONSE:\n" + response + "\n\nUSAGE: promptTokens=0 completionTokens=0";
    }

    @Test
    void theTurnAppearsInTheWindow() {
        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(supervisingTurn(), 0, 20);

        assertEquals(1, heads.size());
        assertEquals(7, heads.get(0).turn(), "the supervising turn is listed like any other");
    }

    @Test
    void itsTraceHoldsEveryRequestAndJudgement() {
        List<IoLogView.TraceTurn> turns = IoLogView.traceOf(supervisingTurn());

        assertEquals(1, turns.size());
        List<IoLogView.TraceStep> steps = turns.get(0).steps();
        assertEquals(4, steps.size(), "two requests to the worker and two judgements");

        assertEquals("tool", steps.get(0).kind());
        assertEquals("ask_worker", steps.get(0).toolName());
        assertTrue(steps.get(0).observation().contains("POJO-actor is"), steps.get(0).observation());

        assertEquals("llm", steps.get(1).kind());
        assertTrue(steps.get(1).thought().startsWith("REVISE:"), steps.get(1).thought());

        assertEquals("ACCEPT", steps.get(3).thought(), "the verdict that ended the turn");
    }

    @Test
    void theRowSaysWhatThePersonAsked_notWhatTheJudgeWasAsked() {
        List<LogEntry> withConversation = new ArrayList<>(supervisingTurn());
        withConversation.add(new LogEntry(9L, 1L, LocalDateTime.of(2026, 9, 13, 13, 0, 10),
                "agent", "turn7/conversation", "action", LogLevel.INFO,
                "QUESTION:\nアクターモデルとは何かを 2 文で説明してください。\n\nANSWER:\nPOJO-actor is … (revised)",
                null, null));

        List<IoLogView.TurnHead> heads = IoLogView.turnHeadsOf(withConversation, 0, 20);

        assertEquals("アクターモデルとは何かを 2 文で説明してください。", heads.get(0).question(),
                "the conversation row wins over the judging prompt");
    }

    @Test
    void theJudgementKeepsTheCriteriaItWasMadeAgainst() {
        IoLogView.TraceTurn turn = IoLogView.traceOf(supervisingTurn()).get(0);

        assertTrue(turn.userPrompt().contains("The text must be written in full sentences."),
                "what the verdict was made against is in the record: " + turn.userPrompt());
    }
}
