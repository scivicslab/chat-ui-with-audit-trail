package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenRequestsTest {

    private static TurnReply answer(String text, String turn) {
        return new TurnReply(text, null, turn);
    }

    @Test
    void aRequestWithNoChildRequestsIsAnsweredByTheTurnItStarted() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        assertSame(done, open.turnEnded("a:t1:r1", answer("42", "b:t1")));
        assertEquals("42", done.join().reply());
    }

    @Test
    void theAnswerWaitsUntilEveryChildRequestHasReturned() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        open.childSent("a:t1:r1", "b:t1:r2");
        assertNull(open.turnEnded("a:t1:r1", answer("sent both", "b:t1")), "the first turn only handed the work out");
        assertFalse(done.isDone());

        assertEquals("a:t1:r1", open.replyArrived("b:t1:r1"));
        assertNull(open.turnEnded("a:t1:r1", answer("one back", "b:t2")), "one child request is still out");
        assertFalse(done.isDone());

        assertEquals("a:t1:r1", open.replyArrived("b:t1:r2"));
        open.turnEnded("a:t1:r1", answer("final report", "b:t3"));
        assertEquals("[answer of turn b:t1]\nsent both\n\n[answer of turn b:t2]\none back\n\n"
                + "[answer of turn b:t3]\nfinal report", done.join().reply());
        assertEquals("b:t3", done.join().turnId());
    }

    @Test
    void aReportWrittenWhileARequestWasStillOutIsNotLost() {
        // chat-38's case: the full report came in a turn that ended with one request still out, and
        // the turn that saw the last reply return only said the report had been sent.
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        open.childSent("a:t1:r1", "b:t1:r2");
        open.replyArrived("b:t1:r1");
        assertNull(open.turnEnded("a:t1:r1", answer("FULL REPORT", "b:t10")));

        open.replyArrived("b:t1:r2");
        open.turnEnded("a:t1:r1", new TurnReply("the report has been sent", "[ended: answered]", "b:t13"));

        TurnReply r = done.join();
        assertTrue(r.reply().contains("[answer of turn b:t10]\nFULL REPORT"), r.reply());
        assertTrue(r.reply().endsWith("[answer of turn b:t13]\nthe report has been sent"), r.reply());
        assertEquals("[ended: answered]", r.ending());
    }

    @Test
    void aFailedLastTurnStillDeliversTheEarlierAnswers() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        open.turnEnded("a:t1:r1", answer("REPORT", "b:t1"));
        open.replyArrived("b:t1:r1");
        open.turnEnded("a:t1:r1", new TurnReply(null, "its turn failed: timeout", "b:t2"));

        TurnReply r = done.join();
        assertEquals("[answer of turn b:t1]\nREPORT", r.reply());
        assertEquals("its turn failed: timeout", r.ending());
    }

    @Test
    void aCorrectionSentFromAReplyTurnIsAChildRequestToo() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        open.turnEnded("a:t1:r1", answer("sent", "b:t1"));
        String original = open.replyArrived("b:t1:r1");
        open.childSent(original, "b:t2:r1");
        assertNull(open.turnEnded(original, answer("asked for a fix", "b:t2")));
        assertEquals(original, open.replyArrived("b:t2:r1"));
        open.turnEnded(original, answer("fixed and checked", "b:t3"));
        assertTrue(done.join().reply().endsWith("[answer of turn b:t3]\nfixed and checked"), done.join().reply());
    }

    @Test
    void aRequestIsAnsweredOnceAndItsAskerIsKeptForAFollowUp() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "project1/chat-01", done);
        assertNull(open.askerIfAnswered("a:t1:r1"));
        open.turnEnded("a:t1:r1", answer("will report later", "b:t1"));
        assertNull(open.turnEnded("a:t1:r1", answer("again", "b:t2")), "a second answer is not sent");
        assertEquals("will report later", done.join().reply());
        assertEquals("project1/chat-01", open.askerIfAnswered(open.last()));
    }

    @Test
    void anAnsweredRequestIsForgottenWhenTheNextOneArrives() {
        OpenRequests open = new OpenRequests();
        open.received("a:t1:r1", "a", new CompletableFuture<>());
        open.turnEnded("a:t1:r1", answer("x", "b:t1"));
        open.received("a:t2:r1", "a", new CompletableFuture<>());
        assertEquals(-1, open.outstanding("a:t1:r1"));
        assertEquals(0, open.outstanding("a:t2:r1"));
    }

    @Test
    void aReplyToARequestNotSentForAnyOpenRequestBelongsToNone() {
        OpenRequests open = new OpenRequests();
        assertNull(open.replyArrived("b:t1:r1"));
        open.childSent("nothing-open", "b:t1:r2");
        assertNull(open.replyArrived("b:t1:r2"));
        assertTrue(open.last() == null);
    }
    /** The report a returned child request promised makes the child request count as out again. */
    @Test
    void aReturnedChildRequestCanBeReopenedAndTheAnswerWaitsForItsReport() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        assertNull(open.turnEnded("a:t1:r1", answer("asked b", "m:t1")));
        assertEquals("a:t1:r1", open.replyArrived("b:t1:r1"), "b said it would report later");

        assertEquals(OpenRequests.Reopening.REOPENED, open.reopen("b:t1:r1", "b", 1_000L));
        assertNull(open.turnEnded("a:t1:r1", answer("waiting for b", "m:t2")), "the request waits for b's report");
        assertFalse(done.isDone());
        assertEquals(1, open.outstanding("a:t1:r1"));
        assertEquals(1_000L, open.outstandingChildren().get(0).sentAt(), "the no-reply timer counts from the reopening");

        assertEquals("a:t1:r1", open.replyArrived("b:t1:r1"), "the follow-up passes through like a reply");
        assertSame(done, open.turnEnded("a:t1:r1", answer("final", "m:t3")));
        assertTrue(done.join().reply().contains("[answer of turn m:t2]\nwaiting for b"), done.join().reply());
        assertTrue(done.join().reply().endsWith("[answer of turn m:t3]\nfinal"), done.join().reply());
    }

    @Test
    void aChildRequestOfARequestAlreadyAnsweredIsNotReopened() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        open.received("a:t1:r1", "a", done);
        open.childSent("a:t1:r1", "b:t1:r1");
        open.replyArrived("b:t1:r1");
        assertSame(done, open.turnEnded("a:t1:r1", answer("answered at once", "m:t1")));

        assertEquals(OpenRequests.Reopening.ALREADY_ANSWERED, open.reopen("b:t1:r1", "b", 1_000L));
        assertEquals(OpenRequests.Reopening.NOT_SENT_FOR_A_REQUEST, open.reopen("x:t9:r9", "x", 1_000L));
    }

    @Test
    void aChildRequestStillOutIsNotReopenedAndAnOpenedOneCanBeClosed() {
        OpenRequests open = new OpenRequests();
        open.received("a:t1:r1", "a", new CompletableFuture<>());
        open.childSent("a:t1:r1", "b:t1:r1");
        assertEquals(OpenRequests.Reopening.STILL_OUT, open.reopen("b:t1:r1", "b", 1_000L));

        open.replyArrived("b:t1:r1");
        open.reopen("b:t1:r1", "b", 1_000L);
        assertEquals(1, open.outstanding("a:t1:r1"));
        assertTrue(open.close("b:t1:r1"), "giving up on the report closes it");
        assertEquals(0, open.outstanding("a:t1:r1"));
        assertFalse(open.close("b:t1:r1"), "closing twice is a no-op");
    }
}
