package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.agent.ToolSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the conversation is told when Claude Code refuses a call for the usage limit, from the three lines the CLI
 * wrote on 2026-11-23: it is told it was the usage limit (so it keeps the request and holds its queue), then the error
 * for the screen, then that the call ended. The CLI's synthetic notice is not passed on as the model's answer.
 */
class UsageLimitRefusalDispatchTest {

    static final String LIMIT = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\","
            + "\"rateLimitType\":\"seven_day\"},\"session_id\":\"s1\"}";
    static final String SYNTHETIC = "{\"type\":\"assistant\",\"message\":{\"model\":\"<synthetic>\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"You have hit your limit\"}]},\"session_id\":\"s1\","
            + "\"error\":\"rate_limit\",\"is_api_error_message\":true,\"api_error_status\":429,"
            + "\"api_error\":\"usage_limit_reached\"}";
    static final String REFUSED = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,"
            + "\"api_error\":\"usage_limit_reached\",\"result\":\"You have hit your limit\",\"session_id\":\"s1\"}";

    @Test
    void theRefusal_isTheUsageLimit_thenTheError_thenTheEndOfTheCall_andNoAnswerText(@TempDir Path dir) {
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("bypassPermissions", dir, 0, "sonnet", null, dir),
                "project1", "57", null, ToolSet.HARNESS);
        List<ChatEvent> shown = new ArrayList<>();
        StreamEventParser parser = new StreamEventParser();
        boolean[] stale = {false};
        boolean[] sawAssistant = {false};
        for (String line : List.of(LIMIT, SYNTHETIC, REFUSED)) {
            for (StreamEvent e : parser.parse(line)) provider.dispatch(e, shown::add, stale, sawAssistant);
        }
        assertEquals(List.of("usage_limit", "error", "result"), shown.stream().map(ChatEvent::type).toList(),
                shown.toString());
        assertEquals("You have hit your limit", shown.get(0).content());
        assertFalse(shown.stream().anyMatch(e -> "delta".equals(e.type())), "no answer text: " + shown);
    }
}
