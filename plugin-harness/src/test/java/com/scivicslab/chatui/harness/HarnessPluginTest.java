package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderCreationContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code HarnessPrefaceAndToolSplit_260912_oo01}: the preface a harness puts
 * before the first prompt is the provider's, present with its own tools and absent when they are
 * switched off, and the kinds' choices carry the harness tool set. No CLI process is started.
 */
class HarnessPluginTest {

    private static ProviderCreationContext ctx(Path dir, ToolSet toolSet) {
        return new ProviderCreationContext("project1", "01", dir, toolSet, 28039,
                key -> key.equals("chat-ui.harness.session-dir") ? Optional.of(dir.toString()) : Optional.empty());
    }

    private static LlmProviderFactory factory(String kind) {
        return new HarnessPlugin().providerFactories().stream().filter(f -> f.kind().equals(kind)).findFirst().orElseThrow();
    }

    @Test
    void claude_withOwnTools_prefacesHowToLoadDeferredWebTools(@TempDir Path dir) {
        LlmProvider p = factory("claude").create(ctx(dir, ToolSet.HARNESS));
        assertTrue(p.promptPreface().startsWith("You are running inside Claude Code"), p.promptPreface());
        assertTrue(p.promptPreface().contains("ToolSearch"), "says how to load the deferred web tools");
        assertTrue(p.promptPreface().endsWith("\n\n"), "ends with a blank line so the format text follows cleanly");
    }

    @Test
    void claude_withToolsOff_hasNoPreface(@TempDir Path dir) {
        LlmProvider p = factory("claude").create(ctx(dir, ToolSet.FULL));
        assertEquals("", p.promptPreface());
    }

    @Test
    void codex_prefacesItsOwnShellAndTheConversationsWebTools(@TempDir Path dir) {
        LlmProvider p = factory("codex").create(ctx(dir, ToolSet.HARNESS));
        assertTrue(p.promptPreface().startsWith("You are running inside Codex"), p.promptPreface());
        assertTrue(p.promptPreface().contains("web_search"));
        assertThrows(IllegalArgumentException.class, () -> factory("codex").create(ctx(dir, ToolSet.FULL)));
    }

    @Test
    void choices_carryTheHarnessToolSetFirst() {
        assertEquals(ToolSet.HARNESS, factory("claude").defaultToolSet());
        assertEquals(ToolSet.HARNESS, factory("codex").defaultToolSet());
        assertEquals("Claude Code (harness tools off)", factory("claude").choices().get(1).label());
    }
}
