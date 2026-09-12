package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for {@code ConversationSettingsRecord_260913_oo01}: the last {@code settings}
 * record of a session is the conversation's whole state, and malformed or absent records leave it
 * undefined rather than wrong.
 */
class ConversationSettingsTest {

    private static long nextId = 1;

    private static LogEntry row(String label, String message) {
        return new LogEntry(nextId++, 1L, LocalDateTime.now(), "agent", label, null,
                LogLevel.INFO, message, null, null);
    }

    @Test
    void latestSettingsOf_takesTheLastRecord() {
        IoLogView.Settings s = IoLogView.latestSettingsOf(List.of(
                row("turn1/conversation", "QUESTION:\nq\n\nANSWER:\na"),
                row("settings", "{\"provider\":\"claude\",\"tools\":\"harness\",\"model\":\"sonnet\"}"),
                row("turn2/step1/llm", "REQUEST:\n..."),
                row("settings", "{\"provider\":\"claude\",\"tools\":\"full\",\"model\":\"opus\"}")));
        assertEquals("claude", s.provider());
        assertEquals("full", s.tools());
        assertEquals("opus", s.model());
    }

    @Test
    void latestSettingsOf_noneOrMalformed_isNull() {
        assertNull(IoLogView.latestSettingsOf(List.of(row("turn1/conversation", "QUESTION:\nq\n\nANSWER:\na"))));
        assertNull(IoLogView.latestSettingsOf(List.of(row("settings", "not json"))));
    }

    @Test
    void parseSettings_missingFields_areNull() {
        IoLogView.Settings s = IoLogView.parseSettings("{\"provider\":\"openai-compat\"}");
        assertEquals("openai-compat", s.provider());
        assertNull(s.tools());
        assertNull(s.model());
    }
}
