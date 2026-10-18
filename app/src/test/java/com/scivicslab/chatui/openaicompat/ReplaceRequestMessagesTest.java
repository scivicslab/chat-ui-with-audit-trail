package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading and replacing the array the next request is built from ({@code RequestMessagesControl_261018_oo01}). */
class ReplaceRequestMessagesTest {

    private static OpenAiCompatProvider newProvider() {
        return new OpenAiCompatProvider(List.of("http://127.0.0.1:1"), "test-model");
    }

    @Test
    void anEmptyProviderReadsAsAnEmptyArray() {
        assertEquals("[]", newProvider().requestMessagesJson());
    }

    @Test
    void aReplacedArrayIsReadBackAsGiven() {
        OpenAiCompatProvider p = newProvider();
        String given = "[{\"role\":\"system\",\"content\":\"rules\"},{\"role\":\"user\",\"content\":\"q\"},"
                + "{\"role\":\"assistant\",\"content\":\"a\"}]";
        assertNull(p.replaceRequestMessages(given));
        assertEquals(given, p.requestMessagesJson());
    }

    @Test
    void aRefusedArrayLeavesTheHistoryAsItWas() {
        OpenAiCompatProvider p = newProvider();
        String first = "[{\"role\":\"user\",\"content\":\"keep me\"}]";
        assertNull(p.replaceRequestMessages(first));

        String refused = p.replaceRequestMessages("[{\"role\":\"tool\",\"content\":\"x\"}]");

        assertNotNull(refused);
        assertEquals(first, p.requestMessagesJson());
    }

    @Test
    void anArrayOverTheBudgetIsRefusedNotTrimmed() {
        OpenAiCompatProvider p = newProvider();
        String big = "x".repeat(2_000_000);
        String refused = p.replaceRequestMessages("[{\"role\":\"user\",\"content\":\"" + big + "\"}]");

        assertNotNull(refused);
        assertTrue(refused.startsWith("too long"), refused);
        assertEquals("[]", p.requestMessagesJson());
    }

    @Test
    void replacingWithAnEmptyArrayEmptiesTheHistory() {
        OpenAiCompatProvider p = newProvider();
        p.replaceRequestMessages("[{\"role\":\"user\",\"content\":\"q\"}]");
        assertNull(p.replaceRequestMessages("[]"));
        assertEquals("[]", p.requestMessagesJson());
    }

    @Test
    void aTurnCollapsedAfterTheReplaceKeepsTheReplacedMessages() {
        OpenAiCompatProvider p = newProvider();
        p.replaceRequestMessages("[{\"role\":\"system\",\"content\":\"rules\"}]");
        p.collapseTurn("q", "a");

        List<ChatMessage> messages = RequestMessagesJson.parse(p.requestMessagesJson());
        assertEquals(List.of(new ChatMessage.System("rules"), new ChatMessage.User("q"),
                new ChatMessage.Assistant("a")), messages);
    }
}
