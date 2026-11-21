package com.scivicslab.chatui.unixhttp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** A test client: sends one raw HTTP/1.1 request over a Unix domain socket and returns the whole answer. */
public final class UnixSocketClient {

    private UnixSocketClient() {
    }

    /** The status code and body of an answer. */
    public record Answer(int status, String head, String body) {
    }

    /** Sends {@code method target} with the given headers and body, with Content-Length, and reads to the end. */
    public static Answer send(Path socket, String method, String target, Map<String, String> headers, String body)
            throws Exception {
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder request = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: localhost\r\n");
        headers.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
        request.append("Content-Length: ").append(content.length).append("\r\n\r\n");
        return sendRaw(socket, request.toString().getBytes(StandardCharsets.ISO_8859_1), content);
    }

    /** Sends raw bytes and reads to the end of the connection. */
    public static Answer sendRaw(Path socket, byte[] head, byte[] content) throws Exception {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            OutputStream out = Channels.newOutputStream(channel);
            out.write(head);
            out.write(content);
            out.flush();
            InputStream in = Channels.newInputStream(channel);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            String headPart = split < 0 ? text : text.substring(0, split);
            String bodyPart = split < 0 ? "" : text.substring(split + 4);
            int status = Integer.parseInt(headPart.split(" ")[1]);
            return new Answer(status, headPart, bodyPart);
        }
    }
}
