package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.agent.ContextBudget;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the conversation history's size limit
 * ({@code TurnResourceLimits_260830_oo01} "会話履歴の上限が件数なのはなぜ駄目か").
 *
 * <p>The limit used to be a count of 20 messages, which said nothing about how much context the
 * messages actually used. An agent-loop turn adds two messages per step, so with a 30-step limit
 * the count alone would throw away everything before the last ten steps, however small those
 * messages were and however large the model's context window is. These tests hold the replacement
 * in place: what bounds the history is its estimated token count against the model's context
 * length.</p>
 *
 * <p>No server is contacted. The history is reached by reflection because it is the provider's own
 * state and has no accessor; every other part of the arrangement is ordinary construction.</p>
 */
class HistoryBudgetTest {

    /** 128K tokens is the assumed floor, and half of it is the history's share. */
    private static final int EXPECTED_BUDGET_TOKENS = 64_000;

    private static OpenAiCompatProvider newProvider() {
        // No reachable URL: these tests never send, they only exercise history bookkeeping.
        return new OpenAiCompatProvider(List.of("http://127.0.0.1:1"), "test-model");
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<ChatMessage> historyOf(OpenAiCompatProvider provider) {
        try {
            Field f = OpenAiCompatProvider.class.getDeclaredField("history");
            f.setAccessible(true);
            return (LinkedList<ChatMessage>) f.get(provider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fit(OpenAiCompatProvider provider) {
        try {
            var m = OpenAiCompatProvider.class.getDeclaredMethod("fitHistoryToBudget");
            m.setAccessible(true);
            m.invoke(provider);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int estimatedTokens(List<ChatMessage> history) {
        int sum = 0;
        for (ChatMessage m : history) {
            String content = switch (m) {
                case ChatMessage.User u -> u.content();
                case ChatMessage.Assistant a -> a.content();
                case ChatMessage.System s -> s.content();
                case ChatMessage.ToolResult r -> r.content();
                case ChatMessage.ToolCallRequest t -> "";
            };
            sum += ContextBudget.estimateMessageTokens(content);
        }
        return sum;
    }

    /**
     * A 30-step turn's worth of small messages. The old count cap of 20 would have kept only the
     * last twenty, discarding the first forty; they are nowhere near the context window, so all
     * sixty must survive.
     */
    @Test
    void aLongTurnOfSmallMessagesIsKeptWhole() {
        OpenAiCompatProvider provider = newProvider();
        LinkedList<ChatMessage> history = historyOf(provider);

        for (int step = 1; step <= 30; step++) {
            history.addLast(new ChatMessage.User("step " + step + " prompt"));
            history.addLast(new ChatMessage.Assistant("step " + step + " reply"));
        }
        fit(provider);

        assertEquals(60, history.size(),
                "60 small messages are far under the budget, so none may be dropped");
        assertTrue(((ChatMessage.User) history.getFirst()).content().contains("step 1"),
                "the oldest step must still be there, got: " + history.getFirst());
    }

    /** Messages large enough to exceed the budget are dropped oldest-first until they fit. */
    @Test
    void historyIsCutDownWhenItExceedsTheBudget() {
        OpenAiCompatProvider provider = newProvider();
        LinkedList<ChatMessage> history = historyOf(provider);

        // 40_000 chars ≈ 20_000 tokens each, so five of them exceed the 64_000-token budget.
        String big = "x".repeat(40_000);
        for (int i = 0; i < 5; i++) {
            history.addLast(new ChatMessage.User("observation " + i + " " + big));
        }
        fit(provider);

        assertTrue(estimatedTokens(history) <= EXPECTED_BUDGET_TOKENS,
                "history must fit the budget, was " + estimatedTokens(history) + " tokens");
        assertTrue(history.size() < 5, "something must have been dropped, kept " + history.size());
        assertTrue(((ChatMessage.User) history.getLast()).content().startsWith("observation 4"),
                "the newest message must be the one kept");
    }

    /** The message about to be sent is never dropped, however large it is on its own. */
    @Test
    void theNewestMessageSurvivesEvenWhenItAloneExceedsTheBudget() {
        OpenAiCompatProvider provider = newProvider();
        LinkedList<ChatMessage> history = historyOf(provider);

        history.addLast(new ChatMessage.User("earlier"));
        history.addLast(new ChatMessage.User("huge " + "y".repeat(400_000)));
        fit(provider);

        assertEquals(1, history.size(), "only the message about to be sent may remain");
        assertTrue(((ChatMessage.User) history.getFirst()).content().startsWith("huge "),
                "the surviving message must be the newest one");
    }
}
