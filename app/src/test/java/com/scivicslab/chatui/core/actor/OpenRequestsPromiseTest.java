package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A pending expect_report promise is part of the open requests ({@code AutomaticReplyDecision_261119_oo01}). */
@Tag("AutomaticReplyDecision_261119_oo01")
class OpenRequestsPromiseTest {

    /** The pending promise is part of the open requests, recorded and put back after a restart. */
    @Test
    void pendingPromise_isRecordedAndPutBackWithTheOpenRequests() {
        OpenRequests first = new OpenRequests();
        first.sentWithoutAnOriginal("project1/chat-01:s47:t1378:r1", "project1/chat-02", 1_000L);
        first.promised("project1/chat-01:s47:t1378:r1", "project1/chat-02", 3_601_000L);
        OpenRequests second = new OpenRequests();
        second.load(new org.json.JSONObject(first.toJson().toString()), (id, asker, urgent, link) -> new CompletableFuture<>());
        assertEquals(java.util.Set.of("project1/chat-01:s47:t1378:r1"), second.promisedRequests());
    }

    /** The follow-up of a request that was still out when the report was promised leaves it out, with a new clock. */
    @Test
    void followUpOfARequestStillOut_keepsItOutFromNow_andEndsThePromise() {
        OpenRequests open = new OpenRequests();
        open.sentWithoutAnOriginal("project1/chat-01:s47:t1378:r1", "project1/chat-02", 1_000L);
        open.promised("project1/chat-01:s47:t1378:r1", "project1/chat-02", 3_601_000L);
        open.followUpArrived("project1/chat-01:s47:t1378:r1", 3_700_000L);
        assertEquals(java.util.Set.of(), open.promisedRequests());
        assertEquals(1, open.outstandingChildren().size());
        assertEquals(3_700_000L, open.outstandingChildren().get(0).sentAt());
    }

    /** The real reply ends the promise and the request together. */
    @Test
    void replyOfAPromisedRequest_endsThePromiseAndTheRequest() {
        OpenRequests open = new OpenRequests();
        open.sentWithoutAnOriginal("project1/chat-01:s47:t1378:r1", "project1/chat-02", 1_000L);
        open.promised("project1/chat-01:s47:t1378:r1", "project1/chat-02", 3_601_000L);
        open.replyArrived("project1/chat-01:s47:t1378:r1");
        assertEquals(java.util.Set.of(), open.promisedRequests());
        assertEquals(0, open.outstandingChildren().size());
    }
}
