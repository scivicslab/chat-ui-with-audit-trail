package com.scivicslab.chatui.core.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.rest.ChatEvent;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A browser that leaves a conversation mid-turn and comes back opens a new stream. The activity
 * text emitted while it was away is what that stream has to begin with.
 */
class SseConnectionReplayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<JsonNode> firstEvents(SseConnection sse, int count) throws Exception {
        AssertSubscriber<String> sub = sse.openStream().subscribe().withSubscriber(AssertSubscriber.create(count));
        sub.awaitItems(count);
        return sub.getItems().stream().map(s -> {
            try { return mapper.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
        }).toList();
    }

    @Test
    void activityEmittedWhileAwayIsReplayedAsOneSnapshot() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.openStream();                       // the browser was here ...
        sse.emit(ChatEvent.thinking("step one "));
        sse.emit(ChatEvent.thinking("step two"));  // ... and has since left; nothing is listening

        List<JsonNode> events = firstEvents(sse, 1);

        assertEquals("thinking_snapshot", events.get(0).get("type").asText());
        assertEquals("step one step two", events.get(0).get("content").asText());
    }

    @Test
    void activityEmittedBeforeAnyBrowserConnectedIsReplayed() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.emit(ChatEvent.thinking("early"));

        assertEquals("early", firstEvents(sse, 1).get(0).get("content").asText());
    }

    @Test
    void aResultAloneEndsTheTurn() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.emit(ChatEvent.thinking("working"));
        sse.emit(ChatEvent.result("s", 0, 1));

        AssertSubscriber<String> sub = sse.openStream().subscribe().withSubscriber(AssertSubscriber.create(1));
        assertTrue(sub.getItems().isEmpty(), "result ends the turn, so the stream opens empty");
    }

    @Test
    void aDeltaAloneEndsTheActivity() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.emit(ChatEvent.thinking("working"));
        sse.emit(ChatEvent.delta("the answer"));

        AssertSubscriber<String> sub = sse.openStream().subscribe().withSubscriber(AssertSubscriber.create(1));
        assertTrue(sub.getItems().isEmpty(), "the pane drops its activity text at the answer, so none is replayed");
    }

    @Test
    void anErrorEndsTheTurnToo() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.emit(ChatEvent.thinking("working"));
        sse.emit(ChatEvent.error("boom"));

        AssertSubscriber<String> sub = sse.openStream().subscribe().withSubscriber(AssertSubscriber.create(1));
        assertTrue(sub.getItems().isEmpty());
    }

    @Test
    void onlyTheTailIsKept() throws Exception {
        SseConnection sse = new SseConnection(mapper);
        sse.emit(ChatEvent.thinking("x".repeat(SseConnection.ACTIVITY_TAIL_CHARS)));
        sse.emit(ChatEvent.thinking("END"));

        String content = firstEvents(sse, 1).get(0).get("content").asText();

        assertEquals(SseConnection.ACTIVITY_TAIL_CHARS, content.length());
        assertTrue(content.endsWith("END"));
    }
}
