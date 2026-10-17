package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

/**
 * The pane draws a restored message from the conversation history, so the time, the model and the
 * duration printed under a message are what the history entry carries.
 */
class HistoryEntryTimeTest {

    private static final class FixedModelProvider implements LlmProvider {
        @Override public String id() { return "fixed"; }
        @Override public String displayName() { return "Fixed"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String model) { }
        @Override public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                                         ProviderContext ctx) { }
        @Override public void cancel() { }
    }

    @Test
    void aRecordedLineCarriesTheTimeItWasRecorded() {
        ChatSession session = new ChatSession(new FixedModelProvider(), Optional.empty());
        long before = System.currentTimeMillis();

        session.recordHistory("user", "hello");

        ChatSession.HistoryEntry entry = session.historySnapshot().get(0);
        assertNotNull(entry.at());
        assertTrue(entry.at() >= before && entry.at() <= System.currentTimeMillis());
        assertNull(entry.model());
        assertNull(entry.durationMs());
    }

    @Test
    void anAnswerCarriesTheModelAndTheDuration() {
        ChatSession session = new ChatSession(new FixedModelProvider(), Optional.empty());

        session.recordHistory("assistant", "ok", null, "gemma", 1234L);

        ChatSession.HistoryEntry entry = session.historySnapshot().get(0);
        assertEquals("gemma", entry.model());
        assertEquals(1234L, entry.durationMs());
        assertNotNull(entry.at());
    }

    @Test
    void aLineRestoredFromTheLogHasNoTime() {
        ChatSession session = new ChatSession(new FixedModelProvider(), Optional.empty());

        session.restoreHistory("assistant", "an answer from before the restart");

        ChatSession.HistoryEntry entry = session.historySnapshot().get(0);
        assertEquals("an answer from before the restart", entry.content());
        assertNull(entry.at(), "the time of the restart is not the time of the answer");
        assertNull(entry.model());
        assertNull(entry.durationMs());
    }

    @Test
    void aLineBroughtBackWithoutATimeHasNone() {
        ChatSession.HistoryEntry entry = new ChatSession.HistoryEntry("user", "hello");

        assertNull(entry.at(), "the I/O log does not keep the time, and the pane then shows none");
    }
}
