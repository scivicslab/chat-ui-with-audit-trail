package com.scivicslab.chatui.unixhttp;

import com.scivicslab.pojoactor.core.ActorSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A small HTTP/1.1 server on a Unix domain socket, for agent-connector-kernel's MCP server and LLM proxy when a
 * harness runs in a container with {@code --network=none} and reaches them only through bind-mounted sockets
 * (tasks/todo.md, Decisions 9 and 10). Built on {@code java.nio}'s {@link UnixDomainSocketAddress}, so it adds no
 * dependency.
 *
 * <p>The socket file is created with owner-only permissions (0600) in a directory that only the owner can read or
 * enter (0700). A directory that exists with wider permissions is refused rather than changed. Each connection
 * carries one request and is closed after the answer ({@code Connection: close}), so a streamed answer needs no
 * length. Connections run on the {@link ActorSystem}'s managed thread pool, as the loopback-TCP forms do.</p>
 */
public final class UnixSocketHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(UnixSocketHttpServer.class.getName());
    private static final int MAX_HEAD = 64 * 1024;
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");

    /** Answers one request. */
    @FunctionalInterface
    public interface Handler {
        void handle(Exchange exchange) throws IOException;
    }

    /** One request read from the socket, and the way to answer it. */
    public static final class Exchange {
        private final String method;
        private final String target;
        private final Map<String, List<String>> headers;
        private final byte[] body;
        private final OutputStream out;
        private boolean answered;

        Exchange(String method, String target, Map<String, List<String>> headers, byte[] body, OutputStream out) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
            this.out = out;
        }

        /** @return the request method, e.g. {@code POST} */
        public String method() { return method; }

        /** @return the request target: the path with its query, e.g. {@code /s1/v1/messages?beta=true} */
        public String target() { return target; }

        /** @return the request headers, names in lower case */
        public Map<String, List<String>> headers() { return headers; }

        /** @return the first value of header {@code name} (any case), or {@code null} */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** @return the request body; empty when there is none */
        public byte[] body() { return body; }

        /** Answers with a whole body. */
        public void respond(int status, String contentType, byte[] content) throws IOException {
            StringBuilder head = statusLine(status);
            if (contentType != null && content.length > 0) head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(content.length).append("\r\nConnection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            answered = true;
        }

        /**
         * Starts an answer whose body is streamed: the caller writes it to the returned stream and closes it, and the
         * end of the body is the end of the connection.
         */
        public OutputStream stream(int status, Map<String, List<String>> responseHeaders) throws IOException {
            StringBuilder head = statusLine(status);
            responseHeaders.forEach((name, values) -> values.forEach(v -> head.append(name).append(": ").append(v).append("\r\n")));
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            answered = true;
            return out;
        }

        private static StringBuilder statusLine(int status) {
            return new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        }
    }

    private final Path socket;
    private final ServerSocketChannel server;
    private final ExecutorService pool;
    private final Handler handler;
    private volatile boolean open = true;

    /**
     * Binds the socket and starts accepting.
     *
     * @param system  the actor system whose managed thread pool runs the connections
     * @param socket  the socket file to create; its directory is created owner-only when missing
     * @param handler what answers each request
     * @throws IOException when the directory is readable by others, or the socket cannot be bound
     */
    public UnixSocketHttpServer(ActorSystem system, Path socket, Handler handler) throws IOException {
        this.socket = socket.toAbsolutePath();
        this.handler = handler;
        this.pool = system.getManagedThreadPool();
        Path dir = this.socket.getParent();
        if (Files.notExists(dir)) {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
        }
        Set<PosixFilePermission> dirPerms = Files.getPosixFilePermissions(dir);
        if (!OWNER_ONLY_DIR.containsAll(dirPerms)) {
            throw new IOException("the socket directory " + dir + " must be readable by its owner only, not "
                    + PosixFilePermissions.toString(dirPerms));
        }
        Files.deleteIfExists(this.socket);
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(this.socket));
        Files.setPosixFilePermissions(this.socket, OWNER_ONLY_FILE);
        pool.submit(this::acceptLoop);
    }

    /** @return the socket file */
    public Path socket() {
        return socket;
    }

    private void acceptLoop() {
        while (open) {
            try {
                SocketChannel connection = server.accept();
                pool.submit(() -> serve(connection));
            } catch (IOException e) {
                if (open) LOG.log(Level.WARNING, "accept on " + socket + " failed", e);
            }
        }
    }

    private void serve(SocketChannel connection) {
        try (connection; InputStream in = Channels.newInputStream(connection);
             OutputStream out = Channels.newOutputStream(connection)) {
            Exchange exchange = read(in, out);
            if (exchange == null) return;
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "a request on " + socket + " failed", e);
                if (!exchange.answered) exchange.respond(500, null, new byte[0]);
            }
            if (!exchange.answered) exchange.respond(500, null, new byte[0]);
        } catch (IOException e) {
            LOG.log(Level.FINE, "a connection on " + socket + " ended early", e);
        }
    }

    /** Reads one request; answers 400 and returns {@code null} when it is not one. */
    static Exchange read(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) return null;
            headBytes.write(b);
            if (headBytes.size() > MAX_HEAD) return badRequest(out);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = headBytes.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length != 3 || !requestLine[2].startsWith("HTTP/1.")) return badRequest(out);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.computeIfAbsent(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(lines[i].substring(colon + 1).strip());
        }
        byte[] body;
        List<String> te = headers.get("transfer-encoding");
        if (te != null && te.get(0).toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(in);
        } else {
            List<String> cl = headers.get("content-length");
            int length = cl == null ? 0 : Integer.parseInt(cl.get(0));
            body = in.readNBytes(length);
            if (body.length != length) return null;
        }
        return new Exchange(requestLine[0], requestLine[1], headers, body, out);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int semi = sizeLine.indexOf(';');
            int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).strip(), 16);
            if (size == 0) {
                while (!readLine(in).isEmpty()) { /* trailer */ }
                return body.toByteArray();
            }
            body.write(in.readNBytes(size));
            readLine(in);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') if (b != '\r') line.write(b);
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private static Exchange badRequest(OutputStream out) throws IOException {
        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return null;
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> "Status";
        };
    }

    /** Stops accepting and removes the socket file. */
    @Override
    public void close() {
        open = false;
        try {
            server.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "closing " + socket, e);
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException e) {
            LOG.log(Level.FINE, "removing " + socket, e);
        }
    }
}
