package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.plugin.OpenAiCompatProviderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A request that names a conversation which does not exist must not create it. A conversation is
 * made by {@code createChat} alone, which a caller asks for.
 */
@DisplayName("会話は作成を頼まれたときだけ作られる")
class ChatIsMadeOnlyWhenAskedTest {

    private static ChatUiActorSystem started() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        return system;
    }

    @Test
    @DisplayName("設定を変える操作は、無い会話に対して false を返し、会話を作らない")
    void settersOnAMissingConversation_answerFalseAndCreateNothing() {
        ChatUiActorSystem system = started();

        assertFalse(system.setModel("project1", "09", "any-model"));
        assertFalse(system.setNoThink("project1", "09", true));
        assertFalse(system.setEffort("project1", "09", "high"));
        assertFalse(system.setTemperature("project1", "09", 0.2));

        assertFalse(system.hasChat("project1", "09"));
    }

    @Test
    @DisplayName("無い会話の提供元を替えようとすると理由つきで拒否し、会話を作らない")
    void setProviderOnAMissingConversation_isRefusedAndCreatesNothing() {
        ChatUiActorSystem system = started();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> system.setProvider("project1", "09", OpenAiCompatProviderFactory.KIND, ToolSet.FULL));

        assertTrue(refused.getMessage().contains("no such conversation: project1/chat-09"), refused.getMessage());
        assertFalse(system.hasChat("project1", "09"));
    }

    @Test
    @DisplayName("作成を頼むと採番されて存在し、そこへの操作は通る")
    void aConversationMadeByCreateChat_existsAndTakesSettings() {
        ChatUiActorSystem system = started();

        String chatId = system.createChat("project1");

        assertTrue(system.hasChat("project1", chatId));
        assertTrue(system.setNoThink("project1", chatId, true));
    }
}
