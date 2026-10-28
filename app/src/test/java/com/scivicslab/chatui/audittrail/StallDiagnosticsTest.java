package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for when a stalled conversation's thread dump is written.
 *
 * <p>Exercises the load-bearing path: a dump is written the first time, none is written again within
 * the interval (a browser that keeps retrying must not fill the disk), one is written again once
 * the interval has passed, and a dump that fails does not throw into the request that noticed the
 * stall and is not retried at every request. The dump itself is replaced by a stub.</p>
 */
class StallDiagnosticsTest {

    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicInteger dumps = new AtomicInteger();

    private StallDiagnostics diagnostics(StallDiagnostics.Dumper dumper) {
        return new StallDiagnostics(dir.resolve("diagnostics"), 300_000L, clock::get, dumper);
    }

    private StallDiagnostics.Dumper writingStub() {
        return file -> {
            dumps.incrementAndGet();
            Files.writeString(file, "{}");
        };
    }

    @Test
    void firstStallWritesAFileNamedAfterWhatStalled() {
        Path file = diagnostics(writingStub()).dumpIfDue("project10/chat-01");

        assertNotNull(file);
        assertTrue(Files.exists(file));
        assertTrue(file.getFileName().toString().contains("project10_chat-01"), file.toString());
        assertEquals(1, dumps.get());
    }

    @Test
    void secondStallWithinTheIntervalWritesNothing() {
        StallDiagnostics d = diagnostics(writingStub());
        d.dumpIfDue("project10/chat-01");
        clock.addAndGet(299_999L);

        assertNull(d.dumpIfDue("project10/chat-01"));
        assertEquals(1, dumps.get());
    }

    @Test
    void stallAfterTheIntervalWritesAgain() throws IOException {
        StallDiagnostics d = diagnostics(writingStub());
        d.dumpIfDue("project10/chat-01");
        clock.addAndGet(300_000L);

        assertNotNull(d.dumpIfDue("project10/chat-02"));
        assertEquals(2, dumps.get());
        try (var files = Files.list(dir.resolve("diagnostics"))) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void failingDumpReturnsNullAndIsNotRetriedAtOnce() {
        AtomicInteger attempts = new AtomicInteger();
        StallDiagnostics d = diagnostics(file -> {
            attempts.incrementAndGet();
            throw new IOException("disk full");
        });

        assertNull(d.dumpIfDue("project10/chat-01"));
        assertNull(d.dumpIfDue("project10/chat-01"));
        assertEquals(1, attempts.get());
    }

    @Test
    void jvmDumperWritesARealThreadDumpIncludingVirtualThreads() throws Exception {
        Thread parked = Thread.ofVirtual().name("stall-test-virtual").start(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
        });
        try {
            StallDiagnostics d = new StallDiagnostics(dir.resolve("real"), 300_000L, clock::get,
                    file -> {
                        var bean = java.lang.management.ManagementFactory
                                .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                        bean.dumpThreads(file.toString(),
                                com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
                    });
            Path file = d.dumpIfDue("real");

            assertNotNull(file);
            String json = Files.readString(file);
            assertTrue(json.contains("threadDump"), "not a JVM thread dump");
            assertTrue(json.contains("stall-test-virtual"), "virtual threads are not listed");
        } finally {
            parked.interrupt();
        }
    }
}
