package com.scivicslab.chatui.unixhttp;

import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Unix-socket HTTP server's own rules (tasks/todo.md, Decisions 10; stage 9 part 1). */
class UnixSocketHttpServerTest {

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("unix-http-test");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void refusesASocketDirectoryOthersCanRead(@TempDir Path root) throws Exception {
        Path open = root.resolve("open");
        Files.createDirectories(open);
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
        IOException e = assertThrows(IOException.class,
                () -> new UnixSocketHttpServer(system, open.resolve("s.sock"), x -> x.respond(200, null, new byte[0])));
        assertTrue(e.getMessage().contains("readable by its owner only"), e.getMessage());
    }

    @Test
    void readsAChunkedBody(@TempDir Path root) throws Exception {
        Path socket = root.resolve("d").resolve("s.sock");
        try (UnixSocketHttpServer server = new UnixSocketHttpServer(system, socket,
                x -> x.respond(200, "text/plain", x.body()))) {
            byte[] head = ("POST /echo HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1);
            byte[] chunks = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            UnixSocketClient.Answer a = UnixSocketClient.sendRaw(server.socket(), head, chunks);
            assertEquals(200, a.status());
            assertEquals("hello world", a.body());
        }
    }
}
