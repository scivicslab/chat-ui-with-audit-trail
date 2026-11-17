package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;
import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A promised report registered with {@code expect_report} survives a restart: the timekeeper records it in its own
 * {@code timekeeper} entry with its due time and its reminder number, puts it back at start-up, and the count goes on
 * from there; one whose time came while the instance was down is given at the first check
 * ({@code EachActorRecordsItsOwnState_261118_oo01}, step 7).
 */
class PromisedReportsSurviveRestartTest {

    private static List<PromisedReports.Pending> pending(ChatUiActorSystem system) throws Exception {
        return system.timekeeperOf("project1", "02").ask(Timekeeper::pendingPromises).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aPromiseComesBackWithItsTimeAndItsNumberAndTheCountGoesOn(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("promises"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            ActorRef<Timekeeper> keeper = first.timekeeperOf("project1", "02");
            assertEquals(1, keeper.ask(k -> k.expectReport("project1/chat-01:s1:t1:r1", "project1/chat-01", 30)).get(5, TimeUnit.SECONDS));
            assertEquals(2, keeper.ask(k -> k.expectReport("project1/chat-01:s1:t1:r1", "project1/chat-01", 30)).get(5, TimeUnit.SECONDS));
            assertEquals(1, keeper.ask(k -> k.expectReport(null, null, 45)).get(5, TimeUnit.SECONDS), "a promise to the person");
            List<PromisedReports.Pending> before = pending(first);
            assertEquals(2, before.size());

            ChatUiActorSystem second = systems.start();
            assertEquals(before, pending(second), "the same promises, due at the same moments, with the same numbers");
            assertEquals(3, second.timekeeperOf("project1", "02")
                    .ask(k -> k.expectReport("project1/chat-01:s1:t1:r1", "project1/chat-01", 30)).get(5, TimeUnit.SECONDS),
                    "the count of reminders for the request goes on after the restart");
        }
    }

    @Test
    void aPromiseThatFellDueWhileTheInstanceWasDownIsGivenAtTheFirstCheckAndThatRecorded(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("due"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            first.timekeeperOf("project1", "02")
                    .ask(k -> k.expectReport("project1/chat-01:s1:t1:r7", "project1/chat-01", 0)).get(5, TimeUnit.SECONDS);

            ChatUiActorSystem second = systems.start();
            ActorRef<Timekeeper> keeper = second.timekeeperOf("project1", "02");
            assertEquals(1, pending(second).size(), "back, and already due");
            keeper.tell(k -> k.tick(System.currentTimeMillis())).get(5, TimeUnit.SECONDS);
            assertTrue(pending(second).isEmpty(), "given at the first check");

            org.json.JSONObject entry = new org.json.JSONObject(SessionEntries.ofConversation(systems.log,
                    IoLogView.over(systems.log), "project1/chat-02").latest(Timekeeper.TIMEKEEPER_LABEL));
            assertTrue(entry.getJSONObject("promised").getJSONArray("promises").isEmpty(),
                    "the reminder given is recorded, so a restart now does not give it again: " + entry);
        }
    }
}
