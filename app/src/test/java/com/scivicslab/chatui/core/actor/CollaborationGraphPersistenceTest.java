package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Role edges survive a restart, saved with the project's settings as the organization is, and go away with a
 * deleted conversation or project ({@code CollaborationGraph_260828_oo01}).
 */
class CollaborationGraphPersistenceTest {

    private static String collaborator(ChatUiActorSystem system, String from, String role) throws Exception {
        return system.getCollaborationGraph().ask(g -> g.getCollaborator(from, role)).get(5, TimeUnit.SECONDS);
    }

    /** Conversations come back after a restart only when the log holds a session for them. */
    private static void recorded(SystemsOnOneLog systems, String projectId, String... chatIds) {
        for (String id : chatIds) systems.log.ensureSession(ChatUiActorSystem.chatActorName(projectId, id));
    }

    @Test
    void aRoleEdgeComesBackAfterARestartAndAReassignmentIsSavedAtOnce(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("roles-restart"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1");
            first.createChat("project1");
            recorded(systems, "project1", "01", "02", "03");
            assertTrue(first.setCollaborator("project1/chat-01", "02", "worker", "03").startsWith("ok"));

            ChatUiActorSystem second = systems.start();
            assertEquals("project1/chat-03", collaborator(second, "project1/chat-02", "worker"),
                    "the edge set before the restart is there after it");
            assertEquals(List.of(new ConversationGraph.RoleEdge("02", "worker", "03", "project1")),
                    second.conversationGraph("project1").roleEdges());

            // A reassignment is written at once: the next restart sees it, with nothing else done in between.
            assertTrue(second.setCollaborator("project1/chat-01", "02", "worker", "01").startsWith("ok"));
            ChatUiActorSystem third = systems.start();
            assertEquals("project1/chat-01", collaborator(third, "project1/chat-02", "worker"));
        }
    }

    @Test
    void deletingAConversationRemovesItsEdgesAndTheEdgesToIt(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("roles-delete-chat"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1");
            first.createChat("project1");
            recorded(systems, "project1", "01", "02", "03");
            assertTrue(first.setCollaborator("project1/chat-01", "02", "worker", "03").startsWith("ok"));
            assertTrue(first.setCollaborator("project1/chat-01", "03", "helper", "02").startsWith("ok"));
            assertTrue(first.setCollaborator("project1/chat-01", "01", "reviewer", "02").startsWith("ok"));

            assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, first.deleteChat("project1", "03").kind());
            assertNull(collaborator(first, "project1/chat-02", "worker"), "the edge to the deleted conversation is gone");
            assertNull(collaborator(first, "project1/chat-03", "helper"), "the edge from it is gone");
            assertEquals("project1/chat-02", collaborator(first, "project1/chat-01", "reviewer"), "other edges stay");

            ChatUiActorSystem second = systems.start();
            assertEquals(List.of(new ConversationGraph.RoleEdge("01", "reviewer", "02", "project1")),
                    second.conversationGraph("project1").roleEdges(), "and the removal was saved");
        }
    }

    @Test
    void deletingAProjectRemovesItsEdgesAndTheEdgesIntoIt(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("roles-delete-project"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1");
            recorded(systems, "project1", "01", "02");
            String other = first.createProject();
            first.createChat(other);
            recorded(systems, other, "01", "02");
            assertTrue(first.setCollaborator("project1/chat-01", "02", "auditor", other + "/02").startsWith("ok"));
            assertTrue(first.setCollaborator("project1/chat-01", "01", "worker", "02").startsWith("ok"));
            assertTrue(first.setCollaborator(other + "/chat-01", "02", "worker", "01").startsWith("ok"));

            assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.DELETED, first.deleteProject(other).kind());
            assertNull(collaborator(first, "project1/chat-02", "auditor"), "the edge into the deleted project is gone");
            assertNull(collaborator(first, other + "/chat-02", "worker"), "the deleted project's own edges are gone");
            assertEquals("project1/chat-02", collaborator(first, "project1/chat-01", "worker"));

            ChatUiActorSystem second = systems.start();
            assertNull(collaborator(second, "project1/chat-02", "auditor"), "and the removal was saved");
            assertEquals("project1/chat-02", collaborator(second, "project1/chat-01", "worker"));
        }
    }

    @Test
    void anEdgeToAConversationThatDidNotComeBackIsDroppedAtStartUp(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("roles-missing"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1");
            first.createChat("project1");
            recorded(systems, "project1", "01", "02");
            assertTrue(first.setCollaborator("project1/chat-01", "01", "worker", "03").startsWith("ok"));
            assertTrue(first.setCollaborator("project1/chat-01", "01", "reviewer", "02").startsWith("ok"));
            // chat-03's session is ended outside deleteChat (as an old log, or a conversation ended by hand,
            // leaves it), so it does not come back while the edge naming it is still saved.
            systems.log.resetSession(ChatUiActorSystem.chatActorName("project1", "03"));

            ChatUiActorSystem second = systems.start();
            assertNull(second.getChatSession("project1", "03"), "chat-03 did not come back");
            assertNull(collaborator(second, "project1/chat-01", "worker"), "so the edge naming it is gone");
            assertEquals("project1/chat-02", collaborator(second, "project1/chat-01", "reviewer"));
        }
    }
}
