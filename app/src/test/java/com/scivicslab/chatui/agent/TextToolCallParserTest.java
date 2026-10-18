package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two spellings of a text tool call the parser accepts.
 *
 * <p>The instructed spelling is {@code <invoke name="…">}. Some models (measured: Gemma 4 behind the
 * GPU broker, in {@code project1/05}) write the Qwen-style
 * {@code <tool_call><function=…><parameter=…>} instead. Before this was accepted, that text matched
 * no tool call, so the agent loop took it for the final answer and handed it, unexecuted, to the
 * caller of {@code ask_chat}.</p>
 */
class TextToolCallParserTest {

    @Test
    void invokeSpellingIsParsed() {
        String text = "<invoke name=\"read\">\n<parameter name=\"path\">a.txt</parameter>\n</invoke>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("read", calls.get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", calls.get(0).argumentsJson());
    }

    @Test
    void functionSpellingIsParsedWithParametersOnTheirOwnLines() {
        String text = "<tool_call>\n<function=read>\n<parameter=path>\n/home/x/outline.tsv\n</parameter>\n"
                + "</function>\n</tool_call>";
        assertTrue(TextToolCallParser.looksLikeTextToolCall(text));
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("read", calls.get(0).name());
        assertEquals("{\"path\":\"/home/x/outline.tsv\"}", calls.get(0).argumentsJson());
    }

    @Test
    void severalFunctionCallsInOneReplyAreAllParsedInOrder() {
        String text = "<tool_call>\n<function=web_search>\n<parameter=query>\nfirst \"q\"\n</parameter>\n</function>\n</tool_call>\n"
                + "<tool_call>\n<function=web_search>\n<parameter=query>\nsecond\n</parameter>\n</function>\n</tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("{\"query\":\"first \\\"q\\\"\"}", calls.get(0).argumentsJson());
        assertEquals("{\"query\":\"second\"}", calls.get(1).argumentsJson());
        assertEquals("text-call-0", calls.get(0).id());
        assertEquals("text-call-1", calls.get(1).id());
    }

    @Test
    void functionSpellingWithTwoParametersAndMissingCloseTags() {
        String text = "<tool_call><function=write><parameter=path>out.txt<parameter=content>hello</function>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("{\"path\":\"out.txt\",\"content\":\"hello\"}", calls.get(0).argumentsJson());
    }

    @Test
    void proseThatMentionsNeitherSpellingIsNotAToolCall() {
        assertFalse(TextToolCallParser.looksLikeTextToolCall("Use the function keyword to define one."));
        assertTrue(TextToolCallParser.parse("The answer is 42.").isEmpty());
    }

    @Test
    void stripRemovesFunctionSpellingBlocksAndKeepsProse() {
        String text = "Let me check.\n<tool_call>\n<function=read>\n<parameter=path>\na\n</parameter>\n</function>\n</tool_call>";
        assertEquals("Let me check.", TextToolCallParser.stripToolCallBlocks(text));
    }
}
