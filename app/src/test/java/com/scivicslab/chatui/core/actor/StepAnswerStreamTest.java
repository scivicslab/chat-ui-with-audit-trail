package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step's text is shown as a provisional answer while it streams, and taken back when it turns
 * out to call a tool. What the pane ends up with is what these tests pin down: the provisional text
 * it was sent, and where the rest went.
 */
class StepAnswerStreamTest {

    private final List<ChatEvent> events = new ArrayList<>();
    private final StepAnswerStream stream = new StepAnswerStream(events::add);

    private String types() {
        StringBuilder b = new StringBuilder();
        for (ChatEvent e : events) b.append(b.length() == 0 ? "" : ",").append(e.type());
        return b.toString();
    }

    /** What the pane holds as the provisional answer: the answer_delta text, unless retracted. */
    private String provisional() {
        StringBuilder b = new StringBuilder();
        for (ChatEvent e : events) {
            if (e.type().equals("answer_delta")) b.append(e.content());
            if (e.type().equals("answer_retract")) b.setLength(0);
        }
        return b.toString();
    }

    /** What the pane's thinking trace gets: the thinking text, plus any text taken back into it. */
    private String trace() {
        StringBuilder b = new StringBuilder();
        StringBuilder shown = new StringBuilder();
        for (ChatEvent e : events) {
            if (e.type().equals("answer_delta")) shown.append(e.content());
            if (e.type().equals("answer_retract")) { b.append(shown); shown.setLength(0); }
            if (e.type().equals("thinking")) b.append(e.content());
        }
        return b.toString();
    }

    @Test
    void plainAnswerStreamsAndNothingIsTakenBack() {
        stream.accept("The answer ");
        stream.accept("is 42.");
        stream.finish(false);

        assertEquals("The answer is 42.", provisional());
        assertEquals("", trace());
        assertTrue(!types().contains("answer_retract") && !types().contains("thinking"), types());
    }

    @Test
    void textIsShownBeforeTheStepEnds() {
        stream.accept("Hello there");
        assertEquals("Hello there", provisional());
    }

    @Test
    void callAtTheStartIsNeverShownAsAnAnswer() {
        stream.accept("<invoke name=\"calc\">");
        stream.accept("<parameter name=\"expression\">1+1</parameter></invoke>");
        stream.finish(true);

        assertEquals("", provisional());
        assertEquals("<invoke name=\"calc\"><parameter name=\"expression\">1+1</parameter></invoke>", trace());
        assertTrue(!types().contains("answer_retract"), types());
    }

    @Test
    void proseBeforeACallIsTakenBackAndMovedToTheTrace() {
        stream.accept("Let me check.\n");
        stream.accept("<invoke name=\"calc\">");
        stream.accept("<parameter name=\"expression\">1+1</parameter></invoke>");
        stream.finish(true);

        assertEquals("", provisional());
        assertEquals("Let me check.\n<invoke name=\"calc\"><parameter name=\"expression\">1+1</parameter></invoke>",
                trace());
        assertEquals(1, events.stream().filter(e -> e.type().equals("answer_retract")).count());
    }

    @Test
    void aMarkerSplitAcrossChunksIsNeverShown() {
        stream.accept("Checking\n<inv");
        // The "<inv" could be the start of "<invoke", so it is held back, not shown.
        assertEquals("Checking\n", provisional());
        stream.accept("oke name=\"calc\">");
        stream.finish(true);

        assertEquals("", provisional());
        assertEquals("Checking\n<invoke name=\"calc\">", trace());
    }

    @Test
    void aLessThanThatIsNotAMarkerIsShownOnceItIsKnown() {
        stream.accept("if a <");
        assertEquals("if a ", provisional());
        stream.accept(" b then");
        stream.finish(false);

        assertEquals("if a < b then", provisional());
    }

    @Test
    void aHeldBackTailIsFlushedWhenTheStepEnds() {
        stream.accept("ends with <inv");
        stream.finish(false);

        assertEquals("ends with <inv", provisional());
    }

    @Test
    void eachOtherCallSpellingIsRecognised() {
        for (String marker : new String[] {"<function_calls>", "<tool_call>", "<function=calc>"}) {
            events.clear();
            StepAnswerStream s = new StepAnswerStream(events::add);
            s.accept("Thinking aloud.\n");
            s.accept(marker);
            s.finish(true);
            assertEquals("", provisional(), marker);
            assertEquals("Thinking aloud.\n" + marker, trace(), marker);
        }
    }

    /**
     * A block quoted in a fence or in the middle of a line is not a call to the parser, so the pane must
     * not take the answer back for it (2026-11-08: a report that quoted call blocks).
     */
    @Test
    void aQuotedBlockInsideAFenceDoesNotTakeTheAnswerBack() {
        stream.accept("The model wrote:\n```\n<invoke name=\"ask_chat\">\n</invoke>\n```\nand that is all.");
        stream.finish(false);

        assertEquals("The model wrote:\n```\n<invoke name=\"ask_chat\">\n</invoke>\n```\nand that is all.", provisional());
        assertTrue(!types().contains("answer_retract") && !types().contains("thinking"), types());
    }

    @Test
    void aQuotedBlockInTheMiddleOfALineDoesNotTakeTheAnswerBack() {
        stream.accept("It wrote ");
        stream.accept("<invoke name=\"ask_chat\"><parameter name=\"chatId\">50</parameter></invoke> twice.");
        stream.finish(false);

        assertEquals("It wrote <invoke name=\"ask_chat\"><parameter name=\"chatId\">50</parameter></invoke> twice.",
                provisional());
        assertTrue(!types().contains("answer_retract") && !types().contains("thinking"), types());
    }

    @Test
    void aBlockAfterAFencedExampleStillTakesTheAnswerBack() {
        stream.accept("Example:\n```\n<invoke name=\"ask_chat\">\n</invoke>\n```\n");
        stream.accept("<invoke name=\"calc\">");
        stream.finish(true);

        assertEquals("", provisional());
        assertTrue(types().contains("answer_retract"), types());
    }

    @Test
    void aCallTheStreamDidNotRecogniseIsTakenBackWhenTheStepEnds() {
        stream.accept("Some prose that the parser later reads as a call");
        assertEquals("Some prose that the parser later reads as a call", provisional());
        stream.finish(true);

        assertEquals("", provisional());
        assertEquals("Some prose that the parser later reads as a call", trace());
    }

    @Test
    void textAfterTheCallStartsGoesStraightToTheTrace() {
        stream.accept("<invoke name=\"a\">");
        stream.accept("more");
        stream.accept(" and more");

        assertEquals("<invoke name=\"a\">more and more", trace());
        assertEquals("", provisional());
    }

    @Test
    void emptyChunksAreIgnored() {
        stream.accept(null);
        stream.accept("");
        stream.finish(false);
        assertEquals("", types());
    }
}
