package com.scivicslab.chatui.audittrail;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a thread dump, virtual threads included, at the moment a conversation looks stalled.
 *
 * <p>A conversation is stalled when it is not answering ({@code busy} is false) and yet a question
 * asked of its actor times out. While a turn is running the actor's thread is occupied on purpose
 * and such a question times out by design; with no turn running it should be answered at once. On
 * 2026-10-28 {@code project10/chat-01} stayed in that state for the better part of an hour, its
 * queue grew and Cancel did nothing, and a dump taken afterwards showed no thread to blame. The
 * dump has to be taken while the state lasts, which is when the question that notices it times
 * out.</p>
 *
 * <p>At most one dump is written per interval, so a browser that keeps retrying does not fill the
 * disk. A dump is a file, not a log line: it is some hundreds of kilobytes.</p>
 */
public final class StallDiagnostics {

    private static final Logger LOG = Logger.getLogger(StallDiagnostics.class.getName());

    /** Writes a thread dump to a file that does not exist yet. */
    @FunctionalInterface
    public interface Dumper {
        void dumpTo(Path file) throws IOException;
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final long minIntervalMillis;
    private final LongSupplier nowMillis;
    private final Dumper dumper;
    private long lastDumpMillis;
    private boolean dumpedBefore;

    /**
     * @param directory         where the dump files go; created when the first one is written
     * @param minIntervalMillis least time between two dumps
     * @param nowMillis         the clock, replaceable by a test
     * @param dumper            writes one dump, replaceable by a test
     */
    public StallDiagnostics(Path directory, long minIntervalMillis, LongSupplier nowMillis, Dumper dumper) {
        this.directory = directory;
        this.minIntervalMillis = minIntervalMillis;
        this.nowMillis = nowMillis;
        this.dumper = dumper;
    }

    /**
     * The one the server uses: dumps under {@code ~/.chat-ui-with-audit-trail/diagnostics}, at most
     * one every five minutes, through the JVM's own thread-dump facility in its JSON form (the only
     * form that lists virtual threads).
     */
    public static StallDiagnostics atDefaultLocation() {
        Path dir = Path.of(System.getProperty("user.home"), ".chat-ui-with-audit-trail", "diagnostics");
        return new StallDiagnostics(dir, 5 * 60_000L, System::currentTimeMillis, StallDiagnostics::dumpWithJvm);
    }

    private static void dumpWithJvm(Path file) throws IOException {
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        bean.dumpThreads(file.toString(), com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
    }

    /**
     * Writes a dump unless one was written within the interval.
     *
     * @param what what stalled, e.g. {@code "project10/chat-01"}; goes into the file name and the log
     * @return the file written, or {@code null} when none was due or the dump failed
     */
    public synchronized Path dumpIfDue(String what) {
        long now = nowMillis.getAsLong();
        if (dumpedBefore && now - lastDumpMillis < minIntervalMillis) return null;
        // Taken before the attempt, so a dump that keeps failing is not retried at every request.
        dumpedBefore = true;
        lastDumpMillis = now;
        try {
            Files.createDirectories(directory);
            String safe = what.replaceAll("[^A-Za-z0-9._-]", "_");
            Path file = directory.resolve("threads-" + LocalDateTime.now().format(STAMP)
                    + "-pid" + ProcessHandle.current().pid() + "-" + safe + ".json");
            dumper.dumpTo(file);
            LOG.warning("Stalled: " + what + " is not answering although it is idle. Thread dump: " + file);
            return file;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write the thread dump for the stalled " + what, e);
            return null;
        }
    }
}
