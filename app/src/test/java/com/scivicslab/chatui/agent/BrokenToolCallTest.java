package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@code BrokenToolCallRepair_261115_oo01}: how the parser tells a call it can run from an attempt to write
 * one that went wrong, and two ways it used to read calls wrong.
 */
class BrokenToolCallTest {

    /** The tools of these tests and the arguments they cannot run without. */
    private static final Function<String, List<String>> REQUIRED = name -> switch (name) {
        case "read" -> List.of("path");
        case "ask_chat" -> List.of("chatId", "prompt");
        default -> List.of();
    };

    /** project9/chat-01, turn34, 2026-11-15 18:25, on GLM-5.3-Flash-Uncensored: the reply as it was shown. */
    static final String REPLY_1825 = """
            おっしゃるとおりです。…まず現状の3文書を読み直して、lintに言及している箇所を特定します。

            <invoke name=“read">
            doc_Base010/docs/ProjectStandard2/030_ProgrammingProject/200_documentation/100_AntiPattern/100_DocumentStanceAndSentenceEndings_261003_oo01/100_DocumentStanceAndSentenceEndings_261003_oo01.md
            doc_Base010/.../110_RewritingWithoutChangingMeaning_261003_oo01.md
            doc_Base010/.../120_YomiyasuFindingsScope_261003_oo01.md
            120の検査手順節を確認し、lint必須規定を外す改版を当てるため
            """;

    @Test
    void theReplyOf1825IsABrokenCallAndSaysWhatWasWrong() {
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(REPLY_1825, REQUIRED);
        assertTrue(a.broken(), "a call was attempted and cannot run: " + a);
        assertTrue(a.calls().isEmpty(), "nothing is run from it: " + a.calls());
        String said = String.join("\n", a.problems());
        assertTrue(said.contains("is not the ASCII quote"), said);
        assertTrue(said.contains("not closed with </invoke>"), said);
        assertTrue(said.contains("has no <parameter> tag"), said);
        assertTrue(said.contains("one \"path\" parameter, one path per line"), said);
        assertEquals("read", a.exampleTool());
    }

    @Test
    void theSameCallClosedButWithBareLinesIsStillBroken_noArgumentIsGuessed() {
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(REPLY_1825 + "</invoke>\n", REQUIRED);
        assertTrue(a.broken(), a.toString());
        assertEquals(1, a.calls().size(), "the call is read, with the name despite its quote mark");
        assertEquals("read", a.calls().get(0).name());
        assertEquals("{}", a.calls().get(0).argumentsJson(), "no path is made up from the bare lines");
        assertTrue(String.join("\n", a.problems()).contains("is not the ASCII quote"));
    }

    @Test
    void typographicQuotesAroundNamesAreAccepted_andAWellFormedCallRuns() {
        String text = "<invoke name=“read”>\n<parameter name=“path”>a.md</parameter>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertEquals("{\"path\":\"a.md\"}", a.calls().get(0).argumentsJson());
    }

    @Test
    void typographicQuotesInsideAValueAreKeptAsWritten() {
        String text = "<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">02</parameter>\n"
                + "<parameter name=\"prompt\">Say “hello” and name=“x”.</parameter>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertTrue(a.calls().get(0).argumentsJson().contains("Say “hello” and name=“x”."), a.calls().get(0).argumentsJson());
    }

    /** The second bug: a request whose text quoted the parameter tag was cut where the quotation began. */
    @Test
    void aValueThatQuotesTheParameterTagIsNotCutThere() {
        String prompt = "Two things are wrong with that call: the opening quote is a typographic “, and the "
                + "paths are bare lines with no <parameter> tag. Write <parameter name=\"path\">a.md</parameter> "
                + "instead, then <reason> the reason.\nThe rest of the request.";
        String text = "<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">56</parameter>\n"
                + "<parameter name=\"prompt\">" + prompt + "</parameter>\n"
                + "<reason>hand the bug over</reason>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertFalse(a.broken(), a.toString());
        String json = a.calls().get(0).argumentsJson();
        assertTrue(json.contains("The rest of the request."), "the prompt runs to its own close tag: " + json);
        assertTrue(json.contains("\"chatId\":\"56\""), json);
        assertTrue(json.contains("\"reason\":\"hand the bug over\""), json);
    }

    @Test
    void aValueWhoseCloseTagIsMissingEndsAtTheNextParameterOnItsOwnLine() {
        String text = "<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">02\n<parameter name=\"prompt\">hi</parameter>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertEquals("{\"chatId\":\"02\",\"prompt\":\"hi\"}", a.calls().get(0).argumentsJson());
    }

    @Test
    void aCallMissingARequiredArgumentIsBroken() {
        String text = "<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">02</parameter>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertTrue(a.broken());
        assertTrue(a.problems().get(0).contains("lacks the parameter \"prompt\""), a.problems().toString());
        assertEquals("ask_chat", a.exampleTool());
    }

    @Test
    void quotationsInProseOrInACodeFenceAreNotAttempts() {
        String prose = "The call is written like <invoke name=“read\"> with each path in a parameter tag.\n"
                + "```\n<invoke name=\"read\">\nsome/path\n```\nThat is all.";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(prose, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertTrue(a.calls().isEmpty());
    }

    @Test
    void aToolCallLineThatNamesNoToolIsBroken() {
        TextToolCallParser.Analysis a = TextToolCallParser.analyze("Reading now.\n<tool_call>\nsome text\n", REQUIRED);
        assertTrue(a.broken(), a.toString());
    }

    @Test
    void theToolCallFunctionSpellingOverTwoLinesIsOneSoundCall() {
        String text = "<tool_call>\n<function=read>\n<parameter=path>\na.md\n</parameter>\n</function>\n</tool_call>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertEquals(1, a.calls().size());
    }

    // ---- A call written in the example form, and a call glued to the end of a sentence
    // (SquareBracketCallRepair_261116_oo01).

    /** project8/chat-01 (Claude, sonnet), 2026-11-16 10:29:50 on 28010: the reply as it was shown. */
    static final String SQUARE_REPLY_1029 = "届いたかどうかを、相手の会話のログで確かめます。\n\n"
            + "[invoke name=\"get_chat_log\"]\n"
            + "[parameter name=\"chatId\"]project1/chat-01[/parameter]\n"
            + "[parameter name=\"count\"]10[/parameter]\n"
            + "<reason>Check whether the ask_chat message arrived in project1/chat-01.</reason>\n"
            + "[/invoke]";

    private static final java.util.function.Predicate<String> TOOLS_HERE =
            name -> List.of("get_chat_log", "read", "ask_chat", "calc").contains(name);

    @Test
    void aCallInTheExampleFormOfAToolOfThisConversationIsABrokenAttempt_notAnAnswer() {
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(SQUARE_REPLY_1029, REQUIRED, TOOLS_HERE);
        assertTrue(a.broken(), "the reply meant to call a tool: " + a);
        assertTrue(a.calls().isEmpty(), "the square-bracket form is never run: " + a.calls());
        String said = String.join("\n", a.problems());
        assertTrue(said.contains("get_chat_log"), said);
        assertTrue(said.contains("square brackets"), said);
        assertTrue(said.contains("was not run"), said);
        assertTrue(said.contains("angle brackets"), said);
        assertEquals("get_chat_log", a.exampleTool());
    }

    @Test
    void withoutTheToolSetTheExampleFormStaysProse() {
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(SQUARE_REPLY_1029, REQUIRED);
        assertFalse(a.broken(), a.toString());
        assertTrue(a.calls().isEmpty());
    }

    @Test
    void anExampleFormBlockIsLeftAlone_unlessItNamesAToolHereAndCarriesParameters() {
        // a tool this conversation does not have
        assertFalse(TextToolCallParser.analyze(
                "[invoke name=\"no_such_tool\"]\n[parameter name=\"a\"]b[/parameter]\n[/invoke]", REQUIRED, TOOLS_HERE).broken());
        // no parameter block
        assertFalse(TextToolCallParser.analyze("[invoke name=\"get_chat_log\"]\n[/invoke]", REQUIRED, TOOLS_HERE).broken());
        // quoted in a fenced code block
        assertFalse(TextToolCallParser.analyze("Write it like this:\n```\n" + SQUARE_REPLY_1029 + "\n```\n", REQUIRED, TOOLS_HERE).broken());
        // quoted in the middle of a sentence
        assertFalse(TextToolCallParser.analyze(
                "The example form is [invoke name=\"get_chat_log\"] with [parameter name=\"chatId\"]x[/parameter] inside.",
                REQUIRED, TOOLS_HERE).broken());
    }

    @Test
    void aRealCallAlongsideAnExampleFormBlockRuns_andTheExampleIsNotRepaired() {
        String text = "Shown as an example:\n[invoke name=\"get_chat_log\"]\n[parameter name=\"chatId\"]x[/parameter]\n[/invoke]\n\n"
                + "<invoke name=\"read\">\n<parameter name=\"path\">a.md</parameter>\n</invoke>";
        TextToolCallParser.Analysis a = TextToolCallParser.analyze(text, REQUIRED, TOOLS_HERE);
        assertFalse(a.broken(), a.toString());
        assertEquals(1, a.calls().size());
        assertEquals("read", a.calls().get(0).name());
    }

    private static final String CALL = "<invoke name=\"get_chat_log\">\n<parameter name=\"chatId\">project1/chat-01</parameter>\n"
            + "<parameter name=\"count\">10</parameter>\n<reason>r</reason>\n</invoke>";

    @Test
    void plainTextThenACallAtTheStartOfALineRuns() {
        for (String sep : List.of("\n\n", "\n", "\n\n  ")) {
            TextToolCallParser.Analysis a = TextToolCallParser.analyze("確かめます。" + sep + CALL, REQUIRED, TOOLS_HERE);
            assertEquals(1, a.calls().size(), "separator " + sep.replace("\n", "\\n"));
            assertEquals("get_chat_log", a.calls().get(0).name());
            assertFalse(a.broken());
        }
    }

    @Test
    void aCallGluedToTheEndOfAFinishedSentenceRuns() {
        for (String glue : List.of("確かめます。", "確かめます。 ", "I will check it.", "確認します）")) {
            TextToolCallParser.Analysis a = TextToolCallParser.analyze(glue + CALL, REQUIRED, TOOLS_HERE);
            assertEquals(1, a.calls().size(), "glue: " + glue);
            assertEquals("get_chat_log", a.calls().get(0).name());
            assertTrue(a.calls().get(0).argumentsJson().contains("\"count\":\"10\""), a.calls().get(0).argumentsJson());
            assertFalse(a.broken(), a.toString());
        }
        assertTrue(TextToolCallParser.looksLikeTextToolCall("確かめます。" + CALL));
        assertEquals("確かめます。", TextToolCallParser.stripToolCallBlocks("確かめます。" + CALL));
    }

    @Test
    void aTagQuotedInTheMiddleOfASentenceIsStillNotACall() {
        for (String prose : List.of(
                "A call starts with <invoke name=\"get_chat_log\"> and ends with </invoke>, as in `" + CALL + "` here.",
                "The form: <invoke name=\"get_chat_log\"><parameter name=\"chatId\">x</parameter></invoke> is what to write.",
                "Use <invoke name=\"get_chat_log\"> for it. Then more text follows after the tag.")) {
            assertTrue(TextToolCallParser.analyze(prose, REQUIRED, TOOLS_HERE).calls().isEmpty(), prose);
        }
    }
}
