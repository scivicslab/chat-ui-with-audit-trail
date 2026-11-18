package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three spellings of a text tool call the parser accepts.
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

    /**
     * The measured failure: an answer that talked about the spelling was run as a call. A reply that
     * quoted {@code <tool_call><function=read>} in backticks, and another that put it in a table row,
     * made the conversation execute {@code read} and {@code web_search} with no arguments.
     */
    @Test
    void proseQuotingTheSpellingIsNotACall() {
        String inBackticks = "chat-05 returned `<tool_call><function=read>…` as its answer.";
        String inTableRow = "| 3 | モデルの応答 | `<tool_call><function=web_search>…` の文面 |";
        for (String prose : List.of(inBackticks, inTableRow, inBackticks + "\n" + inTableRow)) {
            assertFalse(TextToolCallParser.looksLikeTextToolCall(prose), prose);
            assertTrue(TextToolCallParser.parse(prose).isEmpty(), prose);
        }
    }

    @Test
    void aCallAfterProseOnItsOwnLineIsStillParsed() {
        String text = "I will look it up.\n\n<tool_call>\n<function=web_search>\n<parameter=query>\nx\n</parameter>\n</function>\n</tool_call>";
        assertEquals(1, TextToolCallParser.parse(text).size());
        String indented = "  <function=read>\n  <parameter=path>\na\n</parameter>\n</function>";
        assertEquals(1, TextToolCallParser.parse(indented).size());
    }

    @Test
    void aCallInsideAFencedCodeBlockIsAnExampleNotACall() {
        String text = "The format is:\n```\n<tool_call>\n<function=read>\n<parameter=path>\na\n</parameter>\n</function>\n</tool_call>\n```";
        assertFalse(TextToolCallParser.looksLikeTextToolCall(text));
        assertTrue(TextToolCallParser.parse(text).isEmpty());
    }

    /**
     * The measured failure of 2026-11-08: a report that quoted a call block was run as a call, with
     * no chatId, and the report was lost. The invoke spelling is read as the function= spelling is:
     * code fences are ignored, and only a block that starts a line is a call.
     */
    @Test
    void anInvokeBlockInsideAFencedCodeBlockIsAQuoteNotACall() {
        String text = "The model wrote:\n```\n<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">50</parameter>\n</invoke>\n```\nand that is all.";
        assertFalse(TextToolCallParser.looksLikeTextToolCall(text));
        assertTrue(TextToolCallParser.parse(text).isEmpty());
    }

    @Test
    void anInvokeBlockInTheMiddleOfALineIsAQuoteNotACall() {
        String inProse = "It wrote <invoke name=\"ask_chat\"><parameter name=\"chatId\">50</parameter></invoke> twice.";
        String inBackticks = "The block `<invoke name=\"calc\"><parameter name=\"expression\">1+1</parameter></invoke>` is a call.";
        for (String prose : List.of(inProse, inBackticks, inProse + "\n" + inBackticks)) {
            assertFalse(TextToolCallParser.looksLikeTextToolCall(prose), prose);
            assertTrue(TextToolCallParser.parse(prose).isEmpty(), prose);
        }
    }

    @Test
    void anInvokeBlockThatStartsALineIsStillACall() {
        String afterProse = "I will look it up.\n\n<invoke name=\"web_search\">\n<parameter name=\"query\">x</parameter>\n</invoke>";
        assertEquals(1, TextToolCallParser.parse(afterProse).size());
        String indented = "  <invoke name=\"read\">\n  <parameter name=\"path\">a</parameter>\n  </invoke>";
        assertEquals(1, TextToolCallParser.parse(indented).size());
        String wrapped = "<function_calls><invoke name=\"read\"><parameter name=\"path\">a</parameter></invoke></function_calls>";
        assertEquals(1, TextToolCallParser.parse(wrapped).size());
        String twoMessages = "<invoke name=\"a\"><parameter name=\"x\">1</parameter></invoke>done.\n\n"
                + "<invoke name=\"b\"><parameter name=\"x\">2</parameter></invoke>";
        assertEquals(2, TextToolCallParser.parse(twoMessages).size());
    }

    @Test
    void aFencedExampleDoesNotHideARealCallOutsideTheFence() {
        String text = "Example:\n```\n<invoke name=\"ask_chat\">\n</invoke>\n```\n<invoke name=\"calc\">\n<parameter name=\"expression\">1+1</parameter>\n</invoke>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("calc", calls.get(0).name());
    }

    @Test
    void stripLeavesAQuotedInvokeBlockAlone() {
        String prose = "It wrote `<invoke name=\"ask_chat\"><parameter name=\"chatId\">50</parameter></invoke>` in a sentence.";
        assertEquals(prose, TextToolCallParser.stripToolCallBlocks(prose));
        String text = "Let me check.\n<invoke name=\"calc\">\n<parameter name=\"expression\">1+1</parameter>\n</invoke>";
        assertEquals("Let me check.", TextToolCallParser.stripToolCallBlocks(text));
    }

    @Test
    void stripLeavesQuotedSpellingAlone() {
        String prose = "It wrote `<tool_call><function=read>…</function></tool_call>` in the middle of a line.";
        assertEquals(prose, TextToolCallParser.stripToolCallBlocks(prose));
    }

    @Test
    void stripRemovesFunctionSpellingBlocksAndKeepsProse() {
        String text = "Let me check.\n<tool_call>\n<function=read>\n<parameter=path>\na\n</parameter>\n</function>\n</tool_call>";
        assertEquals("Let me check.", TextToolCallParser.stripToolCallBlocks(text));
    }

    // ---- GLM's native spelling: <tool_call>NAME<arg_key>K</arg_key><arg_value>V</arg_value></tool_call> ----
    // Measured on project9/chat-01 (GLM-5.3-Flash-Uncensored, openai-compat): its last three turns ended at once with
    // "0 tool call(s)" and the person saw these lines as the answer.

    @Test
    void glmSpellingIsParsedFromTheMeasuredReply() {
        String text = "<tool_call>load_skill<arg_key>name</arg_key><arg_value>html-saurus-search</arg_value></tool_call>\n"
                + "<tool_call>read<arg_key>path</arg_key><arg_value>doc_Base010/docs/a/b.md</arg_value></tool_call>";
        assertTrue(TextToolCallParser.looksLikeTextToolCall(text));
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("load_skill", calls.get(0).name());
        assertEquals("{\"name\":\"html-saurus-search\"}", calls.get(0).argumentsJson());
        assertEquals("read", calls.get(1).name());
        assertEquals("{\"path\":\"doc_Base010/docs/a/b.md\"}", calls.get(1).argumentsJson());
        assertEquals("text-call-0", calls.get(0).id());
        assertEquals("text-call-1", calls.get(1).id());
    }

    @Test
    void glmSpellingAfterALongAnswerIsParsed() {
        String text = "The work is finished and the report above is complete.\n\n"
                + "<tool_call>accept_reply<arg_key>requestId</arg_key><arg_value>project9/chat-02:s40:t3:r1</arg_value>"
                + "<arg_key>reason</arg_key><arg_value>the report answers the request</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("accept_reply", calls.get(0).name());
        assertEquals("{\"requestId\":\"project9/chat-02:s40:t3:r1\",\"reason\":\"the report answers the request\"}",
                calls.get(0).argumentsJson());
    }

    /**
     * The measured shape: GLM glued accept_reply to the end of a finished answer, on the same line, with a value that
     * has no opening tag (project9/chat-01, turns 14, 18 and 20).
     */
    @Test
    void glmSpellingGluedToTheEndOfAFinishedSentenceIsParsed() {
        String text = "# 「計算と証明が分かれている」問題への返答\n\n天気予報も駅構内の喫茶店も完了済みです。"
                + "<tool_call>accept_reply<arg_key>requestId</arg_key><arg_value>project9/chat-02:s40:t3:r1</arg_value>"
                + "<arg_key>reason</arg_key>返答の内容は十分で、追加の確認は不要なため</arg_value></tool_call>";
        assertTrue(TextToolCallParser.looksLikeTextToolCall(text));
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("accept_reply", calls.get(0).name());
        assertEquals("{\"requestId\":\"project9/chat-02:s40:t3:r1\",\"reason\":\"返答の内容は十分で、追加の確認は不要なため\"}",
                calls.get(0).argumentsJson());
        assertEquals("# 「計算と証明が分かれている」問題への返答\n\n天気予報も駅構内の喫茶店も完了済みです。",
                TextToolCallParser.stripToolCallBlocks(text));
    }

    /**
     * The measured shape of project9/chat-01, session 140, turn 44, step 6, with ask_person before its bash call: two
     * calls glued one after the other to the end of a finished sentence are two calls, each with its own arguments.
     */
    @Test
    void twoGlmCallsGluedToTheEndOfASentenceAreTwoCalls() {
        String text = "どうしますか？ A/B/C、または章立て自体の修正からでもどうぞ。"
                + "<tool_call>ask_person<arg_key>question</arg_key><arg_value>A, B or C?</arg_value></tool_call>"
                + "<tool_call>bash<arg_key>command</arg_key><arg_value>sed -n '1,80p' instance.h</arg_value>"
                + "<arg_key>reason</arg_key><arg_value>read the Instance class</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(2, calls.size(), calls.toString());
        assertEquals("ask_person", calls.get(0).name());
        assertEquals("{\"question\":\"A, B or C?\"}", calls.get(0).argumentsJson());
        assertEquals("bash", calls.get(1).name());
        assertEquals("{\"command\":\"sed -n '1,80p' instance.h\",\"reason\":\"read the Instance class\"}",
                calls.get(1).argumentsJson());
        assertEquals("どうしますか？ A/B/C、または章立て自体の修正からでもどうぞ。", TextToolCallParser.stripToolCallBlocks(text));
    }

    private static String contentOf(ToolCall call) {
        return new org.json.JSONObject(call.argumentsJson()).getString("content");
    }

    /**
     * project9/chat-01, session 140, turn 46, step 28: GLM wrote a file whose content held a ```cpp block, to see
     * whether the block survives. The call reached write as "before\n\n\n\nafter": the whole block was gone.
     */
    @Test
    void aFencedBlockInsideAGlmArgValueReachesTheToolWhole() {
        String text = "<tool_call>write<arg_key>content</arg_key><arg_value>before\n\n```cpp\nint main() { return 0; }\n```"
                + "\n\nafter\n</arg_value><arg_key>path</arg_key><arg_value>tmp-fence-test.md</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("before\n\n```cpp\nint main() { return 0; }\n```\n\nafter", contentOf(calls.get(0)));
    }

    @Test
    void aFencedBlockInsideAnInvokeParameterReachesTheToolWhole_evenWhenItQuotesTheClosingTag() {
        String content = "# Chapter\n\n```cpp\nenum class EventWatcherPhase { PreInputMethod, InputMethod };\n```\n\n"
                + "A tag in a quoted block:\n\n```xml\n<parameter name=\"path\">x</parameter>\n</invoke>\n```\n\nEnd.";
        String text = "Writing the chapter.\n\n<invoke name=\"write\">\n<parameter name=\"path\">ch1.md</parameter>\n"
                + "<parameter name=\"content\">" + content + "</parameter>\n</invoke>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals(content, contentOf(calls.get(0)));
        assertEquals("ch1.md", new org.json.JSONObject(calls.get(0).argumentsJson()).getString("path"));
    }

    @Test
    void aFencedBlockInsideAFunctionParameterReachesTheToolWhole() {
        String text = "<tool_call>\n<function=write>\n<parameter=path>\na.md\n</parameter>\n<parameter=content>\n"
                + "x\n```java\nint y = 1;\n```\nz\n</parameter>\n</function>\n</tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("x\n```java\nint y = 1;\n```\nz", contentOf(calls.get(0)));
    }

    /** A call quoted in a fenced block of prose is still not a call. */
    @Test
    void aCallInsideAFencedBlockOfProseIsStillNotRead() {
        String text = "Write it like this:\n\n```\n<invoke name=\"write\">\n<parameter name=\"path\">a.md</parameter>\n"
                + "<parameter name=\"content\">x</parameter>\n</invoke>\n```\n\nThat is all.";
        assertTrue(TextToolCallParser.parse(text).isEmpty());
        assertFalse(TextToolCallParser.looksLikeTextToolCall(text));
    }

    /** What the glued form must not take for a call: a quotation in prose, which is not a finished sentence's tail. */
    @Test
    void glmSpellingQuotedMidSentenceOrNotLastIsNotACall() {
        String afterColon = "GLM writes it like this: <tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>";
        String inBackticks = "It wrote `<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>`";
        String notLast = "It ended.<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call> And more text.";
        for (String prose : List.of(afterColon, inBackticks, notLast)) {
            assertFalse(TextToolCallParser.looksLikeTextToolCall(prose), prose);
            assertTrue(TextToolCallParser.parse(prose).isEmpty(), prose);
        }
    }

    /** The measured line had a value with no opening tag: the key's close is followed by text, then </arg_value>. */
    @Test
    void glmSpellingToleratesAValueWithoutItsOpenTag() {
        String text = "<tool_call>accept_reply<arg_key>requestId</arg_key><arg_value>project9/chat-02:s40:t3:r1</arg_value>"
                + "<arg_key>reason</arg_key>the report answers the request</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("{\"requestId\":\"project9/chat-02:s40:t3:r1\",\"reason\":\"the report answers the request\"}",
                calls.get(0).argumentsJson());
    }

    /** A value whose </arg_value> was not written ends at the next key. */
    @Test
    void glmSpellingToleratesAMissingArgValueClose() {
        String text = "<tool_call>write<arg_key>path</arg_key><arg_value>out.txt<arg_key>content</arg_key>"
                + "<arg_value>hello</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(1, calls.size());
        assertEquals("{\"path\":\"out.txt\",\"content\":\"hello\"}", calls.get(0).argumentsJson());
    }

    @Test
    void glmSpellingToleratesAMissingToolCallCloseAndSeparatesCalls() {
        String text = "<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value>\n"
                + "<tool_call>read<arg_key>path</arg_key><arg_value>b</arg_value>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("{\"path\":\"a\"}", calls.get(0).argumentsJson());
        assertEquals("{\"path\":\"b\"}", calls.get(1).argumentsJson());
    }

    @Test
    void glmSpellingWithNoArgumentsAndWithNewlinesInValues() {
        List<ToolCall> none = TextToolCallParser.parse("<tool_call>list_chats</tool_call>");
        assertEquals(1, none.size());
        assertEquals("list_chats", none.get(0).name());
        assertEquals("{}", none.get(0).argumentsJson());
        List<ToolCall> multi = TextToolCallParser.parse(
                "<tool_call>write<arg_key>content</arg_key><arg_value>line1\nline2</arg_value></tool_call>");
        assertEquals("{\"content\":\"line1\\nline2\"}", multi.get(0).argumentsJson());
    }

    @Test
    void glmSpellingIsNotCountedTwiceWithTheFunctionSpelling() {
        String text = "<tool_call>\n<function=read>\n<parameter=path>\na\n</parameter>\n</function>\n</tool_call>\n"
                + "<tool_call>read<arg_key>path</arg_key><arg_value>b</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(text);
        assertEquals(2, calls.size());
        assertEquals("{\"path\":\"a\"}", calls.get(0).argumentsJson());
        assertEquals("{\"path\":\"b\"}", calls.get(1).argumentsJson());
    }

    @Test
    void glmSpellingQuotedInProseOrAFenceIsNotACall() {
        String inBackticks = "GLM writes `<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>` for a call.";
        String inMiddle = "It wrote <tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call> twice.";
        String fenced = "The format is:\n```\n<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>\n```";
        for (String prose : List.of(inBackticks, inMiddle, fenced)) {
            assertFalse(TextToolCallParser.looksLikeTextToolCall(prose), prose);
            assertTrue(TextToolCallParser.parse(prose).isEmpty(), prose);
        }
        String real = "Example:\n```\n<tool_call>read<arg_key>path</arg_key><arg_value>x</arg_value></tool_call>\n```\n"
                + "<tool_call>read<arg_key>path</arg_key><arg_value>y</arg_value></tool_call>";
        List<ToolCall> calls = TextToolCallParser.parse(real);
        assertEquals(1, calls.size());
        assertEquals("{\"path\":\"y\"}", calls.get(0).argumentsJson());
    }

    @Test
    void stripRemovesGlmSpellingBlocksAndKeepsProseAndQuotes() {
        String text = "Let me check.\n<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>\n"
                + "<tool_call>read<arg_key>path</arg_key><arg_value>b</arg_value>";
        assertEquals("Let me check.", TextToolCallParser.stripToolCallBlocks(text));
        String quoted = "GLM writes `<tool_call>read<arg_key>path</arg_key><arg_value>a</arg_value></tool_call>` for a call.";
        assertEquals(quoted, TextToolCallParser.stripToolCallBlocks(quoted));
    }
}
