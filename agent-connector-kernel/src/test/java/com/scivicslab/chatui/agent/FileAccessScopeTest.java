package com.scivicslab.chatui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the read/write range and the two tools that obey it
 * ({@code FileAccessScope_260830_oo01}).
 */
class FileAccessScopeTest {

    @Test
    void readReachesAReadOnlyRootThatWriteCannot(@TempDir Path work, @TempDir Path skills) throws IOException {
        Files.writeString(skills.resolve("SKILL.md"), "the skill body");
        FileAccessScope scope = new FileAccessScope(work, List.of(skills));

        assertEquals("the skill body", FileReadTool.read(scope, skills.resolve("SKILL.md").toString()));

        String refusal = FileWriteTool.write(scope, skills.resolve("SKILL.md").toString(), "rewritten");
        assertTrue(refusal.startsWith("error:"), refusal);
        assertEquals("the skill body", Files.readString(skills.resolve("SKILL.md")),
                "a read-only root is not writable, so a conversation cannot rewrite its own instructions");
    }

    @Test
    void writeStaysInsideTheWriteRoot(@TempDir Path work, @TempDir Path elsewhere) {
        FileAccessScope scope = new FileAccessScope(work, List.of(elsewhere));

        String outcome = FileWriteTool.write(scope, "notes/today.txt", "hello");
        assertFalse(outcome.startsWith("error:"), outcome);
        assertTrue(Files.exists(work.resolve("notes/today.txt")),
                "a relative path resolves against the write root");
    }

    @Test
    void aPathInNoRootIsRefusedForBothTools(@TempDir Path work) throws IOException {
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("secret.txt"), "not for the web side");
        FileAccessScope scope = new FileAccessScope(work, List.of());

        assertTrue(FileReadTool.read(scope, outside.resolve("secret.txt").toString())
                .startsWith("error:"));
        assertTrue(FileWriteTool.write(scope, outside.resolve("secret.txt").toString(), "x")
                .startsWith("error:"));
        assertEquals("not for the web side", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    void theWriteRootIsAlwaysReadableAndListedFirst(@TempDir Path work, @TempDir Path skills) {
        FileAccessScope scope = new FileAccessScope(work, List.of(skills));

        assertEquals(List.of(work, skills), scope.readRoots());
        assertTrue(scope.describeReadRoots().startsWith(work.toString()));
    }

    @Test
    void aRootRepeatedInTheReadListIsNotListedTwice(@TempDir Path work) {
        FileAccessScope scope = new FileAccessScope(work, List.of(work, work));

        assertEquals(List.of(work), scope.readRoots());
    }
}
