package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deleting a project from the Project Property tab ({@code ProjectDeletion_261011_oo01}): it leaves
 * the actor tree and does not come back on restart, its conversations' sessions stay in the log,
 * and {@code project1} cannot be deleted.
 */
@Tag("ProjectDeletion_261011_oo01")
@DisplayName("project を消す")
class ProjectDeletionTest {

    private static List<String> namesIn(ActorNode node) {
        List<String> names = new ArrayList<>();
        names.add(node.name());
        for (ActorNode child : node.children()) names.addAll(namesIn(child));
        return names;
    }

    @Test
    @DisplayName("消した project とその会話は、どこからも引けなくなる")
    void deleteProject_removesTheProjectAndItsConversations() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String projectId = system.createProject();
        system.createChat(projectId);

        ChatUiActorSystem.ProjectDeletion outcome = system.deleteProject(projectId);

        assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.DELETED, outcome.kind(), outcome.reason());
        assertEquals(2, outcome.conversations());
        assertFalse(system.getProjectIds().contains(projectId));
        for (String chatId : List.of("01", "02")) {
            assertNull(system.getChatSession(projectId, chatId));
            assertNull(system.getChat(projectId, chatId));
            assertNull(system.getPromptQueue(projectId, chatId), "the queue actor must leave the registry");
            assertNull(system.getSseConnection(projectId, chatId), "the sse actor must leave the registry");
        }
        for (String name : namesIn(system.getActorTree())) {
            assertFalse(name.startsWith(projectId), "the tree still shows " + name);
        }
        assertTrue(system.getProjectIds().contains("project1"), "other projects stay");
    }

    @Test
    @DisplayName("project1 は消せず、何も変わらない")
    void deleteProject_project1_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        ChatUiActorSystem.ProjectDeletion outcome = system.deleteProject("project1");

        assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.DEFAULT_PROJECT, outcome.kind());
        assertTrue(system.getProjectIds().contains("project1"));
        assertTrue(system.getChatSession("project1", "01") != null);
    }

    @Test
    @DisplayName("無い project を消そうとすると拒まれる")
    void deleteProject_unknown_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.UNKNOWN, system.deleteProject("project99").kind());
    }

    @Test
    @DisplayName("待ち行列に発言が残っている project は消さず、何も変わらない")
    void deleteProject_withAQueuedPrompt_isRefused() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String projectId = system.createProject();
        ChatSessionIIAR session = system.getChatSession(projectId, "01");
        // Held, so it stays in the queue without a model being called.
        system.getPromptQueue(projectId, "01").ask(q -> {
            q.enqueue("later", null, "queue", e -> { }, session.asChatSessionRef(), "human", null,
                    new java.util.concurrent.CompletableFuture<Void>(), false, false);
            return null;
        }).get();

        ChatUiActorSystem.ProjectDeletion outcome = system.deleteProject(projectId);

        assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.BUSY, outcome.kind());
        assertTrue(outcome.reason().contains("queued"), outcome.reason());
        assertTrue(system.getProjectIds().contains(projectId));
        assertTrue(system.getChatSession(projectId, "01") != null);
    }

    @Test
    @DisplayName("消した project は再起動しても戻らず、セッションは残る")
    void deleteProject_doesNotComeBackOnRestart_andKeepsItsSessions(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("delete-test"))) {
            IoLogStore log = systems.log;
            ChatUiActorSystem first = systems.start();
            String projectId = first.createProject();
            long session = log.ensureSession(ChatUiActorSystem.chatActorName(projectId, "01"));
            assertTrue(session >= 0, "the conversation has to be recorded for the test to mean anything");
            assertTrue(log.resumableConversationSquads().contains(ChatUiActorSystem.chatActorName(projectId, "01")));

            assertEquals(ChatUiActorSystem.ProjectDeletion.Kind.DELETED, first.deleteProject(projectId).kind());

            ChatUiActorSystem restarted = systems.start();
            assertFalse(restarted.getProjectIds().contains(projectId),
                    "deleted project came back: " + restarted.getProjectIds());
            assertTrue(log.store().listSessions(50).stream().anyMatch(s -> s.getSessionId() == session),
                    "the deleted project's session must stay in the log");
        }
    }

    @Test
    @DisplayName("再起動の後に作る project も、消した番号を使わない")
    void createProject_afterDeletionAndRestart_doesNotReuseTheNumber(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("number-test"))) {
            ChatUiActorSystem first = systems.start();
            String deleted = first.createProject();
            systems.log.ensureSession(ChatUiActorSystem.chatActorName(deleted, "01"));
            first.deleteProject(deleted);

            ChatUiActorSystem restarted = systems.start();

            assertNotEquals(deleted, restarted.createProject(),
                    "a new project must not take the name a deleted one's sessions are recorded under");
        }
    }

    @Test
    @DisplayName("消した後に作る project は、消した番号を使わない")
    void createProject_afterDeletion_doesNotReuseTheNumber() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String deleted = system.createProject();
        system.deleteProject(deleted);

        assertNotEquals(deleted, system.createProject());
    }
}
