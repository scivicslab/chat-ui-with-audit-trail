package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * After a restart, a received request is closed with a notice only when no turn will finish it
 * ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}).
 */
@Tag("TurnWithoutAnAnswerTellsTheAsker_261119_oo01")
class OpenRequestsCutByRestartTest {

    /** Only a request whose turn was cut is closed: not one waiting on its own sent requests, nor one a resume will finish. */
    @Test
    void cutByRestart_closesOnlyRequestsNoTurnWillFinish() {
        OpenRequests open = new OpenRequests();
        CompletableFuture<TurnReply> cut = new CompletableFuture<>();
        CompletableFuture<TurnReply> delegating = new CompletableFuture<>();
        CompletableFuture<TurnReply> resumed = new CompletableFuture<>();
        open.received("project1/chat-01:s5:t365:r1", "project1/chat-01", cut);
        open.received("project1/chat-01:s5:t364:r1", "project1/chat-01", delegating);
        open.childSent("project1/chat-01:s5:t364:r1", "project1/chat-24:s32:t5:r1", "project1/chat-01", 0L);
        open.received("project1/chat-01:s5:t428:r2", "project1/chat-01", resumed);

        List<String> closed = open.cutByRestart(Set.of("project1/chat-01:s5:t428:r2"), "the instance restarted during its turn");

        assertEquals(List.of("project1/chat-01:s5:t365:r1"), closed);
        assertTrue(cut.isDone() && cut.join().reply() == null
                && "the instance restarted during its turn".equals(cut.join().ending()), String.valueOf(cut));
        assertTrue(!delegating.isDone(), "it waits for chat-24's own request, by design");
        assertTrue(!resumed.isDone(), "the queued resume finishes it");
    }

}
