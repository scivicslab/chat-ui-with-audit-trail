package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

/**
 * A conversation keeps two histories, and emptying one is not emptying the conversation
 * ({@code ForgetTheConversationOnBothSides_260917_oo01}). A judging conversation that had been
 * cleared between criteria kept answering with the verdict it had given for an earlier rule,
 * because the list the request is built from had never been touched.
 */
class ForgetBothHistoriesTest {

    /** Counts what {@link ChatSession#clearHistory} asks of the provider. */
    private static final class CountingProvider implements LlmProvider {
        int cleared;

        @Override public String id() { return "counting"; }
        @Override public String displayName() { return "Counting"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String model) { }
        @Override public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                                         ProviderContext ctx) { }
        @Override public void cancel() { }
        @Override public void clearHistory() { cleared++; }
    }

    @Test
    void clearHistory_emptiesTheRecordAndTheContextTheNextRequestIsBuiltFrom() {
        CountingProvider provider = new CountingProvider();
        ChatSession session = new ChatSession(provider, Optional.empty());
        session.recordHistory("user", "観点3の判定をしてください");
        session.recordHistory("assistant", "REVISE: 実機確認の節を雑記へ");
        assertEquals(2, session.historySnapshot().size());

        session.clearHistory();

        assertTrue(session.historySnapshot().isEmpty(), "the record the screen shows");
        assertEquals(1, provider.cleared, "the context the next request is built from");
    }

    @Test
    void slashClear_doesTheSameThing() {
        CountingProvider provider = new CountingProvider();
        ChatSession session = new ChatSession(provider, Optional.empty());
        session.recordHistory("user", "何か");

        session.handleCommand("/clear");

        assertTrue(session.historySnapshot().isEmpty());
        assertEquals(1, provider.cleared, "the screen said the conversation was cleared; the model must agree");
    }
}
