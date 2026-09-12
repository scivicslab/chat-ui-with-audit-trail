package com.scivicslab.chatui.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The range of the file system a conversation may read and write
 * ({@code FileAccessScope_260830_oo01}).
 *
 * <p>Reading and writing are bounded separately because widening them does different things:
 * a wider read range lets anyone who can open the chat page see those files, while a wider write
 * range lets a conversation overwrite them. A skill root is the case that needs the two apart —
 * a skill's own files must be readable for {@code load_skill}'s third loading level to work, and
 * must not be writable, since a skill's text is what steers the conversation that would be doing
 * the writing.</p>
 *
 * <p>Relative paths resolve against {@link #writeRoot()}, which is what the system prompt calls
 * the working directory.</p>
 */
public final class FileAccessScope {

    private final Path writeRoot;
    private final List<Path> readRoots;

    /**
     * @param writeRoot      the only directory {@code write} may write into, and the directory
     *                       relative paths resolve against
     * @param extraReadRoots directories {@code read} may read in addition to {@code writeRoot}
     */
    public FileAccessScope(Path writeRoot, List<Path> extraReadRoots) {
        this.writeRoot = writeRoot.toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        roots.add(this.writeRoot);
        for (Path root : extraReadRoots) {
            Path normalized = root.toAbsolutePath().normalize();
            if (!roots.contains(normalized)) roots.add(normalized);
        }
        this.readRoots = List.copyOf(roots);
    }

    /**
     * The scope a conversation has when nothing is configured: read and write confined to the
     * directory the process was started in, which is what this system did before the range became
     * configurable.
     *
     * @return a scope rooted at the process's current directory
     */
    public static FileAccessScope processDirectory() {
        return new FileAccessScope(Path.of("").toAbsolutePath(), List.of());
    }

    /** @return the only writable directory */
    public Path writeRoot() {
        return writeRoot;
    }

    /** @return every readable directory, the writable one first */
    public List<Path> readRoots() {
        return readRoots;
    }

    /**
     * @param realPath a path with symlinks already resolved
     * @return whether {@code read} may return that path's contents
     */
    public boolean canRead(Path realPath) {
        for (Path root : readRoots) {
            if (startsWithReal(realPath, root)) return true;
        }
        return false;
    }

    /**
     * @param path an absolute, normalized path — checked before anything is created, so it need
     *             not exist yet
     * @return whether {@code write} may create or overwrite it
     */
    public boolean canWrite(Path path) {
        return path.startsWith(writeRoot);
    }

    /**
     * @param realPath a path with symlinks already resolved
     * @return the readable directory containing it, or {@code null} if none does. Used to label
     *         a directory read's files relative to the root they belong to, rather than to
     *         whichever directory happened to be asked for.
     */
    public Path matchingReadRoot(Path realPath) {
        for (Path root : readRoots) {
            if (startsWithReal(realPath, root)) return root;
        }
        return null;
    }

    /** @return the readable directories, for the sentence the conversation is given */
    public String describeReadRoots() {
        List<String> parts = new ArrayList<>();
        for (Path root : readRoots) parts.add(root.toString());
        return String.join(", ", parts);
    }

    /** Compares against a root's real path, so a symlinked root still matches. */
    private static boolean startsWithReal(Path realPath, Path root) {
        try {
            return realPath.startsWith(root.toRealPath());
        } catch (IOException e) {
            return realPath.startsWith(root);
        }
    }
}
