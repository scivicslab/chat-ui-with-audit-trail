package com.scivicslab.chatui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code bash} returns, and the two things it will not let happen: a conversation waiting on
 * a command that never ends, and a conversation handed more output than it can read.
 */
class ShellToolTest {

    @Test
    void returnsTheStatusAndTheOutput(@TempDir Path dir) {
        String out = ShellTool.run(dir, "echo hello");
        assertTrue(out.startsWith("exit 0\n"), out);
        assertTrue(out.contains("hello"), out);
    }

    @Test
    void reportsANonZeroStatus(@TempDir Path dir) {
        assertTrue(ShellTool.run(dir, "exit 3").startsWith("exit 3"), "the status is the answer");
    }

    /** One stream, so a failing command's reason arrives with whatever it managed to print. */
    @Test
    void bringsBackStandardErrorToo(@TempDir Path dir) {
        String out = ShellTool.run(dir, "echo out; echo err 1>&2");
        assertTrue(out.contains("out"), out);
        assertTrue(out.contains("err"), out);
    }

    @Test
    void startsInTheDirectoryItWasGiven(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("marker.txt"), "x");
        String out = ShellTool.run(dir, "ls");
        assertTrue(out.contains("marker.txt"), out);
    }

    /** A pipe is why the command is handed to a shell rather than started directly. */
    @Test
    void runsAPipeline(@TempDir Path dir) {
        String out = ShellTool.run(dir, "printf 'a\\nb\\nc\\n' | wc -l");
        assertTrue(out.contains("3"), out);
    }

    @Test
    void cutsOutputThatIsTooLongAndSaysSo(@TempDir Path dir) {
        String out = ShellTool.run(dir, "yes x | head -c 60000");
        assertTrue(out.contains("[truncated"), out);
        assertTrue(out.length() < ShellTool.MAX_OUTPUT + 200, "cut to the limit, not beyond: " + out.length());
    }

    @Test
    void refusesAnEmptyCommand(@TempDir Path dir) {
        assertEquals("error: command required", ShellTool.run(dir, "  "));
        assertEquals("error: command required", ShellTool.run(dir, null));
    }

    /** Nothing is typed at the command, so one that reads its input ends rather than waiting. */
    @Test
    void doesNotHangOnACommandThatReadsItsInput(@TempDir Path dir) {
        long started = System.currentTimeMillis();
        String out = ShellTool.run(dir, "cat");
        assertTrue(System.currentTimeMillis() - started < 10_000, "it came back: " + out);
        assertTrue(out.startsWith("exit 0"), out);
    }

    /** The working directory follows the property the body reads for it. */
    @Test
    void takesTheWorkingDirectoryFromTheConfiguredWriteRoot(@TempDir Path dir) {
        String before = System.getProperty("chat-ui.write-root");
        try {
            System.setProperty("chat-ui.write-root", dir.toString());
            assertEquals(dir.toRealPath(), ShellPlugin.workingDirectory().toRealPath());
        } catch (IOException e) {
            throw new AssertionError(e);
        } finally {
            if (before == null) System.clearProperty("chat-ui.write-root");
            else System.setProperty("chat-ui.write-root", before);
        }
    }

    /** The tool the model sees: one name, one argument, described in the same voice as the rest. */
    @Test
    void thePluginOffersOneToolCalledBash() {
        ShellPlugin plugin = new ShellPlugin();
        assertEquals("shell", plugin.id());
        assertEquals(1, plugin.tools().size());
        assertEquals("bash", plugin.tools().get(0).name());
        assertTrue(plugin.tools().get(0).description().startsWith("- bash(command):"),
                plugin.tools().get(0).description());
        assertFalse(plugin.tools().get(0).returnsPages(), "its output is not a set of pages");
    }

    @Test
    void runsThroughThePluginsCallShape(@TempDir Path dir) {
        String before = System.getProperty("chat-ui.write-root");
        try {
            System.setProperty("chat-ui.write-root", dir.toString());
            String out = new ShellPlugin().tools().get(0).execute("{\"command\": \"echo via-plugin\"}");
            assertTrue(out.contains("via-plugin"), out);
        } finally {
            if (before == null) System.clearProperty("chat-ui.write-root");
            else System.setProperty("chat-ui.write-root", before);
        }
    }
}
