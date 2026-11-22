package com.scivicslab.chatui.agent;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The {@code remove} tool: moves one file into a trash directory under the working directory.
 *
 * <p>A conversation could write files and never take one away, so leftovers accumulated and a
 * person had to clear them by hand. What it gets is not deletion. The file is moved to
 * {@code .trash/<yyyyMMdd-HHmmss>/} keeping the path it had below the working directory, so two
 * removals of the same name do not land on each other and the original place is still readable
 * from where it sits.</p>
 *
 * <p>Deletion is not offered because the damage from a wrong one cannot be undone, and a
 * conversation reaches for a tool on the strength of a name it read seconds ago. Moving costs the
 * same and is answerable. Clearing the trash is left to a person: a tool that empties it would put
 * the irreversible step back.</p>
 *
 * <p>One path per call, and only a file. A directory is refused rather than taken with its
 * contents: recursive removal is the operation that turns one mistaken path into the loss of
 * everything below it.</p>
 */
public final class FileRemoveTool {

    /** The directory removals are kept in, below the working directory. */
    public static final String TRASH = ".trash";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private FileRemoveTool() {
    }

    /**
     * Moves {@code path} (relative to the working directory) into the trash.
     *
     * @param scope the conversation's file range; the move must start and end inside its write root
     * @param path  the file to take away, as the model wrote it
     * @return where it was put, or an {@code error: ...} the conversation feeds back as its
     *         observation
     */
    public static String remove(FileAccessScope scope, String path) {
        return remove(scope, path, LocalDateTime.now());
    }

    /**
     * The same, with the time named, so a test can say where the file must land.
     *
     * @param scope the conversation's file range
     * @param path  the file to take away
     * @param now   the time the trash directory is named after
     * @return where it was put, or an {@code error: ...}
     */
    static String remove(FileAccessScope scope, String path, LocalDateTime now) {
        if (path == null || path.isBlank()) return "error: path required";
        try {
            Path base = scope.writeRoot();
            Path target = base.resolve(FileReadTool.expandHome(path.trim())).normalize();

            if (!scope.canWrite(target)) {
                return "error: path is outside the writable directory (" + base + "): " + path;
            }
            if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return "error: no such file: " + path;
            }
            if (Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return "error: remove takes one file, not a directory: " + path;
            }
            // After the checks above the parent exists; its real path catches a symlinked
            // directory that points out of the working directory.
            Path parent = target.getParent();
            if (parent != null && !parent.toRealPath().startsWith(base)) {
                return "error: path escapes working directory: " + path;
            }
            Path trashRoot = base.resolve(TRASH);
            if (target.startsWith(trashRoot)) {
                return "error: already in " + TRASH + ": " + path;
            }

            Path kept = trashRoot.resolve(now.format(STAMP)).resolve(base.relativize(target));
            Files.createDirectories(kept.getParent());
            move(target, kept);
            return "moved " + target + " to " + kept
                    + " (nothing was deleted; clear " + trashRoot + " yourself when you are sure)";
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /** Moves atomically where the file system allows it, and copies across devices where not. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
