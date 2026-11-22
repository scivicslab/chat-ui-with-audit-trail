package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Quoting and refusing remote strings (tasks/todo.md, "Stage 11 spec"). */
class RemoteTextTest {

    @Test
    void aSingleQuoteBecomesQuoteBackslashQuoteQuote() {
        assertEquals("'a'\\''b'", RemoteText.quote("a'b"));
        assertEquals("''", RemoteText.quote(""));
    }

    /** A real POSIX shell reads each quoted value back as exactly the value: nothing is expanded or run. */
    @Test
    void shellSyntaxStaysLiteralInsideTheQuotes() throws Exception {
        for (String value : List.of("a'b", "$(id)", "`id`", "; rm -rf ~", "'; touch /tmp/x; '", "$HOME \\ \" *")) {
            Process p = new ProcessBuilder("sh", "-c", "printf %s " + RemoteText.quote(value)).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            assertEquals(value, out, "quoted " + RemoteText.quote(value));
        }
    }

    @Test
    void nulAndLineBreaksAreRefused() {
        for (String value : List.of("a\u0000b", "a\nb", "a\rb")) {
            String refusal = RemoteText.refusal("path", value);
            assertNotNull(refusal, value);
            assertTrue(refusal.startsWith("error: path "), refusal);
        }
        assertNull(RemoteText.refusal("path", "/home/u/a b/$(id)'x"));
    }
}
