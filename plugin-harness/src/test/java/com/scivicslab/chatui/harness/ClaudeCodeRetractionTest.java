package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.scivicslab.chatui.core.rest.ChatEvent;

/**
 * What the conversation is sent when the CLI withdraws a response and asks the model to answer again: the
 * withdrawn text, then a retraction, then the retry's text. The lines are modelled on the CLI's own session
 * record (the withdrawn response was streamed as an ordinary assistant line before the retry notice).
 */
class ClaudeCodeRetractionTest {

    private static final String WITHDRAWN = """
            {"type":"assistant","message":{"id":"msg_A","role":"assistant","content":[{"type":"text","text":"<invoke name=\\"create_chat\\">\\n<parameter name=\\"reason\\">first draft</parameter>\\n</invoke>"}]},"uuid":"u-a","session_id":"s1"}""";
    private static final String NOTICE = """
            {"type":"user","isMeta":true,"turnCompanion":true,"retractedMessageUuids":["u-a0","u-a"],"message":{"role":"user","content":[{"type":"text","text":"The previous response failed to produce a valid tool call. Please retry the tool call now."}]},"session_id":"s1"}""";
    private static final String RETRY = """
            {"type":"assistant","message":{"id":"msg_B","role":"assistant","content":[{"type":"text","text":"Retrying.\\n\\n<invoke name=\\"create_chat\\">\\n<parameter name=\\"reason\\">second draft</parameter>\\n</invoke>"}]},"uuid":"u-b","session_id":"s1"}""";

    @Test
    void aRetractionLineBetweenTwoResponsesReachesTheConversationAsARetraction() throws Exception {
        Path dir = Files.createTempDirectory("provider-retraction-test");
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("default", dir, 1, "sonnet", null), "p", "c", dir,
                com.scivicslab.chatui.agent.ToolSet.FULL);
        StreamEventParser parser = new StreamEventParser();
        List<ChatEvent> sent = new ArrayList<>();
        for (String line : List.of(WITHDRAWN, NOTICE, RETRY)) {
            for (StreamEvent e : parser.parse(line)) provider.dispatch(e, sent::add, new boolean[1], new boolean[1]);
        }

        assertEquals(List.of("delta", "retraction", "delta"), sent.stream().map(ChatEvent::type).toList(), sent.toString());
        assertEquals("u-a0,u-a", sent.get(1).content());
    }
}
