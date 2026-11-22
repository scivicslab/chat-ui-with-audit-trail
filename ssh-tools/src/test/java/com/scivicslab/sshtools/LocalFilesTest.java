package com.scivicslab.sshtools;

import com.scivicslab.chatui.agent.FileAccessScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local paths stay under the working directory (tasks/todo.md, "Stage 11 spec", P3). */
class LocalFilesTest {

    @TempDir
    Path base;

    Path work;
    Path outside;
    FileAccessScope scope;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(base.resolve("work"));
        outside = Files.createDirectory(base.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "secret");
        scope = new FileAccessScope(work, List.of());
    }

    @Test
    void writingRefusesDotDotAbsolutePathsOutsideAndSymlinksLeadingOut() throws Exception {
        Files.createSymbolicLink(work.resolve("out"), outside);
        Files.createSymbolicLink(work.resolve("link"), outside.resolve("secret"));
        for (String path : List.of("../x", "a/../../x", "/etc/passwd", outside.resolve("new").toString(), "out/new",
                "link", "")) {
            assertThrows(IllegalArgumentException.class, () -> LocalFiles.writable(scope, path, true), path);
        }
    }

    @Test
    void anExistingFileIsReplacedOnlyWhenTheCallSaysSo() throws Exception {
        Files.writeString(work.resolve("have"), "x");
        assertThrows(IllegalArgumentException.class, () -> LocalFiles.writable(scope, "have", false));
        assertEquals(work.resolve("have"), LocalFiles.writable(scope, "have", true));
        Files.createDirectory(work.resolve("dir"));
        assertThrows(IllegalArgumentException.class, () -> LocalFiles.writable(scope, "dir", true));
    }

    @Test
    void aNestedNewPathInsideIsAccepted() {
        assertEquals(work.resolve("a/b/c.txt"), LocalFiles.writable(scope, "a/b/c.txt", false));
        assertEquals(work.resolve("a/b/c.txt"), LocalFiles.writable(scope, work.resolve("a/b/c.txt").toString(), false));
        assertTrue(Files.notExists(work.resolve("a")), "checking creates nothing");
    }

    @Test
    void readingTakesOnlyRegularFilesInside() throws Exception {
        Files.writeString(work.resolve("in.txt"), "x");
        Files.createSymbolicLink(work.resolve("link"), outside.resolve("secret"));
        Files.createSymbolicLink(work.resolve("out"), outside);
        Files.createDirectory(work.resolve("dir"));
        assertEquals(work.resolve("in.txt").toRealPath(), LocalFiles.readable(scope, "in.txt"));
        for (String path : List.of("link", "out/secret", "../outside/secret", "/etc/passwd", "dir", "missing", "")) {
            assertThrows(IllegalArgumentException.class, () -> LocalFiles.readable(scope, path), path);
        }
    }
}
