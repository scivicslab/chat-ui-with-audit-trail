package com.scivicslab.chatui.tool;

import com.scivicslab.chatui.agent.DocsSource;
import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.plugin.ConversationTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRunner} runs the plain tools of agent-connector-kernel and the plugin tools itself, hands any other
 * name to its {@link ToolExtension}, and records each MCP call once (tasks/todo.md, stage 5).
 */
class ToolRunnerTest {

    /** An extension with one offered tool ({@code ext_tool}) and one it defines but does not offer ({@code ext_hidden}). */
    private static final ToolExtension EXTENSION = new ToolExtension() {
        @Override public boolean defines(String tool) { return tool.equals("ext_tool") || tool.equals("ext_hidden"); }
        @Override public boolean offers(String tool) { return tool.equals("ext_tool"); }
        @Override public String run(String tool, String argumentsJson) {
            return tool.equals("ext_tool") ? "ext ran with " + ToolRunner.input(argumentsJson, "x") : null;
        }
    };

    private static ConversationTool plugin(String name, String answer) {
        return new ConversationTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "- " + name + "(): a plugin tool\n"; }
            @Override public String execute(String argumentsJson) { return answer; }
        };
    }

    private static ToolRunner runner(Path root, ToolSet toolSet, List<String> recorded) {
        ToolScope scope = new ToolScope("project1/chat-01", new FileAccessScope(root, List.of()), toolSet,
                Map.of("plug", plugin("plug", "plugin ran"), "ext_hidden", plugin("ext_hidden", "plugin shadowed")));
        return new ToolRunner(scope, expression -> "evaluated " + expression,
                node -> new DocsSource.Choice(null, "error: no node " + node), EXTENSION,
                (tool, args, observation) -> recorded.add(tool + " -> " + observation));
    }

    @Test
    void writeThenReadRunsTheFileToolsInTheScope(@TempDir Path root) throws Exception {
        ToolRunner r = runner(root, ToolSet.FULL, new ArrayList<>());
        assertTrue(r.run("write", "{\"path\":\"a.txt\",\"content\":\"hello\"}", false).startsWith("wrote "));
        assertEquals("hello", Files.readString(root.resolve("a.txt")));
        assertTrue(r.run("read", "{\"path\":\"a.txt\"}", false).contains("hello"));
    }

    @Test
    void calcRefusesJavaAndEvaluatesArithmetic(@TempDir Path root) {
        ToolRunner r = runner(root, ToolSet.FULL, new ArrayList<>());
        assertEquals("evaluated 23*47", r.run("calc", "{\"expression\":\"23*47\"}", false));
        assertTrue(r.run("calc", "{\"expression\":\"System.exit(0)\"}", false).startsWith("error"));
    }

    @Test
    void searchDocsReturnsTheLookupsError(@TempDir Path root) {
        ToolRunner r = runner(root, ToolSet.FULL, new ArrayList<>());
        assertEquals("error: no node html-saurus-x", r.run("search_docs", "{\"query\":\"q\",\"node\":\"html-saurus-x\"}", false));
    }

    @Test
    void extensionToolsRunBeforePluginToolsAndUnknownNamesAreErrors(@TempDir Path root) {
        ToolRunner r = runner(root, ToolSet.FULL, new ArrayList<>());
        assertEquals("ext ran with 1", r.run("ext_tool", "{\"x\":\"1\"}", false));
        assertEquals("plugin ran", r.run("plug", "{}", false));
        assertEquals("error: unknown tool 'nothing'", r.run("nothing", "{}", false));
    }

    @Test
    void offersFollowsTheToolSetTheExtensionAndThePlugins(@TempDir Path root) {
        ToolRunner full = runner(root, ToolSet.FULL, new ArrayList<>());
        assertTrue(full.offers("read"));
        assertTrue(full.offers("ext_tool"));
        assertTrue(full.offers("plug"));
        assertFalse(full.offers("ext_hidden"), "a name the extension defines is not taken for the plugin of that name");
        assertFalse(full.offers("nothing"));
        ToolRunner harness = runner(root, ToolSet.HARNESS, new ArrayList<>());
        assertFalse(harness.offers("read"));
        assertFalse(harness.offers("calc"));
        assertTrue(harness.offers("search_docs"));
    }

    @Test
    void callFromMcpRecordsEachCallOnceAndRefusesWhatIsNotOffered(@TempDir Path root) {
        List<String> recorded = new ArrayList<>();
        ToolRunner r = runner(root, ToolSet.HARNESS, recorded);
        assertEquals("plugin ran", r.callFromMcp("plug", "{}"));
        assertEquals("error: tool 'read' is not available in this conversation", r.callFromMcp("read", "{\"path\":\"a\"}"));
        assertEquals(List.of("plug -> plugin ran", "read -> error: tool 'read' is not available in this conversation"), recorded);
    }
}
