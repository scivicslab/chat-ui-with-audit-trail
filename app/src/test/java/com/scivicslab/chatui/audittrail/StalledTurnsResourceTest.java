package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.StalledTurnRecovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JSON the screen reads for stalled-turn recovery ({@code tasks/stalled-turn-recovery.md}): the field names and
 * the units are what the UI is written against.
 */
class StalledTurnsResourceTest {

    @Test
    void theListing_namesTheSettingsInMinutes_andHasAnEmptyListWhenNothingIsStalled() {
        StalledTurnRecovery recovery = new StalledTurnRecovery(Map::of, System::currentTimeMillis,
                10 * 60_000, 0, 5_000);
        Map<String, Object> body = StalledTurnsResource.listing(recovery);
        assertEquals(List.of("thresholdMinutes", "autoRecoverMinutes", "stalled"), List.copyOf(body.keySet()));
        assertEquals(10L, body.get("thresholdMinutes"));
        assertEquals(0L, body.get("autoRecoverMinutes"));
        assertEquals(List.of(), body.get("stalled"));
    }

    @Test
    void aStalledConversation_hasTheFieldsTheScreenReads() throws Exception {
        java.lang.reflect.RecordComponent[] fields = StalledTurnRecovery.Stalled.class.getRecordComponents();
        assertEquals(List.of("projectId", "chatId", "reason", "callStartedAt", "silentMillis", "queued"),
                java.util.Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getName).toList(),
                "the listing writes these as projectId, chatId, reason, callStartedAt, silentSeconds, queued");
    }

    @Test
    void anOutcome_hasTheFieldsTheScreenReads() {
        Map<String, Object> m = StalledTurnsResource.outcome(
                new StalledTurnRecovery.Outcome("project1", "57", "recovered", "kill", 19));
        assertEquals(List.of("projectId", "chatId", "outcome", "how", "queued"), List.copyOf(m.keySet()));
        assertEquals("recovered", m.get("outcome"));
        assertEquals(19, m.get("queued"));
    }
}
