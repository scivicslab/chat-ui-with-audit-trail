package com.scivicslab.chatui.core.remote;

import com.scivicslab.pojoactor.distributed.Receipt;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool node the AI-workspace lists gets the answers to its requests, and nothing else
 * (RepliesToToolNodes_261120_oo01).
 */
@Tag("RepliesToToolNodes_261120_oo01")
@DisplayName("RemoteNodes sends replies, and only replies, to a tool node")
class RepliesToToolNodesTest {

    @TempDir
    Path dir;
    HttpServer workspace;
    RemoteNodes chatUi;
    RemoteNodes tool;

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @AfterEach
    void stop() {
        if (chatUi != null) chatUi.close();
        if (tool != null) tool.close();
        if (workspace != null) workspace.stop(0);
    }

    @Test
    void aReplyReachesTheToolNodeAndARequestDoesNot() throws Exception {
        int chatPort = freePort();
        int toolPort = freePort();
        String nodes = "[{\"name\":\"chat-a\",\"host\":\"127.0.0.1\",\"actorPort\":" + chatPort + ",\"kind\":\"chat-ui\"},"
                + "{\"name\":\"tool-x\",\"host\":\"127.0.0.1\",\"actorPort\":" + toolPort + ",\"kind\":\"html-saurus\"}]";
        String peers = "[{\"name\":\"chat-a\",\"host\":\"127.0.0.1\",\"actorPort\":" + chatPort + "}]";
        workspace = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workspace.createContext(WorkspaceNodeDiscovery.NODES_PATH, ex -> answer(ex, nodes));
        workspace.createContext(WorkspaceNodeDiscovery.PEERS_PATH, ex -> answer(ex, peers));
        workspace.start();
        String url = "http://127.0.0.1:" + workspace.getAddress().getPort();

        // The tool node stands in with a chat-ui gateway of its own, so it can take an enqueueReply.
        tool = RemoteNodes.start(new IIActorSystem("tool"), "chat-a=127.0.0.1:" + chatPort, "tool-x", toolPort,
                dir.resolve("tool-ids.txt"));
        chatUi = RemoteNodes.start(new IIActorSystem("chat"), null, url, "chat-a", chatPort, dir.resolve("chat-ids.txt"));

        Receipt reply = chatUi.send("tool-x", "enqueueReply",
                "{\"requestId\":\"r1\",\"asker\":\"html-saurus/revise\",\"from\":\"project1/chat-01\",\"text\":\"x\"}");
        assertTrue(reply.accepted(), "the reply reaches the tool node: " + reply.detail());

        Receipt request = chatUi.send("tool-x", "enqueueRequest", "{}");
        assertFalse(request.accepted(), "a request to a tool node is refused before it is sent");
    }

    static void answer(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
