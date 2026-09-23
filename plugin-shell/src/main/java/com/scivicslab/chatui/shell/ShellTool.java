package com.scivicslab.chatui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one shell command and returns what it printed.
 *
 * <p>Started through {@code /bin/sh -c}, so the command is written the way it would be typed, with
 * pipes and redirection. The working directory is the conversation's own, and the whole output —
 * standard output and standard error together, in the order the process wrote them — goes into the
 * I/O log with the exit status, which is the point of running it here rather than in a terminal.</p>
 *
 * <p>Two bounds, and no more. A command that has not finished within {@link #TIMEOUT_SECONDS} is
 * killed, and output past {@link #MAX_OUTPUT} characters is cut with a line saying how much there
 * was; both are about a conversation that would otherwise sit waiting or be handed more text than
 * it can read. Nothing here restricts what the command may do: a shell cannot be made to do less
 * than a shell, and the instance that loads this plugin is the one that already reaches the web.</p>
 */
public final class ShellTool {

    /** How long a command may run before it is killed. */
    public static final int TIMEOUT_SECONDS = 120;

    /** How much of the output the conversation is given. */
    public static final int MAX_OUTPUT = 30_000;

    private ShellTool() {
    }

    /**
     * Runs {@code command} through {@code /bin/sh -c} in {@code workingDirectory}.
     *
     * @param workingDirectory where the command starts; the process's own directory when null
     * @param command          the command line, as it would be typed
     * @return the exit status and the output, or an {@code error: ...} the conversation feeds back
     */
    public static String run(Path workingDirectory, String command) {
        if (command == null || command.isBlank()) return "error: command required";
        ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-c", command));
        pb.redirectErrorStream(true);   // one stream, in the order it was written
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            pb.directory(workingDirectory.toFile());
        }
        Process process = null;
        try {
            process = pb.start();
            process.getOutputStream().close();   // nothing to type at it; a prompt would hang
            String output = read(process.getInputStream());
            boolean ended = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ended) {
                process.destroyForcibly();
                return "error: killed after " + TIMEOUT_SECONDS + " seconds\n" + output;
            }
            return "exit " + process.exitValue() + "\n" + output;
        } catch (IOException e) {
            return "error: " + e.getMessage();
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return "error: interrupted while waiting for the command";
        }
    }

    /** Reads the stream, keeping the opening and saying what was left out. */
    private static String read(InputStream in) throws IOException {
        byte[] all = in.readAllBytes();
        String text = new String(all, StandardCharsets.UTF_8);
        if (text.length() <= MAX_OUTPUT) return text;
        return text.substring(0, MAX_OUTPUT)
                + "\n[truncated " + text.length() + " chars total]";
    }
}
