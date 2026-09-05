package com.scivicslab.chatui.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code read} ReAct tool: reads a file, or recursively reads a directory, under the working
 * directory and returns the text as an Observation.
 *
 * <p>Reads are confined to the working directory: the resolved real path (symlinks included) must be
 * inside the working directory's real path, otherwise the request is rejected. This keeps the tool's
 * reach matched to the trust boundary (no unrestricted POSIX access from the Web side).</p>
 *
 * <p>Directory recursion is capped (total bytes and file count) so a huge tree cannot exhaust memory;
 * the {@code s_budget} truncation only shrinks the copy sent to the model, not the Java string built
 * here, so this independent cap is required.</p>
 *
 * <p>Several paths may be given, one per line, and the same cap then covers the call as a whole.
 * The cap is what keeps a read from flooding the context, not the number of paths a call accepts:
 * one path already reaches a whole directory tree, so refusing a second path never bounded anything
 * — it only made comparing two files cost two turns, or one read of a parent directory that pulls in
 * everything else under it as well.</p>
 */
public final class FileReadTool {

    private FileReadTool() {}

    /** Total characters read for a directory before truncating. */
    static final long MAX_TOTAL_CHARS = 4_000_000;
    /** Maximum number of files read for a directory. */
    static final int MAX_FILES = 1000;

    /** Build/VCS/IDE/dependency directories skipped when reading a directory (noise, not source). */
    static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "target", "build", "dist", "out", "bin", "node_modules",
            ".git", ".gradle", ".idea", ".mvn", ".vscode", ".settings");
    /** File extensions skipped when reading a directory (binary / generated, not readable source). */
    static final java.util.Set<String> SKIP_EXT = java.util.Set.of(
            "class", "jar", "war", "ear", "zip", "gz", "tar", "tgz", "so", "o", "a", "dll", "exe",
            "bin", "png", "jpg", "jpeg", "gif", "ico", "svg", "pdf", "woff", "woff2", "ttf", "eot",
            "mp4", "mp3", "wav", "lock", "p12", "jks", "keystore");

    /**
     * Reads {@code input}, a path relative to the scope's write root or an absolute one. Returns
     * file/directory text, or an {@code error: ...} string the agent feeds back as the Observation.
     *
     * @param scope the conversation's file range ({@code FileAccessScope_260830_oo01})
     * @param input the path as the model wrote it
     * @return the text, or {@code error: ...}
     */
    public static String read(FileAccessScope scope, String input) {
        return read(scope, input, MAX_TOTAL_CHARS, MAX_FILES);
    }

    /** As {@link #read(FileAccessScope, String)} but confined to one directory (used by tests). */
    public static String read(Path root, String input) {
        return read(new FileAccessScope(root, java.util.List.of()), input, MAX_TOTAL_CHARS, MAX_FILES);
    }

    /** As {@link #read(FileAccessScope, String)} but with explicit caps (used by tests). */
    static String read(FileAccessScope scope, String input, long maxChars, int maxFiles) {
        if (input == null || input.isBlank()) {
            return "error: path required";
        }
        List<String> paths = splitPaths(input);
        return paths.size() == 1
                ? readOne(scope, paths.get(0), maxChars, maxFiles)
                : readSeveral(scope, paths, maxChars, maxFiles);
    }

    /**
     * Splits the argument into the paths it names, one per line.
     *
     * <p>Reading three files that live in three different directories otherwise costs three turns,
     * or one read of a common parent that also drags in everything else under it — which puts more
     * into the context, not less. Newline is the separator because a path may contain a comma or a
     * space but not a newline.
     */
    static List<String> splitPaths(String input) {
        return input.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Reads each path in turn, sharing one budget across all of them, and says where it stopped.
     *
     * <p>The same shape a directory read uses: every requested path is listed first, so a reply cut
     * short still tells the model what it has not seen and can go back for.
     */
    private static String readSeveral(FileAccessScope scope, List<String> paths, long maxChars, int maxFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append(paths.size()).append(" paths requested. Every one is listed here, and the contents")
          .append(" follow in the same order. This reply may be cut short before the last of them —")
          .append(" when it is, call read on the individual paths below that you still need.\n\nPATHS:\n");
        for (String path : paths) {
            sb.append("  ").append(path).append("\n");
        }
        sb.append("\nCONTENTS:\n\n");

        long remainingChars = maxChars;
        int remainingFiles = maxFiles;
        int done = 0;
        for (String path : paths) {
            if (remainingChars <= 0 || remainingFiles <= 0) {
                sb.append("\n…(stopped after ").append(done).append(" of ").append(paths.size())
                  .append(" paths: the read cap was reached. Read the rest individually.)…\n");
                break;
            }
            String text = readOne(scope, path, remainingChars, remainingFiles);
            sb.append("===== ").append(path).append(" =====\n").append(text).append("\n\n");
            remainingChars -= text.length();
            remainingFiles--;
            done++;
        }
        return sb.toString();
    }

    /** One path: a file's text, a directory read recursively, or {@code error: ...}. */
    private static String readOne(FileAccessScope scope, String input, long maxChars, int maxFiles) {
        Path root = scope.writeRoot();
        try {
            Path base = root.toAbsolutePath().normalize();
            // Accept how users actually write paths: expand ~ and $HOME, and allow absolute paths.
            // The confinement check below still restricts the result to the working directory, so this
            // only changes how the path is spelled, not what may be read.
            String p = expandHome(input.trim());
            Path target = base.resolve(p).normalize();
            if (!Files.exists(target)) {
                return "error: not found: " + input;
            }
            // Resolve symlinks and confirm the target stays inside the working directory.
            Path realTarget = target.toRealPath();
            if (!scope.canRead(realTarget)) {
                return "error: path is outside the readable directories (" + scope.describeReadRoots()
                        + "): " + input;
            }
            if (Files.isDirectory(realTarget)) {
                Path label = scope.matchingReadRoot(realTarget);
                return readDirectory(label == null ? realTarget : label.toRealPath(),
                        realTarget, maxChars, maxFiles);
            }
            return Files.readString(realTarget);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /** True if a file is inside a skipped directory or has a skipped (binary/generated) extension. */
    static boolean isSkipped(Path dir, Path f) {
        for (Path seg : dir.relativize(f)) {
            if (SKIP_DIRS.contains(seg.toString())) return true;
        }
        String name = f.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SKIP_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Expands a leading {@code ~} or {@code $HOME} to the user's home directory; leaves the rest as-is. */
    static String expandHome(String p) {
        String home = System.getProperty("user.home", "");
        if (home.isEmpty()) return p;
        if (p.equals("~") || p.equals("$HOME")) return home;
        if (p.startsWith("~/")) return home + p.substring(1);
        if (p.startsWith("$HOME/")) return home + p.substring(5);
        return p;
    }

    /**
     * Reads a directory as a manifest followed by the files' contents.
     *
     * <p>The manifest comes first, and it is what makes the reply usable when the reply is cut
     * short. A directory read is one observation, and the agent loop shows the model only the
     * first and last part of an observation that exceeds its budget
     * ({@code ContextBudget.truncateObservation}). Reading a 105 KB directory into a 20 000
     * character budget therefore left the model with the first document, the tail of the last one,
     * and no way to know which documents it had not seen — it read a directory "completely" and
     * was missing most of it. Listing every path at the top costs about one line per file, survives
     * the head of that cut, and turns the rest into something the model can go and read one at a
     * time.</p>
     */
    private static String readDirectory(Path base, Path dir, long maxChars, int maxFiles) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(f -> !isSkipped(dir, f))   // drop build/VCS/binary noise (target/, .git/, *.class …)
                    .sorted().toList();
        }
        if (files.isEmpty()) {
            return "(no readable files under " + base.relativize(dir) + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(base.relativize(dir)).append(" contains ").append(files.size())
          .append(files.size() == 1 ? " readable file." : " readable files.")
          .append(" Every path is listed here, and the contents follow in the same order.")
          .append(" This reply may be cut short before the last of them — when it is, call read on")
          .append(" the individual paths below that you still need.\n\nFILES:\n");
        for (Path f : files) {
            sb.append("  ").append(base.relativize(f)).append("\n");
        }
        sb.append("\nCONTENTS:\n\n");
        int count = 0;
        long chars = 0;
        for (Path f : files) {
            if (count >= maxFiles || chars >= maxChars) {
                sb.append("\n…(stopped after ").append(count).append(" of ").append(files.size())
                  .append(" files: the read cap was reached. Read the rest individually.)…\n");
                break;
            }
            String content;
            try {
                content = Files.readString(f);   // UTF-8; skip files that are not readable text
            } catch (IOException e) {
                continue;
            }
            sb.append("===== ").append(base.relativize(f)).append(" =====\n")
              .append(content).append("\n\n");
            count++;
            chars += content.length();
        }
        if (count < files.size()) {
            sb.append("\n(contents included for ").append(count).append(" of ")
              .append(files.size()).append(" files.)\n");
        }
        return sb.toString();
    }
}
