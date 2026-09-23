package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What {@code calc} may be handed, and what it may not.
 *
 * <p>The ones it may not are the measured escapes: on this build, before the check existed,
 * {@code Files.writeString} wrote outside every {@code FileAccessScope} and
 * {@code new ProcessBuilder("/bin/sh", …).start()} ran a shell — through the tool whose
 * description says it evaluates arithmetic.</p>
 */
class CalcExpressionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "23*47",
            "23 * 47",
            "(1 + 2) * 3.5",
            "100 % 7",
            "-5 + 3",
            "1e3 / 2",
            "2.5E-3 * 4",
            "Math.sqrt(16)",
            "Math.pow(2, 10)",
            "Math.PI * 2",
            "Math.max(3, Math.min(9, 7))",
            "Math.round(1.0e9 / 7)"
    })
    void arithmetic_goes_through(String expression) {
        assertNull(CalcExpression.reject(expression), expression);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new ProcessBuilder(\"/bin/sh\", \"-c\", \"id\").start().waitFor()",
            "java.nio.file.Files.writeString(java.nio.file.Path.of(\"/tmp/x\"), \"y\")",
            "System.exit(0)",
            "Runtime.getRuntime().exec(\"id\")",
            "1; System.exit(0)",
            "var x = 1",
            "String.valueOf(1)",
            "foo(1)",
            "System.getenv(\"HOME\")",
            "new java.net.URL(\"http://example.com\").openStream()",
            "Thread.sleep(100000)",
            "\"quoted\"",
            "Math.class"
    })
    void java_does_not(String expression) {
        assertNotNull(CalcExpression.reject(expression), expression);
    }

    /** A name that looks like arithmetic but is not one of Math's own members. */
    @Test void an_unknown_Math_member_is_refused() {
        String out = CalcExpression.reject("Math.evil(1)");
        assertNotNull(out);
        assertTrue(out.contains("Math.evil"), out);
    }

    @Test void nothing_at_all_is_refused() {
        assertNotNull(CalcExpression.reject(null));
        assertNotNull(CalcExpression.reject("   "));
    }

    /** Punctuation alone is not arithmetic, however well it scans. */
    @Test void punctuation_without_a_number_is_refused() {
        assertNotNull(CalcExpression.reject("(())"));
        assertNotNull(CalcExpression.reject("+-*/"));
    }

    @Test void an_expression_longer_than_the_limit_is_refused() {
        assertNotNull(CalcExpression.reject("1+".repeat(200) + "1"));
    }

    /** The refusal tells the model what calc is for, so it stops looking for a way in. */
    @Test void the_refusal_says_what_calc_is() {
        String out = CalcExpression.reject("new ProcessBuilder(\"sh\").start()");
        assertNotNull(out);
        assertTrue(out.startsWith("error: "), out);
        assertTrue(out.contains("arithmetic"), out);
    }
}
