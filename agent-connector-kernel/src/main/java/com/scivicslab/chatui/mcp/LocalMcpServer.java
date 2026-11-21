package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScopes;
import com.scivicslab.chatui.unixhttp.UnixSocketHttpServer;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * agent-connector-kernel's MCP server for harnesses: HTTP on the loopback address only, at {@code /mcp}
 * (tasks/todo.md, Decisions 5 and stage 6). It is built on the JDK's {@code com.sun.net.httpserver}, so it adds no
 * dependency and runs without Quarkus.
 *
 * <p>The scopes and their keys live in a {@link ToolScopes} actor, so registering and looking up never overlap. The
 * HTTP server runs each request on the {@link ActorSystem}'s managed thread pool, so a long tool call does not hold
 * up other requests. What a request gets back is decided by {@link McpRequestHandler}.</p>
 *
 * <p>For a harness in a container with {@code --network=none}, the same server can listen on a Unix domain socket
 * instead (tasks/todo.md, Decisions 10): the same scopes, keys and request handling, and no TCP port at all.</p>
 */
public final class LocalMcpServer implements AutoCloseable {

    private final HttpServer http;
    private final UnixSocketHttpServer unix;
    private final ActorRef<ToolScopes> scopes;

    /**
     * Starts the server on the loopback address.
     *
     * @param system the actor system the scopes actor and the request threads belong to
     * @param port   the port, or 0 for any free one
     */
    public LocalMcpServer(ActorSystem system, int port) throws IOException {
        this.scopes = system.actorOf("mcp-scopes", new ToolScopes());
        McpRequestHandler handler = handler(scopes);
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        http.createContext("/mcp", exchange -> answer(exchange, handler));
        http.setExecutor(system.getManagedThreadPool());
        http.start();
        this.unix = null;
    }

    /**
     * Starts the server on a Unix domain socket only, with no TCP port (tasks/todo.md, Decisions 10).
     *
     * @param system the actor system the scopes actor and the connections belong to
     * @param socket the socket file, created 0600 in an owner-only directory
     */
    public LocalMcpServer(ActorSystem system, Path socket) throws IOException {
        this.scopes = system.actorOf("mcp-scopes-" + Integer.toHexString(socket.toAbsolutePath().hashCode()), new ToolScopes());
        McpRequestHandler handler = handler(scopes);
        this.http = null;
        this.unix = new UnixSocketHttpServer(system, socket, exchange -> {
            if (!"/mcp".equals(exchange.target())) {
                exchange.respond(404, null, new byte[0]);
            } else if (!"POST".equals(exchange.method())) {
                exchange.respond(405, null, new byte[0]);
            } else {
                McpRequestHandler.Reply reply = handler.handle(exchange.header(McpRequestHandler.SCOPE_HEADER),
                        exchange.header("Authorization"), new String(exchange.body(), StandardCharsets.UTF_8));
                exchange.respond(reply.status(), "application/json", reply.body().getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    private static McpRequestHandler handler(ActorRef<ToolScopes> scopes) {
        return new McpRequestHandler(
                name -> scopes.ask(s -> s.has(name)).join(),
                (name, key) -> scopes.ask(s -> s.open(name, key)).join());
    }

    /** @return the socket file, or {@code null} when the server listens on loopback TCP */
    public Path socket() {
        return unix == null ? null : unix.socket();
    }

    /** @return the address harnesses are handed, e.g. {@code http://127.0.0.1:41234/mcp}; {@code null} on a Unix socket */
    public String url() {
        if (http == null) return null;
        return "http://" + http.getAddress().getAddress().getHostAddress() + ":" + http.getAddress().getPort() + "/mcp";
    }

    /** @return the loopback address the server is bound to, or {@code null} when it listens on a Unix socket */
    public InetSocketAddress address() {
        return http == null ? null : http.getAddress();
    }

    /**
     * Opens a scope to requests.
     *
     * @return the key a request for the scope must carry
     */
    public String register(ToolRunner runner) {
        return scopes.ask(s -> s.register(runner)).join();
    }

    /** Closes a scope to requests. */
    public void unregister(String scopeName) {
        scopes.tell(s -> s.unregister(scopeName)).join();
    }

    private static void answer(HttpExchange exchange, McpRequestHandler handler) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            McpRequestHandler.Reply reply = handler.handle(
                    exchange.getRequestHeaders().getFirst(McpRequestHandler.SCOPE_HEADER),
                    exchange.getRequestHeaders().getFirst("Authorization"), body);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        }
    }

    /** Stops the server at once and closes the scopes actor. */
    @Override
    public void close() {
        if (http != null) http.stop(0);
        if (unix != null) unix.close();
        scopes.close();
    }
}
