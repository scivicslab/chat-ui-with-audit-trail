package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileReadTool — several paths in one call")
class FileReadToolMultiPathTest {

    @TempDir
    Path root;

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void onePath_readsThatFileWithNoManifest() throws IOException {
        write("a/one.md", "ONE");

        String out = FileReadTool.read(root, "a/one.md");

        assertEquals("ONE", out);
    }

    /**
     * The case the single-path form could not serve: three files in three directories. Reading
     * their common parent would work but would also pull in everything else under it.
     */
    @Test
    void severalPaths_readEachInOrder() throws IOException {
        write("a/one.md", "ONE");
        write("b/two.md", "TWO");
        write("c/three.md", "THREE");

        String out = FileReadTool.read(root, "a/one.md\nb/two.md\nc/three.md");

        assertTrue(out.contains("ONE"));
        assertTrue(out.contains("TWO"));
        assertTrue(out.contains("THREE"));
        assertTrue(out.indexOf("ONE") < out.indexOf("TWO"), "kept in the order asked for");
        assertTrue(out.indexOf("TWO") < out.indexOf("THREE"));
    }

    /** Every requested path is named before any content, so a cut-short reply still says what is missing. */
    @Test
    void severalPaths_listEveryPathBeforeTheContents() throws IOException {
        write("a/one.md", "ONE");
        write("b/two.md", "TWO");

        String out = FileReadTool.read(root, "a/one.md\nb/two.md");

        assertTrue(out.contains("2 paths requested"));
        assertTrue(out.indexOf("PATHS:") < out.indexOf("CONTENTS:"));
        assertTrue(out.indexOf("a/one.md") < out.indexOf("CONTENTS:"), "listed in the manifest");
    }

    /** One unreadable path must not lose the others: its error is reported in its own place. */
    @Test
    void oneMissingPath_doesNotStopTheRest() throws IOException {
        write("a/one.md", "ONE");

        String out = FileReadTool.read(root, "a/one.md\nnope.md");

        assertTrue(out.contains("ONE"));
        assertTrue(out.contains("error: not found: nope.md"));
    }

    /** Blank lines are how a model spells a list, not a request to read nothing. */
    @Test
    void blankLinesAreIgnored() {
        assertEquals(List.of("a.md", "b.md"), FileReadTool.splitPaths("\n a.md \n\n b.md \n"));
        assertEquals(List.of("a.md"), FileReadTool.splitPaths("a.md"));
    }

    @Test
    void noPath_isAnError() {
        assertEquals("error: path required", FileReadTool.read(root, "   "));
    }

    /**
     * The budget covers the call as a whole, not each path, and the reply says where it stopped —
     * the cap is what bounds a read, not the number of paths the call accepts.
     */
    @Test
    void theCapCoversTheWholeCallAndSaysWhereItStopped() throws IOException {
        write("a.md", "AAAA");
        write("b.md", "BBBB");
        write("c.md", "CCCC");

        String out = FileReadTool.read(new FileAccessScope(root, List.of()), "a.md\nb.md\nc.md", 6, 1000);

        assertTrue(out.contains("AAAA"));
        assertTrue(out.contains("stopped after"), "the truncation is announced, not silent");
        assertTrue(out.contains("c.md"), "the path it never reached is still named in the manifest");
        assertFalse(out.contains("CCCC"), "its contents were not read");
    }
}
