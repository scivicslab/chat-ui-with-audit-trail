package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.rest.ChatEvent;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns one ConversationSquad's SSE connection to the browser. {@link #emit} pushes a
 * {@link ChatEvent} downstream; a {@link ConversationSquad} sibling of {@link ChatSession}.
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
    /** How much of the turn's activity text is kept for a browser that opens the stream mid-turn. */
    static final int ACTIVITY_TAIL_CHARS = 8000;

    private volatile UnicastProcessor<String> processor;

    /**
     * The "thinking" text emitted since the current turn began, whether or not a browser was
     * connected. It is what the pane was showing, and what a browser that left the conversation and
     * came back needs in order to show it again; the stream itself keeps nothing for a browser
     * that is not connected. Cleared when the turn's answer, result or error is emitted, since the
     * pane drops its activity text at those events too.
     */
    private final StringBuilder activity = new StringBuilder();

    public SseConnection(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Opens a fresh stream, replacing any previous one. Called by {@code ChatResource} when the
     * browser opens the SSE endpoint (including on reconnect). If a turn is in progress, the
     * stream begins with the activity text so far, so the pane shows what it showed before.
     *
     * @return the {@link UnicastProcessor}, itself a {@code Multi<String>} the REST layer returns
     */
    public UnicastProcessor<String> openStream() {
        processor = UnicastProcessor.create();
        if (!activity.isEmpty()) {
            send(processor, ChatEvent.thinkingSnapshot(activity.toString()));
        }
        return processor;
    }

    /**
     * Serializes {@code event} to JSON and pushes it to the open stream. A no-op if no browser
     * is currently connected (dropped silently, matching {@code ChatSession}'s other emitters).
     *
     * @param event the event to push
     */
    public void emit(ChatEvent event) {
        remember(event);
        UnicastProcessor<String> p = processor;
        if (p == null) return;
        send(p, event);
    }

    private void remember(ChatEvent event) {
        switch (event.type()) {
            case "thinking" -> {
                if (event.content() == null) return;
                activity.append(event.content());
                if (activity.length() > ACTIVITY_TAIL_CHARS) {
                    activity.delete(0, activity.length() - ACTIVITY_TAIL_CHARS);
                }
            }
            case "delta", "result", "error" -> activity.setLength(0);
            default -> { }
        }
    }

    private void send(UnicastProcessor<String> p, ChatEvent event) {
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
