package com.scivicslab.chatui.core.iolog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.TokenUsage;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

/** The Tokens tab's figures are read from the {@code USAGE:} lines of a session's I/O log rows. */
class TokensViewTest {

    private static LogEntry entry(long id, String label, String message) {
        return new LogEntry(id, 1L, LocalDateTime.of(2026, 11, 24, 3, 0, 0).plusSeconds(id),
                "agent", label, "action", LogLevel.INFO, message, null, null);
    }

    private static String row(String usageLine) {
        return "REQUEST:\n{\"messages\":[]}\n\nRESPONSE:\nok\n\n" + usageLine;
    }

    @Test
    void aRowWrittenFromTokenUsageGivesItsFourCountsAndTheirSum() {
        IoLogView.Tokens t = IoLogView.tokensOf(List.of(
                entry(1, "turn1/step1/llm", row(TokenUsage.logLine(new TokenUsage(2, 30066, 11853, 365))))));
        assertEquals(List.of(new IoLogView.TokenRequest(1, 1, 2, 30066, 11853, 365, 41921)), t.requests());
    }

    @Test
    void rowsWithoutRealUsageAreLeftOut() {
        IoLogView.Tokens t = IoLogView.tokensOf(List.of(
                entry(1, "turn1/step1/llm", row("USAGE: promptTokens=0 completionTokens=0")),
                entry(2, "turn2/step1/llm", "REQUEST:\n{}\n\nRESPONSE:\nold row with no USAGE line"),
                entry(3, "turn3/step1/tool", "TOOL: x\nINPUT: {}\nOBSERVATION: y")));
        assertTrue(t.requests().isEmpty());
        assertTrue(t.turns().isEmpty());
    }

    @Test
    void anOpenAiCompatRowWithNoCacheKeysCountsItsWholePromptAsInput() {
        IoLogView.TokenRequest r = IoLogView.tokensOf(List.of(
                entry(1, "turn1/step1/llm", row("USAGE: promptTokens=900 completionTokens=40")))).requests().get(0);
        assertEquals(new IoLogView.TokenRequest(1, 1, 900, 0, 0, 40, 900), r);
    }

    @Test
    void aTurnsFirstRequestAndItsGrowthOverThePreviousTurnAreReported() {
        IoLogView.Tokens t = IoLogView.tokensOf(List.of(
                entry(1, "turn1/step1/llm", row(TokenUsage.logLine(new TokenUsage(2, 30000, 0, 100)))),
                entry(2, "turn1/step2/llm", row(TokenUsage.logLine(new TokenUsage(2, 500, 30000, 80)))),
                entry(3, "turn2/step1/llm", row(TokenUsage.logLine(new TokenUsage(2, 700, 30500, 60)))),
                entry(4, "turn3/step1/llm", row(TokenUsage.logLine(new TokenUsage(2, 100, 31200, 60))))));
        assertEquals(4, t.requests().size());
        assertEquals(List.of(new IoLogView.TokenTurn(1, 30002, 0),
                new IoLogView.TokenTurn(2, 31202, 1200),
                new IoLogView.TokenTurn(3, 31302, 100)), t.turns());
    }

    @Test
    void theLineOldReadersParseStillGivesPromptAndCompletionTokens() {
        String line = TokenUsage.logLine(new TokenUsage(2, 30066, 11853, 365));
        assertEquals(41921, IoLogView.parseTokenCount(line, "promptTokens="));
        assertEquals(365, IoLogView.parseTokenCount(line, "completionTokens="));
        assertEquals("USAGE: promptTokens=0 completionTokens=0", TokenUsage.logLine(null));
    }
}
