package com.scivicslab.sshtools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one {@code ssh} process at a time with its limits (tasks/todo.md, "Stage 11 spec"). A plain object; ssh-connector
 * runs one per host as a POJO-actor actor, so calls to one host go one after another. The process is started from an
 * argument array, never through a shell. Its two output streams and its input are carried by virtual threads of their
 * own, which end with the process.
 */
public final class SshRunner {

    /**
     * What the process reads on its standard input.
     *
     * @param bytes the bytes to send, or {@code null}
     * @param file  the file to send, or {@code null}; nothing is sent when both are {@code null}
     */
    public record Input(byte[] bytes, Path file) {

        /** @return no input: standard input is closed at once */
        public static Input none() {
            return new Input(null, null);
        }

        /** @return {@code text} in UTF-8 */
        public static Input text(String text) {
            return new Input(text.getBytes(StandardCharsets.UTF_8), null);
        }

        /** @return the contents of {@code file} */
        public static Input file(Path file) {
            return new Input(null, file);
        }
    }

    /**
     * How one run ended.
     *
     * @param exitCode    the process's exit status, or -1 when it was killed for a limit or could not start
     * @param stdout      standard output in UTF-8, or {@code ""} when it was written to a file
     * @param stderr      standard error in UTF-8
     * @param stdoutBytes how many bytes standard output carried
     * @param limit       {@code null}, or the message naming the limit that killed the process
     */
    public record Result(int exitCode, String stdout, String stderr, long stdoutBytes, String limit) {
    }

    private final SshLimits limits;

    /** @param limits the time limit and the per-stream output limit of every run */
    public SshRunner(SshLimits limits) {
        this.limits = limits;
    }

    /**
     * Runs {@code command} and waits for it, or kills it at a limit.
     *
     * @param command     the argument array
     * @param input       what goes to standard input
     * @param stdoutFile  where standard output is written, or {@code null} to keep it in the result
     * @param stdoutLimit how many bytes standard output may carry
     * @return how it ended
     */
    public Result run(List<String> command, Input input, Path stdoutFile, long stdoutLimit) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            return new Result(-1, "", "cannot start " + command.get(0) + ": " + e.getMessage(), 0, null);
        }
        AtomicReference<String> limit = new AtomicReference<>();
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        long[] outCount = new long[1];
        Thread feeder = Thread.ofVirtual().start(() -> feed(process, input));
        Thread outReader = Thread.ofVirtual().start(() -> {
            try (OutputStream sink = stdoutFile == null ? outBuffer : Files.newOutputStream(stdoutFile)) {
                outCount[0] = copy(process.getInputStream(), sink, stdoutLimit, "standard output", process, limit);
            } catch (IOException e) {
                limit.compareAndSet(null, "standard output could not be written: " + e.getMessage());
                kill(process);
            }
        });
        Thread errReader = Thread.ofVirtual().start(() ->
                copy(process.getErrorStream(), errBuffer, limits.outputBytes(), "standard error", process, limit));
        try {
            if (!process.waitFor(limits.time().toMillis(), TimeUnit.MILLISECONDS)) {
                limit.compareAndSet(null, "the time limit of " + describe() + " was reached; ssh was killed");
                kill(process);
                process.waitFor();
            }
            outReader.join();
            errReader.join();
            feeder.join();
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted", outCount[0], "the run was interrupted; ssh was killed");
        }
        String stdout = outBuffer.toString(StandardCharsets.UTF_8);
        String stderr = errBuffer.toString(StandardCharsets.UTF_8);
        return limit.get() != null
                ? new Result(-1, stdout, stderr, outCount[0], limit.get())
                : new Result(process.exitValue(), stdout, stderr, outCount[0], null);
    }

    private String describe() {
        long ms = limits.time().toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }

    private static void feed(Process process, Input input) {
        try (OutputStream in = process.getOutputStream()) {
            if (input.bytes() != null) in.write(input.bytes());
            else if (input.file() != null) Files.copy(input.file(), in);
        } catch (IOException e) {
            // The process stopped reading: it ended or was killed, which its result says.
        }
    }

    /** Copies up to {@code max} bytes; at the limit it records which and kills the process. */
    private static long copy(InputStream from, OutputStream to, long max, String stream, Process process,
                             AtomicReference<String> limit) {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (from) {
            int n;
            while ((n = from.read(buffer)) > 0) {
                if (total + n > max) {
                    to.write(buffer, 0, (int) (max - total));
                    total = max;
                    limit.compareAndSet(null, "the output limit of " + max + " bytes was reached on " + stream
                            + "; ssh was killed");
                    kill(process);
                    break;
                }
                to.write(buffer, 0, n);
                total += n;
            }
        } catch (IOException e) {
            // The stream closed because the process was killed.
        }
        return total;
    }

    /** Kills the process and anything it started, such as the ssh of a ProxyJump. */
    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
