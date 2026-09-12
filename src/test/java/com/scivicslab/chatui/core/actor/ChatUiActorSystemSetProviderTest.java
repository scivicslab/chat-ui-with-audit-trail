package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code CliHarnessProvider_260912_oo01}: switching a conversation's provider
 * kind replaces the provider actor under the same name, and the ChatSession sees the new kind and
 * tool set. No CLI process is started — a harness provider only starts one on its first prompt.
 */
class ChatUiActorSystemSetProviderTest {

    @Test
    void setProvider_claude_replacesTheProviderActorUnderTheSameNameAndUpdatesTheSession() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.createChat("project1", "01");
        ActorRef<LlmProvider> before = system.getProviderRef("project1", "01");
        assertNotNull(before);
        ChatSessionIIAR session = system.getChatSession("project1", "01");
        assertEquals("openai-compat", session.getProviderIdDirect());
        assertEquals("full", session.getToolSetDirect());

        system.setProvider("project1", "01", "claude", ToolSet.COLLABORATION);

        ActorRef<LlmProvider> after = system.getProviderRef("project1", "01");
        assertNotNull(after);
        assertNotSame(before, after);
        assertEquals(before.getName(), after.getName(), "the new provider takes the old one's name");
        assertTrue(session.getNamesOfChildren().contains(after.getName()));
        assertEquals(1, session.getNamesOfChildren().stream().filter(n -> n.endsWith(".provider")).count());
        assertEquals("claude", after.ask(LlmProvider::id).get(5, TimeUnit.SECONDS));
        // The ChatSession learns of it on its own thread; wait for that message to land.
        session.ask(a -> ((ChatSession) a).getProviderId()).get(5, TimeUnit.SECONDS);
        assertEquals("claude", session.getProviderIdDirect());
        assertEquals("collaboration", session.getToolSetDirect());
        assertTrue(session.ask(a -> ((ChatSession) a).getAvailableModels()).get(5, TimeUnit.SECONDS)
                .stream().anyMatch(m -> "sonnet".equals(m.name())), "the model list is now Claude's");
    }

    @Test
    void setProvider_openAiCompatWithCollaborationTools_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        assertThrows(IllegalArgumentException.class,
                () -> system.setProvider("project1", "01", "openai-compat", ToolSet.COLLABORATION));
        assertThrows(IllegalArgumentException.class,
                () -> system.setProvider("project1", "01", "no-such-kind", ToolSet.FULL));
    }
}
