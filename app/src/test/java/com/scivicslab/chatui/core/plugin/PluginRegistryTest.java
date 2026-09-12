package com.scivicslab.chatui.core.plugin;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code ProviderAndToolPlugins_260912_oo01}'s registry: what a plugin
 * registers is what the dropdown and the conversation get, and a jar that cannot be loaded stops
 * start-up instead of yielding an instance silently without it.
 */
class PluginRegistryTest {

    private static final class FakePlugin implements ChatUiPlugin {
        @Override public String id() { return "fake"; }
        @Override public List<LlmProviderFactory> providerFactories() {
            return List.of(new LlmProviderFactory() {
                @Override public String kind() { return "fake-kind"; }
                @Override public List<ProviderChoice> choices() {
                    return List.of(new ProviderChoice("fake-kind", ToolSet.COLLABORATION, "Fake"),
                                   new ProviderChoice("fake-kind", ToolSet.FULL, "Fake (bare)"));
                }
                @Override public LlmProvider create(ProviderCreationContext ctx) { throw new UnsupportedOperationException(); }
            });
        }
        @Override public List<ConversationTool> tools() {
            return List.of(new ConversationTool() {
                @Override public String name() { return "fake_tool"; }
                @Override public String description() { return "- fake_tool(): nothing\n"; }
                @Override public String execute(String argumentsJson) { return "ok"; }
            });
        }
    }

    @Test
    void register_plugin_exposesItsKindsChoicesAndTools() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new OpenAiCompatProviderFactory(List.of("http://localhost:1"), "m"));
        registry.register(new FakePlugin(), "test");

        assertEquals(List.of("openai-compat", "fake-kind"), registry.kinds());
        assertEquals(List.of("fake_tool"), registry.tools().stream().map(ConversationTool::name).toList());
        assertTrue(registry.factory("fake-kind").isPresent());
        assertEquals(ToolSet.COLLABORATION, registry.factory("fake-kind").get().defaultToolSet());

        Map<String, Object> d = registry.describe();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) d.get("providerChoices");
        assertEquals(List.of("openai-compat", "fake-kind", "fake-kind:full"),
                choices.stream().map(c -> c.get("value")).toList(),
                "the value is the kind alone for its default tool set, kind:toolSet otherwise");
        assertEquals(List.of("Local LLM", "Fake", "Fake (bare)"), choices.stream().map(c -> c.get("label")).toList());
        assertEquals(1, ((List<?>) d.get("plugins")).size());
    }

    @Test
    void loadJars_missingJar_stopsStartup() {
        PluginRegistry registry = new PluginRegistry();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.loadJars(List.of(Path.of("/no/such/plugin.jar"))));
        assertTrue(e.getMessage().contains("/no/such/plugin.jar"), e.getMessage());
    }

    @Test
    void loadJars_nothing_leavesOnlyWhatWasRegistered() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new OpenAiCompatProviderFactory(List.of("http://localhost:1"), "m"));
        registry.loadJars(List.of());
        assertEquals(List.of("openai-compat"), registry.kinds());
        assertTrue(registry.tools().isEmpty());
        assertTrue(registry.plugins().isEmpty());
    }
}
