package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code remove} takes away, where it puts it, and what it refuses.
 *
 * <p>The refusals are the point of the tool's shape: a conversation reaches for a tool on the
 * strength of a name it read seconds ago, so the damage a wrong call can do is bounded here rather
 * than left to the caller's judgement.</p>
 */
class FileRemoveToolTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 23, 14, 5, 6);
    private static final String STAMP = "20260923-140506";

    private FileAccessScope scope(Path root) {
        return new FileAccessScope(root, List.of());
    }

    private Path file(Path dir, String rel, String body) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void movesTheFileIntoTheTrashKeepingItsPath(@TempDir Path root) throws IOException {
        Path doc = file(root, "notes/leftover.md", "draft");

        String out = FileRemoveTool.remove(scope(root), "notes/leftover.md", AT);

        Path kept = root.resolve(".trash").resolve(STAMP).resolve("notes/leftover.md");
        assertTrue(Files.exists(kept), out);
        assertEquals("draft", Files.readString(kept));
        assertFalse(Files.exists(doc), "the file left its place");
        assertTrue(out.startsWith("moved "), out);
        assertTrue(out.contains("nothing was deleted"), out);
    }

    /** Two files of the same name from different places must not land on each other. */
    @Test
    void keepsTwoRemovalsOfTheSameNameApart(@TempDir Path root) throws IOException {
        file(root, "a/x.md", "first");
        file(root, "b/x.md", "second");

        FileRemoveTool.remove(scope(root), "a/x.md", AT);
        FileRemoveTool.remove(scope(root), "b/x.md", AT);

        Path trash = root.resolve(".trash").resolve(STAMP);
        assertEquals("first", Files.readString(trash.resolve("a/x.md")));
        assertEquals("second", Files.readString(trash.resolve("b/x.md")));
    }

    /** One mistaken path must not become the loss of everything below it. */
    @Test
    void refusesADirectory(@TempDir Path root) throws IOException {
        file(root, "tree/deep/kept.md", "keep me");

        String out = FileRemoveTool.remove(scope(root), "tree", AT);

        assertTrue(out.startsWith("error: "), out);
        assertTrue(out.contains("not a directory"), out);
        assertTrue(Files.exists(root.resolve("tree/deep/kept.md")), "the tree is untouched");
    }

    @Test
    void refusesAPathOutsideTheWritableDirectory(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        Path outside = file(elsewhere, "precious.md", "not yours");

        String out = FileRemoveTool.remove(scope(root), "../" + elsewhere.getFileName() + "/precious.md", AT);

        assertTrue(out.startsWith("error: "), out);
        assertTrue(Files.exists(outside), "the file outside the scope is untouched");
    }

    @Test
    void refusesAFileThatIsNotThere(@TempDir Path root) {
        String out = FileRemoveTool.remove(scope(root), "never-existed.md", AT);
        assertTrue(out.startsWith("error: no such file"), out);
    }

    @Test
    void refusesAnEmptyPath(@TempDir Path root) {
        assertTrue(FileRemoveTool.remove(scope(root), "  ", AT).startsWith("error: "));
        assertTrue(FileRemoveTool.remove(scope(root), null, AT).startsWith("error: "));
    }

    /** Emptying the trash is a person's job, so the tool cannot be turned on the trash itself. */
    @Test
    void refusesToTakeSomethingOutOfTheTrash(@TempDir Path root) throws IOException {
        file(root, "gone.md", "x");
        FileRemoveTool.remove(scope(root), "gone.md", AT);

        String out = FileRemoveTool.remove(scope(root), ".trash/" + STAMP + "/gone.md", AT);

        assertTrue(out.startsWith("error: already in .trash"), out);
        assertTrue(Files.exists(root.resolve(".trash").resolve(STAMP).resolve("gone.md")));
    }

    /** A symlinked directory pointing out of the scope must not carry a removal out with it. */
    @Test
    void refusesAPathThatLeavesThroughASymlink(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        Path outside = file(elsewhere, "precious.md", "not yours");
        try {
            Files.createSymbolicLink(root.resolve("link"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            return;   // a file system without symlinks has nothing to check here
        }

        String out = FileRemoveTool.remove(scope(root), "link/precious.md", AT);

        assertTrue(out.startsWith("error: "), out);
        assertTrue(Files.exists(outside), "the file the link pointed at is untouched");
    }
}
