package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The localhost MCP server binds to the loopback address only and answers over real HTTP (tasks/todo.md, stage 6). */
class LocalMcpServerTest {

    @Test
    void answersARequestWithTheKeyOnTheLoopbackAddressOnly(@TempDir Path root) throws Exception {
        ActorSystem system = new ActorSystem("local-mcp-test");
        try (LocalMcpServer server = new LocalMcpServer(system, 0)) {
            assertTrue(server.address().getAddress().isLoopbackAddress(), server.address().toString());
            String key = server.register(new ToolRunner(
                    new ToolScope("s1", new FileAccessScope(root, List.of()), ToolSet.FULL, Map.of()),
                    expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE));
            String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"calc\",\"arguments\":{\"expression\":\"23*47\"}}}";
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ok = client.send(post(server.url(), "s1", "Bearer " + key, call), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("1081"), ok.body());
            HttpResponse<String> refused = client.send(post(server.url(), "s1", "Bearer wrong", call), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, refused.statusCode());
        } finally {
            system.terminate();
        }
    }

    private static HttpRequest post(String url, String scope, String authorization, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .header(McpRequestHandler.SCOPE_HEADER, scope).header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
