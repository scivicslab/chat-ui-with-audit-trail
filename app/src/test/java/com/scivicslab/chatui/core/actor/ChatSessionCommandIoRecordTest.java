package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * What the I/O log keeps of a command that the backing process carried out, such as {@code /compact}:
 * the line it was given and the text it answered, in the {@code REQUEST:} / {@code RESPONSE:} shape
 * the Sessions tab reads.
 */
@DisplayName("CLI のコマンドの I/O ログの記録")
class ChatSessionCommandIoRecordTest {

    @Test
    @DisplayName("入力した行が REQUEST に、答えの本文が RESPONSE に入る")
    void record_holdsTheLineAndTheAnswer() {
        String record = ChatSession.commandIoRecord("/compact", List.of(
                ChatEvent.info("Compacted"),
                ChatEvent.status("sonnet", "s1", false)));

        assertTrue(record.startsWith("REQUEST:\n"), record);
        assertTrue(record.contains("\"content\":\"/compact\""), record);
        assertTrue(record.contains("\n\nRESPONSE:\nCompacted"), record);
        assertFalse(record.contains("ERROR:"), "no failure, no ERROR section: " + record);
    }

    @Test
    @DisplayName("複数の info は改行でつながり、エラーは ERROR に入る")
    void record_joinsInfoAndKeepsTheErrorApart() {
        String record = ChatSession.commandIoRecord("/context", List.of(
                ChatEvent.info("first"), ChatEvent.info("second"), ChatEvent.error("boom")));

        assertTrue(record.contains("RESPONSE:\nfirst\nsecond"), record);
        assertTrue(record.contains("\n\nERROR:\nboom"), record);
        assertEquals(1, record.split("RESPONSE:", -1).length - 1);
    }
}
