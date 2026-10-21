package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Deleting one conversation from the Project Property tab ({@code ConversationDeletion_261011_oo01}).
 */
@Tag("ConversationDeletion_261011_oo01")
@DisplayName("会話を 1 つ消す")
class ConversationDeletionTest {

    private static List<String> namesIn(ActorNode node) {
        List<String> names = new ArrayList<>();
        names.add(node.name());
        for (ActorNode child : node.children()) names.addAll(namesIn(child));
        return names;
    }

    @Test
    @DisplayName("消した会話はどこからも引けなくなり、同じ project の他の会話は残る")
    void deleteChat_removesOnlyThatConversation() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String projectId = system.createProject();
        system.createChat(projectId);                       // 02

        ChatUiActorSystem.ChatDeletion outcome = system.deleteChat(projectId, "02");

        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, outcome.kind(), outcome.reason());
        assertNull(system.getChat(projectId, "02"));
        assertNull(system.getChatSession(projectId, "02"));
        assertNull(system.getPromptQueue(projectId, "02"), "the queue actor must leave the registry");
        assertNull(system.getSseConnection(projectId, "02"), "the sse actor must leave the registry");
        assertFalse(namesIn(system.getActorTree()).contains(ChatUiActorSystem.chatActorName(projectId, "02")));
        assertNotNull(system.getChatSession(projectId, "01"), "the other conversation stays");
    }

    @Test
    @DisplayName("project1 の chat-01、無い会話、project の最後の会話は消さない")
    void refusals_changeNothing() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.createChat("project1");                      // 02, so 01 is not the last one
        String lone = system.createProject();               // only 01

        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DEFAULT_CHAT, system.deleteChat("project1", "01").kind());
        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.UNKNOWN, system.deleteChat("project1", "99").kind());
        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.LAST_CHAT, system.deleteChat(lone, "01").kind());
        assertNotNull(system.getChatSession("project1", "01"));
        assertNotNull(system.getChatSession(lone, "01"));
    }

    @Test
    @DisplayName("待ち行列に発言が残っている会話は消さない")
    void queuedPrompt_isRefused() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String projectId = system.createProject();
        system.createChat(projectId);                       // 02
        ChatSessionIIAR session = system.getChatSession(projectId, "02");
        system.getPromptQueue(projectId, "02").ask(q -> {
            q.enqueue("later", null, "queue", e -> { }, session.asChatSessionRef(), "human", null,
                    new java.util.concurrent.CompletableFuture<Void>(), false, false);
            return null;
        }).get();

        ChatUiActorSystem.ChatDeletion outcome = system.deleteChat(projectId, "02");

        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.BUSY, outcome.kind());
        assertTrue(outcome.reason().contains("queued"), outcome.reason());
        assertNotNull(system.getChatSession(projectId, "02"));
    }

    @Test
    @DisplayName("下に会話がぶら下がっている会話は消さない")
    void conversationWithANestedOne_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        String projectId = system.createProject();
        system.createChat(projectId, "02");
        system.createChat(projectId, "03", "02");

        ChatUiActorSystem.ChatDeletion outcome = system.deleteChat(projectId, "02");

        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.HAS_NESTED, outcome.kind());
        assertTrue(outcome.reason().contains("chat-03"), outcome.reason());
        assertNotNull(system.getChatSession(projectId, "03"));
    }

    @Test
    @DisplayName("消した会話は再起動しても戻らず、セッションは残り、番号は使い回さない")
    void deletedConversation_staysGone_andItsNumberIsNotReused(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("chat-delete-test"))) {
            IoLogStore log = systems.log;
            ChatUiActorSystem first = systems.start();
            String projectId = first.createProject();
            log.ensureSession(ChatUiActorSystem.chatActorName(projectId, "01"));
            assertEquals("02", first.createChat(projectId));
            long session = log.ensureSession(ChatUiActorSystem.chatActorName(projectId, "02"));

            assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, first.deleteChat(projectId, "02").kind());
            assertNotEquals("02", first.createChat(projectId), "the number of a recorded conversation is not handed out again");

            ChatUiActorSystem restarted = systems.start();
            assertNull(restarted.getChatSession(projectId, "02"), "the deleted conversation came back");
            assertTrue(log.store().listSessions(50).stream().anyMatch(s -> s.getSessionId() == session),
                    "the deleted conversation's session must stay in the log");
        }
    }
}
