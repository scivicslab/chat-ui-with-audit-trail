package com.scivicslab.chatui.harness;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A CLI process that ends in the middle of a prompt, before it wrote the prompt's {@code result}, fails the prompt
 * with its exit code, as soon as its output closes ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}). It used
 * to return as if the prompt had ended, up to 60 seconds later, with whatever text had streamed so far.
 */
@Tag("TurnWithoutAnAnswerTellsTheAsker_261119_oo01")
class CliEndedWithoutResultTest {

    /** Reads the prompt, starts a tool call, and exits with 143 (SIGTERM) before any result, as a killed CLI does. */
    private static Path fakeCli(Path dir, String exit) throws Exception {
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\n"
                + "read line\n"
                + "echo '{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Rendering frames 480-599.\"}]}}'\n"
                + exit + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    @Test
    void sendPrompt_processEndsWithoutAResult_failsWithTheExitCodeAtOnce(@TempDir Path dir) throws Exception {
        CliProcess process = new CliProcess(fakeCli(dir, "exit 143").toString(), "UNUSED_KEY", CliConfig.defaults(null));
        List<StreamEvent> events = new CopyOnWriteArrayList<>();
        long started = System.currentTimeMillis();

        java.io.IOException ended = assertThrows(java.io.IOException.class,
                () -> process.sendPrompt("render the next range", events::add));

        assertTrue(ended.getMessage().contains("ended without a result (exit code 143)"), ended.getMessage());
        assertTrue(System.currentTimeMillis() - started < 30_000,
                "noticed when the output closed, not after the 60-second poll");
        assertTrue(events.stream().anyMatch(e -> "assistant".equals(e.type())), "what it wrote still reached the caller: " + events);
    }
}
