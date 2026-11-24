package com.scivicslab.ack.confinementhost;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.mcp.McpScopes;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The test host's one scope (tasks/todo.md, steps 5 and 6): its key, its runner, and agent-connector-kernel's core
 * tools. {@code calc} records each call in {@code calls} in the host's owner-only directory and answers 42 with the
 * run's marker, so a check can see that a tool call reached the MCP server and came back.
 */
@ApplicationScoped
public class HostScopes implements McpScopes {

    @ConfigProperty(name = "confinement-host.scope")
    String scope;

    @ConfigProperty(name = "confinement-host.dir")
    String dir;

    @ConfigProperty(name = "confinement-host.work-dir")
    String workDir;

    @ConfigProperty(name = "confinement-host.result-marker")
    String marker;

    private byte[] key;
    private String keyText;
    private ToolRunner runner;

    @PostConstruct
    void init() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        keyText = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        key = keyText.getBytes(StandardCharsets.UTF_8);
        Path calls = Path.of(dir, "calls");
        runner = new ToolRunner(new ToolScope(scope, new FileAccessScope(Path.of(workDir), List.of()), ToolSet.FULL, Map.of()),
                expression -> {
                    try {
                        Files.writeString(calls, expression + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return "42 (" + marker + ")";
                }, DocsLookup.HTTP, ToolExtension.NONE, ToolCallRecorder.NONE);
    }

    /** @return the scope's MCP key */
    public String key() {
        return keyText;
    }

    /** @return the scope's name */
    public String scope() {
        return scope;
    }

    @Override
    public List<ToolCatalog.Spec> tools() {
        return new ArrayList<>(ToolCatalog.coreTools().values());
    }

    @Override
    public ToolRunner runnerOf(String name) {
        return scope.equals(name) ? runner : null;
    }

    @Override
    public boolean opens(String name, String candidate) {
        return scope.equals(name) && candidate != null
                && MessageDigest.isEqual(key, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
