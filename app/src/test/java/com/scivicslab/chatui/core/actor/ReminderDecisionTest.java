package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules for reading the end-of-turn decision ({@code ReminderDecidedAtTheEndOfTheTurn_261114_oo01}). */
class ReminderDecisionTest {

    private static final String ANSWER = "調査を始めました。明日の朝に結果を報告します。\n\nI will check again in 20 minutes.";

    @Test
    void registerWithTheTimeTheAnswerStates() {
        ReminderDecision d = ReminderDecision.parse("REGISTER 20 | \"I will check again in 20 minutes.\"", ANSWER);
        assertEquals(ReminderDecision.Kind.REGISTER, d.kind());
        assertEquals(20L, d.minutes());
        assertEquals("I will check again in 20 minutes.", d.quote());
    }

    @Test
    void registerDefaultWhenTheAnswerStatesNoTime() {
        ReminderDecision d = ReminderDecision.parse("REGISTER DEFAULT | 「明日の朝に結果を報告します」", ANSWER);
        assertEquals(ReminderDecision.Kind.REGISTER, d.kind());
        assertNull(d.minutes(), "no stated time: the caller applies the default");
        assertEquals("明日の朝に結果を報告します", d.quote());
    }

    @Test
    void noneKeepsItsReason() {
        ReminderDecision d = ReminderDecision.parse("NONE | the answer is complete", ANSWER);
        assertEquals(ReminderDecision.Kind.NONE, d.kind());
        assertEquals("the answer is complete", d.reason());
        assertEquals("NONE | the answer is complete", d.describe());
    }

    /** A promise the answer does not make is not registered, whatever the model says. */
    @Test
    void aQuoteThatIsNotInTheAnswerIsNotRegistered() {
        ReminderDecision d = ReminderDecision.parse("REGISTER DEFAULT | \"I will phone you tonight\"", ANSWER);
        assertEquals(ReminderDecision.Kind.NONE, d.kind());
        assertTrue(d.reason().contains("not in the answer"), d.reason());
    }

    @Test
    void aReplyThatIsNeitherLineIsUnreadableAndRegistersNothing() {
        assertEquals(ReminderDecision.Kind.UNREADABLE, ReminderDecision.parse("I think maybe.", ANSWER).kind());
        assertEquals(ReminderDecision.Kind.UNREADABLE, ReminderDecision.parse("", ANSWER).kind());
        assertEquals(ReminderDecision.Kind.UNREADABLE, ReminderDecision.parse(null, ANSWER).kind());
        assertEquals(ReminderDecision.Kind.UNREADABLE, ReminderDecision.parse("REGISTER 5 |", ANSWER).kind());
    }

    @Test
    void statedMinutesAreKeptInsideTheirBounds() {
        assertEquals(1L, ReminderDecision.parse("REGISTER 0 | \"I will check again in 20 minutes.\"", ANSWER).minutes());
        assertEquals(ReminderDecision.MAX_MINUTES,
                ReminderDecision.parse("REGISTER 99999 | \"I will check again in 20 minutes.\"", ANSWER).minutes());
    }

    @Test
    void theFirstLineWinsAndThinkingAloudBeforeItIsIgnored() {
        String reply = "The answer promises a check.\nREGISTER 20 | \"I will check again in 20 minutes.\"\nNONE | wrong";
        assertEquals(ReminderDecision.Kind.REGISTER, ReminderDecision.parse(reply, ANSWER).kind());
        assertEquals(ReminderDecision.Kind.NONE, ReminderDecision.parse("NONE | done\nREGISTER 5 | \"x\"", ANSWER).kind());
    }

    @Test
    void theQuoteMatchIgnoresCaseEmphasisMarksAndWhiteSpace() {
        ReminderDecision d = ReminderDecision.parse("REGISTER DEFAULT | \"**I will   check AGAIN** in 20 minutes\"", ANSWER);
        assertEquals(ReminderDecision.Kind.REGISTER, d.kind());
    }

    @Test
    void thePromptShowsTheAnswerTheAddresseeTheTimeAndTheRequestsAlreadySent() {
        String p = ReminderDecision.prompt(ANSWER, "project1/chat-01", List.of("project1/chat-02:t1:r1 to project1/chat-03"),
                "2026-11-14 22:00 (Saturday) JST");
        assertTrue(p.contains("FINAL ANSWER:\n" + ANSWER));
        assertTrue(p.contains("written to project1/chat-01"));
        assertTrue(p.contains("2026-11-14 22:00"));
        assertTrue(p.contains("project1/chat-02:t1:r1 to project1/chat-03"));
        assertTrue(p.contains("REGISTER <minutes or DEFAULT>") && p.contains("NONE | <short reason>"));
    }

    @Test
    void aVeryLongAnswerIsShownByItsEnd() {
        String longAnswer = "x".repeat(20_000) + "END";
        String p = ReminderDecision.prompt(longAnswer, "the person", List.of(), "now");
        assertTrue(p.endsWith("END"));
        assertTrue(p.length() < 12_000);
    }
}
