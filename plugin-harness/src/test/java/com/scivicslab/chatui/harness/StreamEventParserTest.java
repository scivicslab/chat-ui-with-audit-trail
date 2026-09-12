package com.scivicslab.chatui.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code CliHarnessProvider_260912_oo01}: the whole of a tool_use block's input
 * and of a tool_result block's content survive parsing, and a line holding text and a tool call
 * yields both.
 */
class StreamEventParserTest {

    private final StreamEventParser parser = new StreamEventParser();

    @Test
    void parse_assistantLineWithTextAndToolUse_yieldsTextThenToolUseWithWholeInput() {
        String line = """
                {"type":"assistant","message":{"role":"assistant","content":[
                  {"type":"text","text":"Let me look."},
                  {"type":"tool_use","id":"toolu_01","name":"Read","input":{"file_path":"/tmp/a.txt","limit":5}}
                ]},"session_id":"s1"}""".replace("\n", "");
        List<StreamEvent> events = parser.parse(line);
        assertEquals(2, events.size(), events.toString());
        assertEquals("assistant", events.get(0).type());
        assertEquals("Let me look.", events.get(0).content());
        assertEquals("tool_use", events.get(1).type());
        assertEquals("Read", events.get(1).toolName());
        assertEquals("toolu_01", events.get(1).toolUseId());
        assertTrue(events.get(1).content().contains("\"file_path\":\"/tmp/a.txt\""), events.get(1).content());
        assertTrue(events.get(1).content().contains("\"limit\":5"), events.get(1).content());
    }

    @Test
    void parse_userLineWithStringToolResult_yieldsToolResultPairedById() {
        String line = """
                {"type":"user","message":{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"toolu_01","content":"hello from a.txt"}
                ]}}""".replace("\n", "");
        List<StreamEvent> events = parser.parse(line);
        assertEquals(1, events.size());
        assertEquals("tool_result", events.get(0).type());
        assertEquals("toolu_01", events.get(0).toolUseId());
        assertEquals("hello from a.txt", events.get(0).content());
        assertFalse(events.get(0).isError());
    }

    @Test
    void parse_userLineWithArrayToolResultAndError_joinsTextBlocksAndKeepsErrorFlag() {
        String line = """
                {"type":"user","message":{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"toolu_02","is_error":true,
                   "content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]}
                ]}}""".replace("\n", "");
        List<StreamEvent> events = parser.parse(line);
        assertEquals(1, events.size());
        assertEquals("line one\nline two", events.get(0).content());
        assertTrue(events.get(0).isError());
    }

    @Test
    void parse_resultLine_carriesSessionIdAndFinalText() {
        String line = "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s1\","
                + "\"result\":\"done\",\"total_cost_usd\":0.01,\"duration_ms\":1200}";
        List<StreamEvent> events = parser.parse(line);
        assertEquals(1, events.size());
        assertEquals("result", events.get(0).type());
        assertEquals("s1", events.get(0).sessionId());
        assertEquals("done", events.get(0).content());
    }

    @Test
    void parse_blankOrInvalidLine_yieldsNothingOrError() {
        assertTrue(parser.parse("   ").isEmpty());
        assertNull(parser.parse("not json").get(0).sessionId());
        assertEquals("error", parser.parse("not json").get(0).type());
    }
}
