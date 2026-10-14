package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The name a person gave a conversation is the message of the session's last {@code name} entry;
 * an empty one means it was cleared.
 */
@DisplayName("会話の名前の記録")
class ConversationNameRecordTest {

    private static long nextId = 1;

    private static LogEntry row(String label, String message) {
        return new LogEntry(nextId++, 1L, LocalDateTime.now(), "agent", label, null,
                LogLevel.INFO, message, null, null);
    }

    @Test
    @DisplayName("最後に付けた名前が読み出される")
    void latestNameOf_takesTheLastEntry() {
        assertEquals("二つ目", IoLogView.latestNameOf(List.of(
                row(IoLogView.NAME_LABEL, "一つ目"),
                row(IoLogView.SETTINGS_LABEL, "{\"provider\":\"openai-compat\"}"),
                row(IoLogView.NAME_LABEL, "二つ目"))));
    }

    @Test
    @DisplayName("最後に名前を消していれば名前は無い")
    void latestNameOf_clearedLast_isNull() {
        assertNull(IoLogView.latestNameOf(List.of(
                row(IoLogView.NAME_LABEL, "いちど付けた名前"),
                row(IoLogView.NAME_LABEL, ""))));
    }

    @Test
    @DisplayName("名前の記録が無ければ名前は無い")
    void latestNameOf_none_isNull() {
        assertNull(IoLogView.latestNameOf(List.of(row(IoLogView.SETTINGS_LABEL, "{}"))));
    }
}
