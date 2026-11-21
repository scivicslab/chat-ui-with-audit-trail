package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runner's limits and streams (tasks/todo.md, "Stage 11 spec"), on plain processes in place of ssh. */
class SshRunnerTest {

    @TempDir
    Path dir;

    @Test
    void theTimeLimitKillsTheProcessAndSaysSo() {
        SshRunner runner = new SshRunner(new SshLimits(Duration.ofSeconds(1), 1 << 20, 1 << 20));
        long start = System.nanoTime();
        SshRunner.Result r = runner.run(List.of("sh", "-c", "sleep 30"), SshRunner.Input.none(), null, 1 << 20);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 15, "it did not wait for sleep 30");
        assertEquals(-1, r.exitCode());
        assertTrue(r.limit() != null && r.limit().contains("time limit of 1 s"), String.valueOf(r.limit()));
    }

    @Test
    void theOutputLimitKillsTheProcessAndSaysSo() {
        SshRunner runner = new SshRunner(new SshLimits(Duration.ofSeconds(30), 4096, 1 << 20));
        SshRunner.Result r = runner.run(List.of("sh", "-c", "yes"), SshRunner.Input.none(), null, 4096);
        assertEquals(-1, r.exitCode());
        assertTrue(r.limit() != null && r.limit().contains("output limit of 4096 bytes"), String.valueOf(r.limit()));
        assertTrue(r.stdoutBytes() <= 4096 + 65536, "reading stopped near the limit: " + r.stdoutBytes());
    }

    @Test
    void theErrorStreamHasTheLimitToo() {
        SshRunner runner = new SshRunner(new SshLimits(Duration.ofSeconds(30), 4096, 1 << 20));
        SshRunner.Result r = runner.run(List.of("sh", "-c", "yes >&2"), SshRunner.Input.none(), null, 1 << 20);
        assertTrue(r.limit() != null && r.limit().contains("standard error"), String.valueOf(r.limit()));
    }

    @Test
    void inputGoesInExactlyAndTheExitStatusComesBack() throws Exception {
        SshRunner runner = new SshRunner(SshLimits.DEFAULT);
        String script = "#!/bin/sh\necho 'a b' \"$(id)\"\n";
        SshRunner.Result r = runner.run(List.of("sh", "-c", "cat; exit 3"), SshRunner.Input.text(script), null, 1 << 20);
        assertEquals(3, r.exitCode());
        assertEquals(script, r.stdout());
        assertNull(r.limit());

        Path in = dir.resolve("in.bin");
        Files.write(in, new byte[]{0, 1, 2, (byte) 255});
        Path out = dir.resolve("out.bin");
        SshRunner.Result f = runner.run(List.of("cat"), SshRunner.Input.file(in), out, 1 << 20);
        assertEquals(0, f.exitCode());
        assertEquals(4, f.stdoutBytes());
        assertEquals("", f.stdout());
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(in), Files.readAllBytes(out)));
    }
}
