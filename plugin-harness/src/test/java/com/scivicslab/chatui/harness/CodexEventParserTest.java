package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code CliHarnessProvider_260912_oo01}: Codex's JSON Lines items become the
 * same {@code tool_use}/{@code tool_result} pairs Claude Code's blocks do, keyed by item id.
 */
class CodexEventParserTest {

    private final CodexEventParser parser = new CodexEventParser();

    @Test
    void parse_threadStarted_carriesTheThreadIdAsSession() {
        List<StreamEvent> events = parser.parse("{\"type\":\"thread.started\",\"thread_id\":\"t-1\"}");
        assertEquals(1, events.size());
        assertEquals("system", events.get(0).type());
        assertEquals("t-1", events.get(0).sessionId());
    }

    @Test
    void parse_commandExecutionStartedThenCompleted_yieldsToolUseThenToolResultWithOutput() {
        List<StreamEvent> started = parser.parse("{\"type\":\"item.started\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"command\":\"echo pong\",\"status\":\"in_progress\"}}");
        assertEquals(1, started.size());
        assertEquals("tool_use", started.get(0).type());
        assertEquals("command_execution", started.get(0).toolName());
        assertEquals("i1", started.get(0).toolUseId());
        assertTrue(started.get(0).content().contains("\"command\":\"echo pong\""), started.get(0).content());

        List<StreamEvent> done = parser.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\","
                + "\"type\":\"command_execution\",\"command\":\"echo pong\",\"aggregated_output\":\"pong\\n\","
                + "\"exit_code\":0,\"status\":\"completed\"}}");
        assertEquals(1, done.size());
        assertEquals("tool_result", done.get(0).type());
        assertEquals("i1", done.get(0).toolUseId());
        assertEquals("pong\n", done.get(0).content());
        assertTrue(!done.get(0).isError());
    }

    @Test
    void parse_commandExecutionWithNonZeroExit_isAnErrorResult() {
        List<StreamEvent> done = parser.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\","
                + "\"type\":\"command_execution\",\"command\":\"false\",\"aggregated_output\":\"\","
                + "\"exit_code\":1,\"status\":\"failed\"}}");
        assertEquals(1, done.size());
        assertTrue(done.get(0).isError());
        assertTrue(done.get(0).content().contains("exit code 1"));
    }

    @Test
    void parse_agentMessageAndTurnCompleted_yieldAssistantTextAndResult() {
        List<StreamEvent> msg = parser.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"i3\","
                + "\"type\":\"agent_message\",\"text\":\"pong\"}}");
        assertEquals("assistant", msg.get(0).type());
        assertEquals("pong", msg.get(0).content());
        List<StreamEvent> end = parser.parse("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1}}");
        assertEquals("result", end.get(0).type());
        assertTrue(parser.parse("{\"type\":\"turn.started\"}").isEmpty());
        assertTrue(parser.parse("2026-09-12T01:01:38Z ERROR something").isEmpty(), "log noise is not an event");
    }

    /** Codex's web_search item carries two "id" keys; the line must still parse and pair up. */
    @Test
    void parse_webSearchWithDuplicateIdKey_yieldsToolUseAndResult() {
        List<StreamEvent> started = parser.parse("{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\","
                + "\"type\":\"web_search\",\"id\":\"ws_abc\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}");
        assertEquals(1, started.size(), started.toString());
        assertEquals("tool_use", started.get(0).type());
        assertEquals("web_search", started.get(0).toolName());
        List<StreamEvent> done = parser.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\","
                + "\"type\":\"web_search\",\"id\":\"ws_abc\",\"query\":\"東京 満潮\",\"action\":{\"type\":\"search\"}}}");
        assertEquals(1, done.size());
        assertEquals("tool_result", done.get(0).type());
        assertEquals(started.get(0).toolUseId(), done.get(0).toolUseId(), "both events of one search pair up");
        assertTrue(done.get(0).content().contains("東京 満潮"));
    }
}
