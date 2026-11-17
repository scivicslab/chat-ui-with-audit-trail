package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation's settings are written by the actors that hold them ({@code EachActorRecordsItsOwnState_261118_oo01},
 * step 6): the timekeeper its limits as its own {@code timekeeper} entry, the chat session the rest as {@code settings},
 * the squad its {@code name}; and nothing under the housekeeper gathers them any more.
 */
class ConversationOwnersRecordTest {

    private static SessionEntries of(SystemsOnOneLog systems, String chat) {
        return SessionEntries.ofConversation(systems.log, IoLogView.over(systems.log), chat);
    }

    @Test
    void theTimekeeperRecordsItsLimitsInItsOwnEntryAndTheyComeBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("limits"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            first.setTimekeeperLimits("project1", "02", 7L, 3L);
            assertTrue(first.setModel("project1", "02", "model-x"));

            SessionEntries chat = of(systems, "project1/chat-02");
            org.json.JSONObject limits = new org.json.JSONObject(chat.latest(Timekeeper.TIMEKEEPER_LABEL));
            assertEquals(7, limits.getInt("urgentWaitMinutes"));
            assertEquals(3, limits.getInt("childReplyWarnMinutes"));
            org.json.JSONObject settings = new org.json.JSONObject(chat.latest(IoLogView.SETTINGS_LABEL));
            assertEquals("model-x", settings.getString("model"));
            assertFalse(settings.has("urgentWaitMinutes"), "the settings entry no longer carries the limits: " + settings);

            ChatUiActorSystem second = systems.start();
            assertArrayEquals(new long[] {7, 3}, second.setTimekeeperLimits("project1", "02", null, null));
            assertEquals("model-x", second.getChatSession("project1", "02").getModelDirect());
        }
    }

    @Test
    void limitsInAnEarlierSettingsEntryComeBackAndAreThenWrittenAsTheTimekeepersOwn(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("earlier-limits"))) {
            SessionEntries chat = of(systems, "project1/chat-02");
            // Exactly the shape ChatUiActorSystem.recordSettings wrote before.
            chat.write(IoLogView.SETTINGS_LABEL, "{\"provider\":\"openai-compat\",\"tools\":\"full\",\"model\":\"old-model\","
                    + "\"temperature\":null,\"urgentWaitMinutes\":9,\"childReplyWarnMinutes\":4,\"noThink\":true,\"effort\":null}");

            ChatUiActorSystem system = systems.start();
            assertNotNull(system.getChatSession("project1", "02"));
            assertArrayEquals(new long[] {9, 4}, system.setTimekeeperLimits("project1", "02", null, null));
            assertEquals("old-model", system.getChatSession("project1", "02").getModelDirect());
            assertTrue(system.getChatSession("project1", "02").isNoThinkDirect());
            assertNotNull(chat.latest(Timekeeper.TIMEKEEPER_LABEL), "the limits were written as the timekeeper's own entry");
        }
    }

    @Test
    void nothingUnderTheHousekeeperGathersConversationSettings() {
        ChatUiActorSystem system = StartedSystem.start();
        ActorNode housekeeper = system.getActorTree().children().stream()
                .filter(n -> ChatUiActorSystem.HOUSEKEEPER.equals(n.name())).findFirst().orElseThrow();
        List<String> names = housekeeper.children().stream().map(ActorNode::name).toList();
        assertFalse(names.contains("conversationRegistry"), "no conversation registry: " + names);
    }
}
