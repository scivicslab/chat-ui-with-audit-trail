package com.scivicslab.chatui.mcp;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.tool.ToolRunner;

import java.util.List;

/**
 * What an application tells agent-connector-kernel's MCP server (tasks/todo.md, Decision 12): which tools it registers,
 * which runner a scope name stands for, and whether a key opens a scope. The application provides exactly one CDI bean
 * of this type: chat-ui's conversations, or ssh-connector's harness scopes.
 */
public interface McpScopes {

    /** @return every tool the server registers at start-up; each request then sees only its scope's share */
    List<ToolCatalog.Spec> tools();

    /** @return the runner of scope {@code name}, or {@code null} when there is no such scope */
    ToolRunner runnerOf(String name);

    /**
     * @param name the scope a request names
     * @param key  the key it carries
     * @return whether {@code key} is scope {@code name}'s key, compared in constant time; {@code false} for a scope that
     *         does not exist
     */
    boolean opens(String name, String key);
}
