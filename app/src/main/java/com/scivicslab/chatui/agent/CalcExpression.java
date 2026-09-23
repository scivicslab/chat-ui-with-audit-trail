package com.scivicslab.chatui.agent;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a {@code calc} argument is arithmetic before it reaches JShell.
 *
 * <p>{@code calc} exists so that a model does not have to multiply 23 by 47 in its head. It is
 * implemented on {@code JShellCalculator}, which hands the string to {@code JShell.eval} as it
 * stands — and JShell evaluates Java, in this process, with this process's rights. Measured:
 * {@code Files.writeString(...)} wrote a file outside every {@code FileAccessScope} the file tools
 * enforce, and {@code new ProcessBuilder("/bin/sh", "-c", …).start()} ran a shell. A conversation
 * that has no {@code bash} tool had one all along, by another name.</p>
 *
 * <p>So the argument is read here first, and only arithmetic goes through: numbers, the five
 * operators, parentheses, commas, and the {@link #MATH_MEMBERS} of {@code java.lang.Math}. What is
 * allowed is listed rather than what is forbidden — a list of forbidden forms is a list of the
 * ones that were thought of.</p>
 */
public final class CalcExpression {

    /** The members of {@code Math} an arithmetic expression may name. */
    private static final Set<String> MATH_MEMBERS = Set.of(
            "abs", "ceil", "floor", "round", "rint", "signum",
            "sqrt", "cbrt", "pow", "exp", "expm1", "log", "log10", "log1p",
            "min", "max", "hypot", "floorDiv", "floorMod",
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh", "cosh", "tanh",
            "toRadians", "toDegrees", "PI", "E");

    /**
     * One token of an arithmetic expression: whitespace, a number, a member of {@code Math}, or
     * one of the punctuation marks. Anything the scan cannot cover is not arithmetic.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "\\s+"                                     // whitespace
            + "|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"    // 47, 3.5, 1e9, 2.5E-3
            + "|Math\\.([A-Za-z0-9]+)"                 // Math.sqrt, Math.PI
            + "|[-+*/%(),]");                          // operators, parentheses, argument commas

    /** Long enough for any arithmetic a conversation needs; short enough to read in a log. */
    private static final int MAX_LENGTH = 200;

    private CalcExpression() {
    }

    /**
     * Reports why {@code expression} is not arithmetic.
     *
     * @param expression the {@code calc} argument as the model wrote it
     * @return {@code null} when it may be evaluated, otherwise the message to return to the model
     */
    public static String reject(String expression) {
        if (expression == null || expression.isBlank()) {
            return "error: calc needs an expression, e.g. 23*47 or Math.sqrt(16)";
        }
        if (expression.length() > MAX_LENGTH) {
            return "error: calc takes an arithmetic expression of at most " + MAX_LENGTH
                    + " characters; this one is " + expression.length();
        }
        Matcher m = TOKEN.matcher(expression);
        int at = 0;
        boolean sawNumberOrMath = false;
        while (at < expression.length()) {
            if (!m.find(at) || m.start() != at) {
                return notArithmetic(expression, at);
            }
            String member = m.group(1);
            if (member != null && !MATH_MEMBERS.contains(member)) {
                return "error: calc knows Math." + String.join(", Math.", sorted())
                        + "; it does not know Math." + member;
            }
            if (member != null || Character.isDigit(expression.charAt(at))) sawNumberOrMath = true;
            at = m.end();
        }
        if (!sawNumberOrMath) return notArithmetic(expression, 0);
        return null;
    }

    /** The message for a character that arithmetic has no place for. */
    private static String notArithmetic(String expression, int at) {
        return "error: calc evaluates arithmetic only — numbers, + - * / %, parentheses and Math.*."
                + " It stopped at '" + expression.charAt(at) + "' (position " + at + ")."
                + " It is not a way to run Java.";
    }

    /** The allowed members, in a stable order, for the message above. */
    private static java.util.List<String> sorted() {
        return MATH_MEMBERS.stream().sorted().toList();
    }
}
