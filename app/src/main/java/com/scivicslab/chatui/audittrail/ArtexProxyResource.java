package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Relays an OpenAI-compatible {@code /v1/chat/completions} call from an external caller that
 * drives its own agent loop (ARTEX's {@code planner}/{@code worker}/{@code mainagent}, built on
 * the {@code norma} SDK) to this application's configured upstream LLM server, and records the
 * full exchange ({@code ArtexProxyLogging_261017_oo01}).
 *
 * <p>The caller already builds its own full {@code messages} history and reads the server's reply
 * itself; this resource runs no agent loop of its own on the request — it is a transparent
 * forward, not a {@link com.scivicslab.chatui.core.actor.ConversationSquad} of this application's
 * own kind. See {@link ChatUiActorSystem#recordExternalCall} for why the record goes through a job
 * -style I/O log session rather than a conversation one.</p>
 *
 * <p>The request body is forwarded byte-for-byte; the response is recorded the same way it is
 * sent — the raw non-streaming body, or the raw JSON of each streamed chunk, one per line, in the
 * order received. This keeps the record faithful to what the upstream server actually said,
 * including fields (reasoning, tool calls, token usage) this resource does not itself parse.</p>
 */
@Path("/v1/chat/completions")
public class ArtexProxyResource {

    private static final Logger LOG = Logger.getLogger(ArtexProxyResource.class.getName());

    /** Sent by a caller that does not identify itself ({@code X-Caller-Id}). */
    private static final String DEFAULT_CALLER_ID = "external";

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * The OpenAI-compatible server this resource relays to (e.g. the GPU broker). Deliberately
     * separate from {@code chat-ui.servers} — this application's own conversations and an external
     * caller's calls are configured independently, so one can be pointed elsewhere without moving
     * the other.
     */
    @ConfigProperty(name = "chat-ui.external-proxy.upstream-base-url")
    Optional<String> upstreamBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * @param callerId    {@code X-Caller-Id} header value; names the I/O log session and the live
     *                    view entry's source. Defaults to {@value #DEFAULT_CALLER_ID} when absent.
     * @param requestBody the OpenAI-compatible chat-completion request, forwarded as received
     * @return the upstream server's response, forwarded as received — streamed back as
     *         {@code text/event-stream} when the request asked for {@code "stream":true}, as plain
     *         JSON otherwise
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response relay(@HeaderParam("X-Caller-Id") String callerId, String requestBody) {
        String caller = (callerId == null || callerId.isBlank()) ? DEFAULT_CALLER_ID : callerId.strip();
        String base = upstreamBaseUrl.map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
        if (base == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"chat-ui.external-proxy.upstream-base-url is not configured\"}")
                    .build();
        }
        if (requestBody == null || requestBody.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"empty request body\"}")
                    .build();
        }

        boolean streaming = isStreamingRequest(requestBody);
        try {
            if (streaming) {
                return relayStreaming(caller, base, requestBody);
            }
            return relayNonStreaming(caller, base, requestBody);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Relay to " + base + " failed for caller " + caller, e);
            actorSystem.recordExternalCall(caller,
                    "REQUEST:\n" + requestBody + "\n\nRESPONSE:\nERROR: " + e.getMessage());
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("{\"error\":\"" + JSONObject.quote(String.valueOf(e.getMessage())) + "\"}")
                    .build();
        }
    }

    /** @return whether the request body asks for a streamed reply ({@code "stream":true}) */
    static boolean isStreamingRequest(String requestBody) {
        try {
            return new JSONObject(requestBody).optBoolean("stream", false);
        } catch (Exception e) {
            // Not valid JSON — forwarded as-is below and the upstream server will reject it;
            // treated as non-streaming so that rejection comes back as one plain response.
            return false;
        }
    }

    /** One-shot relay: send, wait for the full body, record it, return it. */
    private Response relayNonStreaming(String caller, String base, String requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        actorSystem.recordExternalCall(caller,
                "REQUEST:\n" + requestBody + "\n\nRESPONSE:\n" + response.body());
        return Response.status(response.statusCode())
                .type(MediaType.APPLICATION_JSON)
                .entity(response.body())
                .build();
    }

    /**
     * Streamed relay. Reads the upstream SSE through to its end on this resource method's own
     * (worker) thread, then sends the caller the whole response in one write.
     *
     * <p>This is not an incremental passthrough: the caller does not see the first token until the
     * upstream server has sent its last one. The caller still receives the exact SSE body the
     * upstream server sent (so its own stream parser works unchanged), and the record is the same
     * either way; only the moment the caller sees it is later than calling the upstream server
     * directly. Streaming the response on as each line arrives — giving the caller the same
     * early-token behavior it would get without this relay — is a further step, not yet done.</p>
     */
    private Response relayStreaming(String caller, String base, String requestBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        StringBuilder forwarded = new StringBuilder();
        StringBuilder chunks = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                forwarded.append(line).append('\n');
                if (line.startsWith("data: ")) {
                    String payload = line.substring(6);
                    if (chunks.length() > 0) chunks.append('\n');
                    chunks.append(payload);
                }
            });
        }
        actorSystem.recordExternalCall(caller,
                "REQUEST:\n" + requestBody + "\n\nRESPONSE (raw stream chunks, one per line):\n" + chunks);
        return Response.status(response.statusCode())
                .type(MediaType.SERVER_SENT_EVENTS)
                .entity(forwarded.toString())
                .build();
    }
}
