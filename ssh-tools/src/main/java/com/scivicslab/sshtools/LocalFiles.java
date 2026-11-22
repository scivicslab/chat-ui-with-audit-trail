package com.scivicslab.sshtools;

import com.scivicslab.chatui.agent.FileAccessScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Where on the user's computer the file tools may read and write (tasks/todo.md, "Stage 11 spec", P3): only under the
 * working directory, {@link FileAccessScope#writeRoot()}. A relative path resolves against it.
 */
public final class LocalFiles {

    private LocalFiles() {
    }

    /**
     * @return the real path of a regular file inside the working directory, for {@code upload_file}
     * @throws IllegalArgumentException when the path is empty, leads out of the working directory, also through a
     *                                  symbolic link, or is not a regular file
     */
    public static Path readable(FileAccessScope scope, String localPath) {
        Path path = inside(scope, localPath);
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("local_path " + localPath + " does not exist");
        }
        if (!real.startsWith(realRoot(scope))) {
            throw new IllegalArgumentException("local_path " + localPath + " leads out of the working directory");
        }
        if (!Files.isRegularFile(real)) throw new IllegalArgumentException("local_path " + localPath + " is not a file");
        return real;
    }

    /**
     * @param overwrite whether an existing file may be replaced
     * @return the absolute path {@code download_file} may write; its missing parent directories are inside the
     *         working directory
     * @throws IllegalArgumentException when the path is empty, leads out of the working directory, also through a
     *                                  symbolic link, names a symbolic link or a directory, or names an existing file
     *                                  and {@code overwrite} is false
     */
    public static Path writable(FileAccessScope scope, String localPath, boolean overwrite) {
        Path path = inside(scope, localPath);
        Path root = scope.writeRoot();
        if (path.equals(root)) throw new IllegalArgumentException("local_path names the working directory itself");
        // Every directory between the root and the file that exists already must be a real directory, not a link:
        // a link there could lead out after this check.
        Path dir = root;
        for (Path part : root.relativize(path.getParent())) {
            dir = dir.resolve(part);
            if (Files.isSymbolicLink(dir)) {
                throw new IllegalArgumentException("local_path " + localPath + " goes through the symbolic link " + dir);
            }
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) break;
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("local_path " + localPath + ": " + dir + " is not a directory");
            }
        }
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("local_path " + localPath + " is a symbolic link");
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("local_path " + localPath + " is a directory");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw new IllegalArgumentException("local_path " + localPath
                    + " exists; call again with overwrite true to replace it");
        }
        return path;
    }

    /** @return the normalized absolute path, which must lie under the working directory as written */
    private static Path inside(FileAccessScope scope, String localPath) {
        if (localPath == null || localPath.isEmpty()) throw new IllegalArgumentException("local_path is empty");
        Path path;
        try {
            path = scope.writeRoot().resolve(localPath).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("local_path is not a path: " + e.getMessage());
        }
        if (!path.startsWith(scope.writeRoot())) {
            throw new IllegalArgumentException("local_path " + localPath + " is outside the working directory "
                    + scope.writeRoot());
        }
        return path;
    }

    private static Path realRoot(FileAccessScope scope) {
        try {
            return scope.writeRoot().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("the working directory " + scope.writeRoot() + " does not exist");
        }
    }
}
