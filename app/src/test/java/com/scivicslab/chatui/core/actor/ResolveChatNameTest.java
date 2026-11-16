package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ways a tool's {@code chatId} may name a conversation all resolve to the same actor name. */
class ResolveChatNameTest {

    @Test
    void projectSlashChatDashNN_projectSlashNN_chatDashNN_andNN_allNameTheSameConversation() {
        assertEquals("project1/chat-01", ChatUiActorSystem.resolveChatName("project1", "project1/chat-01"));
        assertEquals("project1/chat-01", ChatUiActorSystem.resolveChatName("project1", "project1/01"));
        assertEquals("project1/chat-01", ChatUiActorSystem.resolveChatName("project1", "chat-01"));
        assertEquals("project1/chat-01", ChatUiActorSystem.resolveChatName("project1", "01"));
        assertEquals("project2/chat-03", ChatUiActorSystem.resolveChatName("project1", "project2/chat-03"));
    }
}
