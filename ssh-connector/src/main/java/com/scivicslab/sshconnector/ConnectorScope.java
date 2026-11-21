package com.scivicslab.sshconnector;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * ssh-connector's one MCP scope (tasks/todo.md, "Stage 11 spec"): the harness's. Its key is made at start-up, its
 * tools are the ssh tools only, and its runner runs them with the working directory as the file range. The harness has
 * file and shell tools of its own, so the scope's tool set is {@link ToolSet#HARNESS}.
 */
public final class ConnectorScope {

    private final List<ToolCatalog.Spec> specs;
    private final ToolRunner runner;
    private final String key;
    private final byte[] keyBytes;

    /**
     * @param tools      the ssh tools
     * @param specs      their definitions
     * @param workingDir the range the file tools are bound to
     */
    public ConnectorScope(ToolExtension tools, List<ToolCatalog.Spec> specs, FileAccessScope workingDir) {
        this.specs = List.copyOf(specs);
        this.runner = new ToolRunner(new ToolScope(ConnectorSettings.SCOPE, workingDir, ToolSet.HARNESS, Map.of()),
                expression -> "error: calc is not offered here", DocsLookup.HTTP, tools, ToolCallRecorder.NONE);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        this.keyBytes = key.getBytes(StandardCharsets.UTF_8);
    }

    /** @return the scope's name, {@value ConnectorSettings#SCOPE} */
    public String name() {
        return ConnectorSettings.SCOPE;
    }

    /** @return the scope's key: 32 random bytes, base64url */
    public String key() {
        return key;
    }

    /** @return the tools the MCP server registers */
    public List<ToolCatalog.Spec> tools() {
        return specs;
    }

    /** @return the scope's runner */
    public ToolRunner runner() {
        return runner;
    }

    /** @return whether {@code key} opens scope {@code name}, compared in constant time */
    public boolean opens(String name, String candidate) {
        return ConnectorSettings.SCOPE.equals(name) && candidate != null
                && MessageDigest.isEqual(keyBytes, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
