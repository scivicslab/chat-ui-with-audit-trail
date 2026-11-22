package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed {@code result} line ends the prompt, as a successful one does. On 2026-11-23 the CLI answered prompts with
 * the weekly-limit refusal (a {@code rate_limit_event}, a synthetic assistant message, and a {@code result} with
 * {@code is_error} and {@code api_error usage_limit_reached}) and stayed alive. The refusal was read as an
 * {@code error} event only, the prompt went on waiting for a {@code result}, and the conversation stayed on that one
 * model call for 43 minutes, until its process was killed by hand.
 */
class CliFailedResultEndsTheTurnTest {

    /** The refusal, with the event shapes the CLI wrote, for the first prompt; a plain answer for the second. */
    private static Path fakeCli(Path dir) throws Exception {
        String limit = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"rejected\",\"rateLimitType\":\"seven_day\"},"
                + "\"session_id\":\"s-limit\"}";
        String synthetic = "{\"type\":\"assistant\",\"message\":{\"model\":\"<synthetic>\",\"role\":\"assistant\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"You have hit your limit\"}]},\"session_id\":\"s-limit\",\"error\":\"rate_limit\","
                + "\"is_api_error_message\":true,\"api_error_status\":429,\"api_error\":\"usage_limit_reached\"}";
        String refused = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,"
                + "\"api_error\":\"usage_limit_reached\",\"result\":\"You have hit your limit\",\"session_id\":\"s-limit\"}";
        String answer = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]},"
                + "\"session_id\":\"s-limit\"}";
        String done = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\",\"session_id\":\"s-limit\"}";
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\n"
                + "read line\n"
                + "echo '{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-limit\"}'\n"
                + "echo '" + limit + "'\n"
                + "echo '" + synthetic + "'\n"
                + "echo '" + refused + "'\n"
                + "read line\n"
                + "echo '" + answer + "'\n"
                + "echo '" + done + "'\n"
                // Alive and silent from here on, as the CLI was.
                + "while read line; do :; done\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    /** Runs one prompt on a virtual thread; {@code -2} while it has not returned after five seconds. */
    private static int promptWithin5s(CliProcess process, String prompt, List<StreamEvent> events) throws Exception {
        AtomicInteger code = new AtomicInteger(-2);
        Thread t = Thread.ofVirtual().start(() -> {
            try { code.set(process.sendPrompt(prompt, events::add)); } catch (Exception e) { code.set(-999); }
        });
        t.join(5_000);
        if (t.isAlive()) {
            process.cancel();
            t.join(5_000);
            return -2;
        }
        return code.get();
    }

    @Test
    void theLimitRefusal_endsThePrompt_andTheNextPromptIsAnswered(@TempDir Path dir) throws Exception {
        CliProcess process = new CliProcess(fakeCli(dir).toString(), "UNUSED_KEY", CliConfig.defaults(null));
        try {
            List<StreamEvent> first = new CopyOnWriteArrayList<>();
            assertEquals(0, promptWithin5s(process, "first", first),
                    "the prompt ended at the CLI's failed result, not after 5 s or never: " + first);
            assertTrue(first.stream().anyMatch(e -> "error".equals(e.type())
                            && "You have hit your limit".equals(e.content())),
                    "the refusal still reaches the caller as an error: " + first);
            assertEquals("result", first.get(first.size() - 1).type(), "the last event is the result: " + first);
            assertEquals("s-limit", process.getLastSessionId(), "the session is kept for --resume");

            List<StreamEvent> second = new CopyOnWriteArrayList<>();
            assertEquals(0, promptWithin5s(process, "second", second), "the next prompt is answered: " + second);
            assertTrue(second.stream().anyMatch(e -> "assistant".equals(e.type()) && "ok".equals(e.content())), second.toString());
            assertFalse(second.stream().anyMatch(e -> "error".equals(e.type())), second.toString());
        } finally {
            process.cancel();
        }
    }
}
