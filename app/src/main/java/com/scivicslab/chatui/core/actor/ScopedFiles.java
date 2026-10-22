package com.scivicslab.chatui.core.actor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.turingworkflow.plugins.rstframe.FileGate;

/**
 * The files a job's workflow may read and write: the instance's range ({@code FileAccessScope_260830_oo01}), the
 * same one every conversation has, set once at start-up. Registered as the actor {@code files}; a workflow reads and
 * writes through it, and the actors of plugin-rst-frame reach it as their {@link FileGate}. Holds nothing that changes,
 * so the jobs of every project may use it at once.
 */
public final class ScopedFiles implements FileGate {

    private final FileAccessScope scope;

    public ScopedFiles(FileAccessScope scope) {
        this.scope = scope;
    }

    @Override
    public String read(Path file) throws IOException {
        Path real = file.toAbsolutePath().toRealPath();
        if (!scope.canRead(real)) throw new SecurityException("outside the readable range: " + real);
        return Files.readString(real);
    }

    /** Writes the text, ending it with a newline when it has none: what a model answers with often lacks one. */
    @Override
    public void write(Path file, String text) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        mayWrite(target);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, text.isEmpty() || text.endsWith("\n") ? text : text + "\n");
    }

    @Override
    public List<Path> list(Path dir, String suffix, int depth) throws IOException {
        Path root = dir.toAbsolutePath().toRealPath();
        if (!scope.canRead(root)) throw new SecurityException("outside the readable range: " + root);
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(root, Math.max(1, depth))) {
            walk.filter(Files::isRegularFile).filter(p -> suffix.isEmpty() || p.getFileName().toString().endsWith(suffix))
                .sorted().forEach(out::add);
        }
        return out;
    }

    @Override
    public void mayWrite(Path file) {
        Path target = file.toAbsolutePath().normalize();
        if (!scope.canWrite(target)) throw new SecurityException("outside the writable range: " + target);
    }
}
