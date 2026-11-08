package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reports a conversation has promised and its timekeeper is to ask for, on the plain object alone
 * ({@code ReplyDecisionAndPromisedReportTests_261108_oo01}).
 */
@Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
@DisplayName("PromisedReports — 約束の登録と、1回使い切りの催促")
class PromisedReportsTest {

    @Test
    @DisplayName("時刻が来た約束だけが催促になり、催促になった約束は消える")
    void onlyDuePromisesBecomeRemindersAndAreThenGone() {
        PromisedReports p = new PromisedReports();
        p.expect("project1/chat-01:t1:r1", "project1/chat-01", 1_000);
        p.expect("project1/chat-01:t1:r2", "project1/chat-01", 5_000);

        assertEquals(List.of(), p.due(999));
        assertEquals(List.of(new PromisedReports.Reminder("project1/chat-01:t1:r1", "project1/chat-01", 1)), p.due(1_000));
        assertEquals(List.of(), p.due(2_000), "a reminder is given once");
        assertEquals(1, p.due(5_000).size());
        assertEquals(0, p.pending());
    }

    @Test
    @DisplayName("同じ依頼を登録し直すと、催促の回数が1つ増える")
    void registeringTheSameRequestAgainCountsTheReminders() {
        PromisedReports p = new PromisedReports();
        assertEquals(1, p.expect("r1", "project1/chat-01", 1_000));
        p.due(1_000);
        assertEquals(2, p.expect("r1", "project1/chat-01", 2_000), "the answer was 'not yet', so it is registered again");
        assertEquals(2, p.due(2_000).get(0).number());
    }

    @Test
    @DisplayName("依頼 ID の無い登録は、画面への約束になる")
    void aRegistrationWithoutARequestIsAPromiseToThePerson() {
        PromisedReports p = new PromisedReports();
        p.expect(null, null, 1_000);
        PromisedReports.Reminder r = p.due(1_000).get(0);
        assertTrue(r.toScreen());
        assertEquals(1, r.number());
    }
}
