package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code CliHarnessProvider_260912_oo01} and
 * {@code ProviderAndToolPlugins_260912_oo01}: switching a conversation's provider kind replaces
 * the provider actor under the same name, the ChatSession sees the new kind and tool set, and the
 * kind comes from a factory in the registry — here a fake one registered by the test, since the
 * body carries no harness kind of its own.
 */
class ChatUiActorSystemSetProviderTest {

    /** A provider kind the way a plugin jar would add one. */
    private static final class FakeHarnessFactory implements LlmProviderFactory {
        @Override public String kind() { return "fake-harness"; }
        @Override public List<ProviderChoice> choices() {
            return List.of(new ProviderChoice("fake-harness", ToolSet.HARNESS, "Fake harness"));
        }
        @Override public LlmProvider create(ProviderCreationContext ctx) {
            if (ctx.toolSet() != ToolSet.HARNESS) throw new IllegalArgumentException("harness only");
            return new LlmProvider() {
                @Override public String id() { return "fake-harness"; }
                @Override public String displayName() { return "Fake harness"; }
                @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("fake-model", id(), null)); }
                @Override public String getCurrentModel() { return "fake-model"; }
                @Override public void setModel(String model) {}
                @Override public void cancel() {}
                @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {}
            };
        }
    }

    @Test
    void setProvider_registeredKind_replacesTheProviderActorUnderTheSameNameAndUpdatesTheSession() throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.getPluginRegistry().register(new FakeHarnessFactory());
        system.createChat("project1", "01");
        ActorRef<LlmProvider> before = system.getProviderRef("project1", "01");
        assertNotNull(before);
        ChatSessionIIAR session = system.getChatSession("project1", "01");
        assertEquals("openai-compat", session.getProviderIdDirect());
        assertEquals("full", session.getToolSetDirect());

        system.setProvider("project1", "01", "fake-harness", ToolSet.HARNESS);

        ActorRef<LlmProvider> after = system.getProviderRef("project1", "01");
        assertNotNull(after);
        assertNotSame(before, after);
        assertEquals(before.getName(), after.getName(), "the new provider takes the old one's name");
        assertTrue(session.getNamesOfChildren().contains(after.getName()));
        assertEquals(1, session.getNamesOfChildren().stream().filter(n -> n.endsWith(".provider")).count());
        assertEquals("fake-harness", after.ask(LlmProvider::id).get(5, TimeUnit.SECONDS));
        // The ChatSession learns of it on its own thread; wait for that message to land.
        session.ask(a -> ((ChatSession) a).getProviderId()).get(5, TimeUnit.SECONDS);
        assertEquals("fake-harness", session.getProviderIdDirect());
        assertEquals("harness", session.getToolSetDirect());
        assertTrue(session.ask(a -> ((ChatSession) a).getAvailableModels()).get(5, TimeUnit.SECONDS)
                .stream().anyMatch(m -> "fake-model".equals(m.name())), "the model list is now the new kind's");
    }

    @Test
    void setProvider_openAiCompatWithCollaborationTools_isRefused() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        assertEquals(List.of("openai-compat"), system.getPluginRegistry().kinds(),
                "the body alone has one provider kind");
        assertThrows(IllegalArgumentException.class,
                () -> system.setProvider("project1", "01", "openai-compat", ToolSet.HARNESS));
        assertThrows(IllegalArgumentException.class,
                () -> system.setProvider("project1", "01", "no-such-kind", ToolSet.FULL));
    }
}
