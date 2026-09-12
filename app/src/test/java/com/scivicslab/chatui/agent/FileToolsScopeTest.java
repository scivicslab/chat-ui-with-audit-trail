package com.scivicslab.chatui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for what the two file tools tell a conversation about what they just did. No
 * server and no agent loop: a temporary directory stands in for the working directory.
 *
 * <p>Both cases here come from one live failure. A conversation was asked to read a directory of
 * standards and write a restructured set beside it. The directory read returned 105 000 characters
 * into a 20 000 character budget, so it saw the first document and the tail of the last and had no
 * way to name the ones it had missed; and every write reported success while landing under a root
 * the conversation had never been told about.</p>
 */
class FileToolsScopeTest {

    private static FileAccessScope scopeAt(Path root) {
        return new FileAccessScope(root, List.of());
    }

    /** The paths come first, so they survive the head of a truncated observation. */
    @Test
    void aDirectoryReadListsEveryPathBeforeAnyContent() throws IOException {
        Path root = Files.createTempDirectory("read-manifest");
        Path dir = Files.createDirectories(root.resolve("docs/standards"));
        Files.writeString(dir.resolve("a.md"), "first document");
        Files.writeString(dir.resolve("b.md"), "second document");

        String out = FileReadTool.read(scopeAt(root), "docs/standards");

        assertTrue(out.startsWith("docs/standards contains 2 readable files."), out);
        int files = out.indexOf("FILES:");
        int contents = out.indexOf("CONTENTS:");
        assertTrue(files >= 0 && contents > files, "the manifest must precede the contents: " + out);
        assertTrue(out.indexOf("docs/standards/a.md") < contents, out);
        assertTrue(out.indexOf("docs/standards/b.md") < contents, out);
        assertTrue(out.contains("first document") && out.contains("second document"), out);
    }

    /** Stopping early says how many of how many, and what to do about the rest. */
    @Test
    void stoppingEarlySaysHowManyOfHowManyWereIncluded() throws IOException {
        Path root = Files.createTempDirectory("read-cap");
        Path dir = Files.createDirectories(root.resolve("docs"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("doc" + i + ".md"), "x".repeat(100));
        }

        String out = FileReadTool.read(scopeAt(root), "docs", 150L, 1000);

        assertTrue(out.contains("contains 5 readable files"), out);
        assertTrue(out.contains("of 5 files"), out);
        assertTrue(out.contains("Read the rest individually"), out);
        // Every path is still named even though most contents were left out.
        for (int i = 0; i < 5; i++) {
            assertTrue(out.contains("docs/doc" + i + ".md"), "missing path " + i + ": " + out);
        }
    }

    /** An empty directory says so rather than producing a manifest of nothing. */
    @Test
    void anEmptyDirectorySaysSo() throws IOException {
        Path root = Files.createTempDirectory("read-empty");
        Files.createDirectories(root.resolve("docs"));

        assertEquals("(no readable files under docs)", FileReadTool.read(scopeAt(root), "docs"));
    }

    /** The confirmation names where the file actually is, not where some root made it relative. */
    @Test
    void aWriteConfirmationNamesTheAbsolutePath() throws IOException {
        Path root = Files.createTempDirectory("write-abs").toRealPath();

        String out = FileWriteTool.write(scopeAt(root), "docs/new/x.md", "# hello");

        Path written = root.resolve("docs/new/x.md");
        assertEquals("wrote " + written + " (7 chars)", out);
        assertEquals("# hello", Files.readString(written));
    }

    /** A path outside the writable range is refused, and the refusal names the range. */
    @Test
    void aWriteOutsideTheRangeIsRefusedAndNamesTheRange() throws IOException {
        Path root = Files.createTempDirectory("write-outside").toRealPath();
        Path outside = Files.createTempDirectory("elsewhere").toRealPath();

        String out = FileWriteTool.write(scopeAt(root), outside.resolve("x.md").toString(), "no");

        assertTrue(out.startsWith("error: path is outside the writable directory"), out);
        assertTrue(out.contains(root.toString()), out);
        assertTrue(Files.notExists(outside.resolve("x.md")));
    }
}
