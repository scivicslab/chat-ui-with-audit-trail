package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.scivicslab.chatui.agent.OrganizationTools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation that has never run a turn comes back after a restart, with what it was set to
 * ({@code ConversationRestoreOnRestart_260904_oo01}). Before, only a conversation with a session in the I/O log
 * came back, and a conversation got one only when it worked or a setting of its own was recorded; one made by
 * {@code create_chat} or REST and given only a superior and a role was lost, and both lines with it.
 */
class NewConversationSurvivesRestartTest {

    @Test
    void aConversationThatNeverRanATurnComesBackWithItsSettingsItsSuperiorAndItsRoleEdge(@TempDir Path dir)
            throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("new-conversation"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            String provider = first.getChatSession("project1", "02").getProviderIdDirect();
            String tools = first.getChatSession("project1", "02").getToolSetDirect();
            String model = first.getChatSession("project1", "02").getModelDirect();
            assertTrue(OrganizationTools.setSuperior(first, "project1", "01", "02", "01").startsWith("ok"));
            assertTrue(first.setCollaborator("project1/chat-01", "01", "worker", "02").startsWith("ok"));

            ChatUiActorSystem second = systems.start();
            ChatSessionIIAR back = second.getChatSession("project1", "02");
            assertNotNull(back, "the conversation that never ran a turn came back");
            assertEquals(provider, back.getProviderIdDirect(), "its provider");
            assertEquals(tools, back.getToolSetDirect(), "its tool set");
            assertEquals(model, back.getModelDirect(), "its model");
            assertTrue(!back.isBusyDirect(), "idle");
            ConversationGraph g = second.conversationGraph("project1");
            assertTrue(g.superiorEdges().contains(new ConversationGraph.SuperiorEdge("01", "02")),
                    "its superior line: " + g.superiorEdges());
            assertEquals(List.of(new ConversationGraph.RoleEdge("01", "worker", "02", "project1")), g.roleEdges(),
                    "the role edge that names it");
        }
    }

    @Test
    void aNameAndAModelSetBeforeTheFirstTurnComeBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("new-conversation-settings"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertTrue(first.setChatName("project1", "02", "worker A").startsWith("ok"));
            assertTrue(first.setModel("project1", "02", "model-x"));

            ChatUiActorSystem second = systems.start();
            assertNotNull(second.getChatSession("project1", "02"));
            assertEquals("worker A", second.getChatName("project1", "02"));
            assertEquals("model-x", second.getChatSession("project1", "02").getModelDirect());
        }
    }

    @Test
    void aConversationDeletedBeforeItsFirstTurnStaysDeleted(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("new-conversation-deleted"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertTrue(OrganizationTools.setSuperior(first, "project1", "01", "02", "01").startsWith("ok"));
            assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, first.deleteChat("project1", "02").kind());

            ChatUiActorSystem second = systems.start();
            assertNull(second.getChatSession("project1", "02"), "the deleted conversation did not come back");
            assertTrue(second.conversationGraph("project1").superiorEdges().isEmpty());
        }
    }
}
