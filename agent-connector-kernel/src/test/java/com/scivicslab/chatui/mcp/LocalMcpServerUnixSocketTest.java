package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.chatui.unixhttp.UnixSocketClient;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent-connector-kernel's MCP server on a Unix domain socket: the same keys and answers as on loopback TCP, and a
 * socket file only its owner can use (tasks/todo.md, Decisions 10; stage 9 part 1). Dummy keys only.
 */
class LocalMcpServerUnixSocketTest {

    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private ActorSystem system;
    private LocalMcpServer server;
    private String key;
    private Path socket;

    @BeforeEach
    void setUp(@TempDir Path root) throws Exception {
        system = new ActorSystem("mcp-unix-test");
        socket = root.resolve("run").resolve("mcp.sock");
        server = new LocalMcpServer(system, socket);
        key = server.register(new ToolRunner(new ToolScope("s1", new FileAccessScope(root, List.of()), ToolSet.FULL, Map.of()),
                expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE));
    }

    @AfterEach
    void tearDown() {
        server.close();
        system.terminate();
    }

    @Test
    void toolsListOverTheSocketWithTheRightKey() throws Exception {
        UnixSocketClient.Answer a = UnixSocketClient.send(socket, "POST", "/mcp",
                Map.of("Content-Type", "application/json", "Authorization", "Bearer " + key,
                        McpRequestHandler.SCOPE_HEADER, "s1"), LIST);
        assertEquals(200, a.status());
        assertTrue(a.body().contains("\"read\"") && a.body().contains("\"calc\""), a.body());
    }

    @Test
    void aWrongKeyOverTheSocketIs401() throws Exception {
        UnixSocketClient.Answer a = UnixSocketClient.send(socket, "POST", "/mcp",
                Map.of("Authorization", "Bearer wrong", McpRequestHandler.SCOPE_HEADER, "s1"), LIST);
        assertEquals(401, a.status());
    }

    @Test
    void theSocketFileIsOwnerOnlyInAnOwnerOnlyDirectory() throws Exception {
        assertEquals(socket.toAbsolutePath(), server.socket());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.getParent())));
    }
}
