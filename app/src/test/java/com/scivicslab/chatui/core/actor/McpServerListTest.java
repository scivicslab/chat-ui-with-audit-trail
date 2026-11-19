package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A conversation's external MCP servers: the rules and the settings record (ExternalMcpServersOfAConversation_261120_oo01, 1 and 3). */
@Tag("ExternalMcpServersOfAConversation_261120_oo01")
@DisplayName("McpServerList checks names and URLs, and the settings record keeps the list")
class McpServerListTest {

    @Test
    void aGoodListIsKeptByName() {
        Map<String, String> s = McpServerList.parse("{\"zeta\":\"http://z/mcp\",\"htmlsaurus\":\"http://localhost:28001/mcp\"}");
        assertEquals(List.of("htmlsaurus", "zeta"), List.copyOf(s.keySet()));
        assertEquals("http://localhost:28001/mcp", s.get("htmlsaurus"));
    }

    @Test
    void emptyAndBlankAreNoServers() {
        assertTrue(McpServerList.parse("{}").isEmpty());
        assertTrue(McpServerList.parse("").isEmpty());
    }

    @Test
    void badNamesAndUrlsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("{\"Html\":\"http://x/mcp\"}"));
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("{\"a_b\":\"http://x/mcp\"}"));
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("{\"a-very-long-server-name-x\":\"http://x/mcp\"}"));
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("{\"ok\":\"ftp://x/mcp\"}"));
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("{\"ok\":42}"));
        assertThrows(IllegalArgumentException.class, () -> McpServerList.parse("[1]"));
    }

    @Test
    void theSettingsRecordKeepsTheServers() {
        IoLogView.Settings s = IoLogView.parseSettings("{\"provider\":\"openai-compat\",\"tools\":\"full\",\"model\":\"m\","
                + "\"mcpServers\":{\"htmlsaurus\":\"http://localhost:28001/mcp\"}}");
        assertEquals(Map.of("htmlsaurus", "http://localhost:28001/mcp"), s.mcpServers());
        assertNull(IoLogView.parseSettings("{\"provider\":\"openai-compat\"}").mcpServers(),
                "a record written before the servers were kept names none");
    }
}
