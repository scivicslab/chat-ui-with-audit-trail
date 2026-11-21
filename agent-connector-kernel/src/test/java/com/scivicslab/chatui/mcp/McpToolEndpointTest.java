package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scope sees only its own tools, through the library MCP server's {@code ToolFilter} as through any other caller
 * (tasks/todo.md, Decision 12). Brought over from the deleted {@code McpRequestHandlerTest}.
 */
class McpToolEndpointTest {

    @Test
    void aScopeIsOfferedOnlyItsOwnToolsAndAnUnknownScopeNone(@TempDir Path root) {
        ToolRunner full = runner("s1", root, ToolSet.FULL);
        ToolRunner harness = runner("s2", root, ToolSet.HARNESS);
        McpToolEndpoint endpoint = new McpToolEndpoint(name -> switch (name) {
            case "s1" -> full;
            case "s2" -> harness;
            default -> null;
        });
        assertTrue(endpoint.offers("s1", "calc"));
        assertFalse(endpoint.offers("s2", "calc"), "HARNESS leaves out read, write, remove and calc");
        assertTrue(endpoint.offers("s2", "search_docs"));
        assertFalse(endpoint.offers("s3", "search_docs"), "an unknown scope");
        assertFalse(endpoint.offers(null, "search_docs"), "no scope");
        List<String> s1 = endpoint.list("s1").stream().map(ToolCatalog.Spec::name).toList();
        List<String> s2 = endpoint.list("s2").stream().map(ToolCatalog.Spec::name).toList();
        assertTrue(s1.containsAll(List.of("read", "write", "remove", "calc", "search_docs")), s1.toString());
        assertTrue(s2.contains("search_docs"), s2.toString());
        assertFalse(s2.contains("calc") || s2.contains("read"), s2.toString());
        assertTrue(endpoint.list("s3").isEmpty());
        assertEquals(new McpToolEndpoint.Result("1081", false), endpoint.call("s1", "calc", "{\"expression\":\"23*47\"}"));
        assertTrue(endpoint.call("s2", "calc", "{\"expression\":\"23*47\"}").isError(), "a tool outside the scope's share");
        assertTrue(endpoint.call("s3", "calc", "{\"expression\":\"23*47\"}").isError(), "an unknown scope");
    }

    private static ToolRunner runner(String name, Path root, ToolSet set) {
        return new ToolRunner(new ToolScope(name, new FileAccessScope(root, List.of()), set, Map.of()),
                expression -> "1081", DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE);
    }
}
