package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Sessions tab's trace of a turn carries the ids its {@code turnN/ids} entry holds. */
class TraceTurnIdsTest {

    private static LogEntry entry(long id, String label, String message) {
        return new LogEntry(id, 1L, LocalDateTime.of(2026, 11, 8, 3, 0, 0).plusSeconds(id),
                "agent", label, "action", LogLevel.INFO, message, null, null);
    }

    private static final String LLM = "REQUEST: {\"messages\":[{\"role\":\"user\",\"content\":\"q\"}]}"
            + "\n\nRESPONSE:\nok\n\nUSAGE: promptTokens=0 completionTokens=0";

    @Test
    void aTurnWithAnIdsEntry_carriesBothIds() {
        List<IoLogView.TraceTurn> turns = IoLogView.traceOf(List.of(
                entry(1, "turn4/ids", "TURN_ID: project1/chat-28:s109:t4\nREQUEST_ID: project1/chat-01:s47:t614:r2"),
                entry(2, "turn4/step1/llm", LLM)));
        assertEquals(1, turns.size());
        assertEquals("project1/chat-28:s109:t4", turns.get(0).turnId());
        assertEquals("project1/chat-01:s47:t614:r2", turns.get(0).requestId());
    }

    @Test
    void aTurnRecordedBeforeTheEntryExisted_hasNone() {
        IoLogView.TraceTurn t = IoLogView.traceOf(List.of(entry(1, "turn2/step1/llm", LLM))).get(0);
        assertNull(t.turnId());
        assertNull(t.requestId());
    }

    @Test
    void oneTurnsIdsEntryIsCountedAsThatTurns() {
        assertEquals(7, IoLogView.anyTurnOf("turn7/ids"));
        assertEquals(-1, IoLogView.stepTurnOf("turn7/ids"));
    }
}
