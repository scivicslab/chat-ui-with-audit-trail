package com.scivicslab.sshconnector;

import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.mcp.McpScopes;
import com.scivicslab.chatui.tool.ToolRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** agent-connector-kernel's view of ssh-connector's one scope (tasks/todo.md, Decision 12). */
@ApplicationScoped
public class ConnectorMcpScopes implements McpScopes {

    @Inject
    ConnectorScope scope;

    @Override
    public List<ToolCatalog.Spec> tools() {
        return scope.tools();
    }

    @Override
    public ToolRunner runnerOf(String name) {
        return scope.name().equals(name) ? scope.runner() : null;
    }

    @Override
    public boolean opens(String name, String key) {
        return scope.opens(name, key);
    }
}
