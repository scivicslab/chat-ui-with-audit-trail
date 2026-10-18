package com.scivicslab.chatui.openaicompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestMessagesJsonTest {

    @Test
    void textMessagesSurviveARoundTrip() {
        List<ChatMessage> messages = List.of(
                new ChatMessage.System("be brief"),
                new ChatMessage.User("q"),
                new ChatMessage.Assistant("a"));
        assertEquals(messages, RequestMessagesJson.parse(RequestMessagesJson.toJson(messages)));
    }

    @Test
    void aUserImageIsWrittenAsAnImagePartAndReadBack() {
        List<ChatMessage> messages = List.of(new ChatMessage.User("see", List.of("data:image/png;base64,AA")));
        String json = RequestMessagesJson.toJson(messages);
        assertTrue(json.contains("\"image_url\""));
        assertEquals(messages, RequestMessagesJson.parse(json));
    }

    @Test
    void theToolRoleIsRefusedAndTheMessageNamesTheElement() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> RequestMessagesJson.parse("[{\"role\":\"user\",\"content\":\"x\"},{\"role\":\"tool\",\"content\":\"y\"}]"));
        assertTrue(e.getMessage().contains("element 1"), e.getMessage());
    }

    @Test
    void anImageInAnAssistantMessageIsRefused() {
        var e = assertThrows(IllegalArgumentException.class, () -> RequestMessagesJson.parse(
                "[{\"role\":\"assistant\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:x\"}}]}]"));
        assertTrue(e.getMessage().contains("element 0"), e.getMessage());
    }

    @Test
    void textThatIsNotAnArrayIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RequestMessagesJson.parse("{\"role\":\"user\"}"));
        assertThrows(IllegalArgumentException.class, () -> RequestMessagesJson.parse(null));
    }

    @Test
    void anEmptyArrayIsAcceptedAsAnEmptyHistory() {
        assertEquals(List.of(), RequestMessagesJson.parse("[]"));
    }
}
