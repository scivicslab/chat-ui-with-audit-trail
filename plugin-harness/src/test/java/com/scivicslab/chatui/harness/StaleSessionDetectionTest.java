package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.agent.ToolSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line the CLI prints when {@code --resume} names a session it has deleted must reach the
 * stale-session retry rather than the conversation as an error. It once arrived as "Unknown error"
 * and the retry never ran.
 */
class StaleSessionDetectionTest {

    @Test
    void dispatch_resumeOfDeletedSession_marksTheSessionStaleAndShowsNoError(@TempDir Path dir) {
        ClaudeCodeProvider provider = new ClaudeCodeProvider(
                new HarnessSettings("bypassPermissions", dir, 0, "sonnet", null),
                "project1", "01", null, ToolSet.HARNESS);
        String line = "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                + "\"session_id\":\"s1\",\"errors\":[\"No conversation found with session ID: s1\"]}";
        List<ChatEvent> shown = new ArrayList<>();
        boolean[] stale = {false};

        for (StreamEvent e : new StreamEventParser().parse(line)) {
            provider.dispatch(e, shown::add, stale, new boolean[]{false});
        }

        assertTrue(stale[0], "the retry with a new session must run");
        assertTrue(shown.isEmpty(), "nothing is shown to the person: " + shown);
    }
}
