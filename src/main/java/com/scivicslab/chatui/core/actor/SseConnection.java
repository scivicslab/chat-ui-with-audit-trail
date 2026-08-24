package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.rest.ChatEvent;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns one conversation tab's SSE connection to the browser. {@link #emit} pushes a
 * {@link ChatEvent} downstream; a {@link ConversationTab} sibling of {@link ChatSession}.
 *
 * <p>Adapted from {@code quarkus-chat-ui3}'s {@code SseActor} rather than ported verbatim from
 * {@code quarkus-chat-ui/core}'s {@code SseActor} ({@code SseConnectionPorting_260823_oo01}) —
 * that reference class writes directly to a raw Vert.x {@code HttpServerResponse}, but
 * {@code chat-ui-with-audit-trail} is JAX-RS only (no Vert.x route layer), so it uses the same
 * {@code Multi<String>}/{@code UnicastProcessor} mechanism chat-ui3's REST layer already expects
 * ({@code @Produces(MediaType.SERVER_SENT_EVENTS)} returning a {@code Multi<String>}).</p>
 *
 * <p>This is a plain POJO actor — no CDI annotations. Thread safety is guaranteed by the actor's
 * sequential message processing via {@code ActorRef}, once wrapped by {@code createChild}.</p>
 */
public class SseConnection {

    private static final Logger logger = Logger.getLogger(SseConnection.class.getName());

    private final ObjectMapper mapper;
    private volatile UnicastProcessor<String> processor;

    public SseConnection(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Opens a fresh stream, replacing any previous one. Called by {@code ChatResource} when the
     * browser opens the SSE endpoint (including on reconnect).
     *
     * @return the {@link UnicastProcessor}, itself a {@code Multi<String>} the REST layer returns
     */
    public UnicastProcessor<String> openStream() {
        processor = UnicastProcessor.create();
        return processor;
    }

    /**
     * Serializes {@code event} to JSON and pushes it to the open stream. A no-op if no browser
     * is currently connected (dropped silently, matching {@code ChatSession}'s other emitters).
     *
     * @param event the event to push
     */
    public void emit(ChatEvent event) {
        UnicastProcessor<String> p = processor;
        if (p == null) return;
        try {
            p.onNext(mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            logger.log(Level.WARNING, "Failed to serialize ChatEvent", e);
        } catch (Exception e) {
            // The stream may have been cancelled by the client (browser navigated away).
            logger.log(Level.FINE, "SSE emit skipped (stream closed): " + e.getMessage());
        }
    }

    /** Ends the current stream, if any. */
    public void complete() {
        UnicastProcessor<String> p = processor;
        if (p != null) p.onComplete();
    }
}
