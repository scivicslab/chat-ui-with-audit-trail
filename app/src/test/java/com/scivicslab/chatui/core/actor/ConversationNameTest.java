package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A conversation's own name, set from the Project Property tab as a project's is
 * ({@code ProjectProperty_260929_oo01}).
 *
 * <p>No I/O log here: {@code ChatUiActorSystem.init()} outside CDI leaves {@code ioLogStore} null,
 * so these tests read the name straight off the actor. Reading the recorded entry back is
 * {@code ConversationNameRecordTest}.</p>
 */
@Tag("ProjectProperty_260929_oo01")
@DisplayName("会話の名前")
class ConversationNameTest {

    private static ActorNode find(ActorNode node, String name) {
        if (name.equals(node.name())) return node;
        for (ActorNode child : node.children()) {
            ActorNode hit = find(child, name);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    @DisplayName("付けた名前がそのまま読み出せる")
    void setChatName_readsBackTheSameString() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.createChat("project1");

        String outcome = system.setChatName("project1", "02", "  文献調査  ");

        assertTrue(outcome.startsWith("ok:"), outcome);
        assertEquals("文献調査", system.getChatName("project1", "02"));
        assertNull(system.getChatName("project1", "01"), "the other conversation keeps no name");
    }

    @Test
    @DisplayName("空文字を渡すと名前が無い状態に戻る")
    void setChatName_blank_clearsIt() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.setChatName("project1", "01", "いちど付けた名前");

        system.setChatName("project1", "01", "");

        assertNull(system.getChatName("project1", "01"));
    }

    @Test
    @DisplayName("無い会話に名前を付けようとすると理由が返る")
    void setChatName_unknownConversation_saysSo() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertTrue(system.setChatName("project1", "99", "x").startsWith("error:"));
    }

    @Test
    @DisplayName("名前を付けた会話は、アクターツリーで名前と id の両方を名乗る")
    void actorTree_namedConversation_carriesBoth() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.setChatName("project1", "01", "表示される名前");

        ActorNode chat = find(system.getActorTree(), "project1/chat-01");

        assertNotNull(chat, "the conversation is in the tree");
        assertEquals("表示される名前", chat.displayName());
        assertNull(find(system.getActorTree(), "project1/chat-01.chat").displayName(),
                "the conversation's members carry no name");
    }
}
