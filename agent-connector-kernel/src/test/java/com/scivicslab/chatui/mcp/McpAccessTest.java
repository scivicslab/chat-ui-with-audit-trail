package com.scivicslab.chatui.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which requests agent-connector-kernel's MCP server lets through, for both values of
 * {@code agent-connector-kernel.mcp.require-key} (tasks/todo.md, Decisions 12 and 13). Dummy keys only.
 */
class McpAccessTest {

    private static final String KEY = "dummy-key-s1";

    /** Only scope s1 exists, and only {@link #KEY} opens it. */
    private static McpAccess access(boolean requireKey) {
        return new McpAccess(requireKey, (scope, key) -> scope.equals("s1") && key.equals(KEY));
    }

    @Test
    void withAKeyRequiredTheRightKeyGoesOnAsItsScope() {
        assertEquals(new McpAccess.Allow("s1"), access(true).decide("s1", "Bearer " + KEY));
        assertEquals(new McpAccess.Allow("s1"), access(true).decide(" s1 ", "Bearer " + KEY));
    }

    @Test
    void withAKeyRequiredAnUnknownScopeIsRefusedLikeAWrongKey() {
        McpAccess.Decision unknown = access(true).decide("s2", "Bearer " + KEY);
        McpAccess.Decision wrongKey = access(true).decide("s1", "Bearer " + KEY + "x");
        assertEquals(new McpAccess.Refuse(401), unknown);
        assertEquals(wrongKey, unknown);
        assertEquals(unknown, access(true).decide("s1", null), "no key");
        assertEquals(unknown, access(true).decide("s1", KEY), "the key counts only as a Bearer token");
        assertEquals(unknown, access(true).decide("s1", "Bearer "), "an empty key");
    }

    @Test
    void withAKeyRequiredARequestThatNamesNoScopeIs400() {
        assertEquals(new McpAccess.Refuse(400), access(true).decide(null, "Bearer " + KEY));
        assertEquals(new McpAccess.Refuse(400), access(true).decide("  ", "Bearer " + KEY));
    }

    /** chat-ui's behaviour before the split is kept exactly: no key, and the request goes on as the scope it names. */
    @Test
    void withNoKeyRequiredARequestGoesOnAsTheScopeItNamesWithoutAKey() {
        assertEquals(new McpAccess.Allow("project1/chat-01"), access(false).decide("project1/chat-01", null));
        assertEquals(new McpAccess.Allow("s1"), access(false).decide("s1", "Bearer wrong"), "a key is not looked at");
        assertEquals(new McpAccess.Allow(null), access(false).decide(null, null), "no scope: no tools, but no refusal");
    }
}
