package com.scivicslab.chatui.core.actor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the end-of-turn step decided about a final answer: does the answer promise a later report or check that
 * nothing else will trigger, so that the timekeeper must remind the conversation
 * ({@code ReminderDecidedAtTheEndOfTheTurn_261114_oo01}).
 *
 * <p>The model is asked one small question and must answer with one explicit line, {@code REGISTER <minutes or
 * DEFAULT> | "<quote>"} or {@code NONE | <reason>}. This class builds the question and reads the line. It holds no
 * state and calls nothing, so the rules are testable without a model or an actor.</p>
 *
 * @param kind    what was decided
 * @param minutes for {@link Kind#REGISTER}: the wait the answer itself states, in minutes, or {@code null} when it
 *                states none and the default applies
 * @param quote   for {@link Kind#REGISTER}: the words of the answer that make the promise
 * @param reason  for {@link Kind#NONE}: why no reminder is needed; for {@link Kind#UNREADABLE}: what was wrong
 * @param raw     the model's reply, whole, for the record
 */
public record ReminderDecision(Kind kind, Long minutes, String quote, String reason, String raw) {

    /** The three outcomes. A reply that is neither of the two lines is {@link #UNREADABLE}: nothing is registered. */
    public enum Kind { REGISTER, NONE, UNREADABLE }

    /** The longest wait a stated time may ask for, in minutes: seven days. */
    public static final long MAX_MINUTES = 7L * 24 * 60;

    /** How much of the end of the answer the model is shown; a promise is made at the end. */
    static final int MAX_ANSWER_CHARS = 8000;

    private static final Pattern REGISTER = Pattern.compile(
            "^\\s*REGISTER\\s+(DEFAULT|\\d{1,7})\\s*[|:]\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern NONE = Pattern.compile(
            "^\\s*NONE\\b\\s*[|:\\-]?\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * The question put to the model.
     *
     * @param finalAnswer the answer the turn ended with
     * @param addressee   who the answer was written to: "the person", or the name of the conversation that asked
     * @param unanswered  the requests this conversation sent that have no reply yet, as one line each; their replies
     *                    start a turn of their own, so waiting for them needs no reminder
     * @param now         the current local time, as text, for converting a stated time into minutes
     */
    static String prompt(String finalAnswer, String addressee, List<String> unanswered, String now) {
        String answer = finalAnswer.length() <= MAX_ANSWER_CHARS ? finalAnswer
                : "[...]" + finalAnswer.substring(finalAnswer.length() - MAX_ANSWER_CHARS);
        StringBuilder p = new StringBuilder();
        p.append("This is a bookkeeping check at the end of a conversation turn. Do not continue the conversation ")
                .append("and do not call tools.\n\n")
                .append("Below is the FINAL ANSWER just written to ").append(addressee).append(". Does it PROMISE ")
                .append(addressee).append(" a later report, result or check that nothing else will trigger? ")
                .append("Examples that do: \"I will report back when it is done\", \"results tomorrow morning\", ")
                .append("\"I will check again in 20 minutes\".\n")
                .append("Examples that do not: an answer given now, \"let me know if you want more\", a question to ")
                .append("the reader, or waiting for the reply to a request already sent.\n");
        if (!unanswered.isEmpty()) {
            p.append("\nRequests already sent that have no reply yet (their replies start a new turn by themselves, ")
                    .append("so waiting for them needs no reminder):\n");
            for (String u : unanswered) p.append("- ").append(u).append("\n");
        }
        p.append("\nCurrent time: ").append(now).append("\n\n")
                .append("Reply with exactly one line, and nothing else:\n")
                .append("REGISTER <minutes or DEFAULT> | \"<the exact words of the answer that make the promise>\"\n")
                .append("NONE | <short reason>\n\n")
                .append("Write <minutes> only when the answer itself states when the report is due: convert it to ")
                .append("whole minutes from the current time (for \"tomorrow morning\" use the minutes until 09:00 ")
                .append("tomorrow). When the answer states no time, write DEFAULT.\n\n")
                .append("FINAL ANSWER:\n").append(answer);
        return p.toString();
    }

    /**
     * Reads the model's reply.
     *
     * <p>A {@code REGISTER} whose quote is not in the answer is not trusted: a reminder is registered only for a
     * promise the answer really makes, so it comes back as {@link Kind#NONE} with the reason said.</p>
     *
     * @param modelText   what the model wrote
     * @param finalAnswer the answer it was shown
     * @return the decision; never {@code null}
     */
    static ReminderDecision parse(String modelText, String finalAnswer) {
        String raw = modelText == null ? "" : modelText;
        // Whichever of the two lines comes first counts, so a model that thinks aloud before the line is read.
        Matcher reg = REGISTER.matcher(raw);
        Matcher none = NONE.matcher(raw);
        boolean hasReg = reg.find();
        boolean hasNone = none.find();
        if (hasReg && (!hasNone || reg.start() <= none.start())) {
            String quote = stripQuotes(reg.group(2));
            if (quote.isBlank()) return new ReminderDecision(Kind.UNREADABLE, null, "", "REGISTER without a quote", raw);
            if (!contains(finalAnswer, quote)) {
                return new ReminderDecision(Kind.NONE, null, quote,
                        "not registered: the quoted words are not in the answer", raw);
            }
            Long minutes = null;
            String m = reg.group(1);
            if (!m.equalsIgnoreCase("DEFAULT")) {
                long v = Long.parseLong(m);
                minutes = Math.max(1, Math.min(MAX_MINUTES, v));
            }
            return new ReminderDecision(Kind.REGISTER, minutes, quote, "", raw);
        }
        if (hasNone) {
            String why = none.group(1).isBlank() ? "(no reason given)" : none.group(1);
            return new ReminderDecision(Kind.NONE, null, "", why, raw);
        }
        return new ReminderDecision(Kind.UNREADABLE, null, "", "the reply is neither REGISTER nor NONE", raw);
    }

    /** @return the line for the log: what was decided, and on what words or reason */
    String describe() {
        return switch (kind) {
            case REGISTER -> "REGISTER " + (minutes == null ? "DEFAULT" : minutes + " min") + " | \"" + quote + "\"";
            case NONE -> "NONE | " + reason + (quote.isBlank() ? "" : " | \"" + quote + "\"");
            case UNREADABLE -> "UNREADABLE | " + reason;
        };
    }

    private static String stripQuotes(String s) {
        String t = s.strip();
        String open = "\"'`“”‘’「」『』";
        while (t.length() > 1 && open.indexOf(t.charAt(0)) >= 0) t = t.substring(1).stripLeading();
        while (t.length() > 1 && open.indexOf(t.charAt(t.length() - 1)) >= 0) t = t.substring(0, t.length() - 1).stripTrailing();
        return t;
    }

    /** Whether {@code quote} is in {@code answer}, ignoring case, markdown emphasis marks and differences of white space. */
    private static boolean contains(String answer, String quote) {
        return squash(answer).contains(squash(quote));
    }

    private static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[*_`]", "").replaceAll("\\s+", " ").strip();
    }
}
