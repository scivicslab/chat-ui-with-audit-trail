package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.audittrail.HeadBlock.Part;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.Project;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.openaicompat.McpToolClient;
import com.scivicslab.chatui.openaicompat.ToolDefinition;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Tokens tab's parts table: what the block a conversation's first request starts with is made of, and who
 * may cut each part. Read-only; it counts characters and estimates tokens ({@link HeadBlock}).
 *
 * <p>For a Claude Code conversation the parts are the CLAUDE.md files that apply to the working directory,
 * the project's MEMORY.md index, the skill list, each MCP server the CLI is configured with, and the CLI's
 * own prompt and tools, which cannot be counted here. For any other provider they are the tools of chat-ui's
 * own MCP server and of the conversation's external MCP servers.</p>
 */
@Path("/api/chats")
public class HeadBlockResource {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Inject
    ChatUiActorSystem actorSystem;

    @Inject
    IoLogStore ioLog;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "28030")
    int httpPort;

    /** Where a conversation's CLI works when its project has no working directory: the write root of the file tools. */
    @ConfigProperty(name = "chat-ui.write-root")
    java.util.Optional<String> writeRoot;

    /**
     * @param id a qualified conversation name such as {@code project1/chat-02}; the slash may be sent as {@code %2F}
     * @return {@code {parts:[{name, chars, estimatedTokens, measured, whoCanCut}], estimatedTotal,
     *         measuredFirstRequestTotal}}, or 404 for no such conversation
     */
    @GET
    @Path("/{id: .+}/head-block")
    @Produces(MediaType.APPLICATION_JSON)
    public Response headBlock(@PathParam("id") String id) {
        int at = id.indexOf("/chat-");
        if (at < 0) return notFound(id);
        String projectId = id.substring(0, at);
        String chatId = id.substring(at + "/chat-".length());
        ChatSessionIIAR session = actorSystem.getChatSession(projectId, chatId);
        if (session == null) return notFound(id);

        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        java.nio.file.Path workingDir = workingDirOf(projectId);
        List<Part> parts = new ArrayList<>();
        if ("claude".equals(session.getProviderIdDirect())) {
            claudeParts(parts, workingDir, home, id);
        } else {
            parts.addAll(mcpParts("chat-ui's own MCP server", "http://127.0.0.1:" + httpPort + "/mcp", id,
                    HeadBlock.CONVERSATION_SETTING));
            Map<String, String> external = actorSystem.mcpServersOf(id);
            if (external != null) {
                external.forEach((name, url) -> parts.addAll(mcpParts("MCP server " + name, url, id,
                        HeadBlock.CONVERSATION_SETTING)));
            }
        }
        return Response.ok(HeadBlock.body(parts, measuredFirstRequestTotal(id))).build();
    }

    private static Response notFound(String id) {
        return Response.status(404).entity(Map.of("type", "error", "message", "no such conversation: " + id)).build();
    }

    private java.nio.file.Path workingDirOf(String projectId) {
        var project = actorSystem.getProject(projectId);
        if (project != null) {
            try {
                java.nio.file.Path dir = project.ask(Project::getWorkingDir).get(5, TimeUnit.SECONDS);
                if (dir != null) return dir;
            } catch (Exception e) {
                // Falls back to the write root, as the provider does.
            }
        }
        return writeRoot.map(java.nio.file.Path::of).orElse(null);
    }

    private void claudeParts(List<Part> parts, java.nio.file.Path workingDir, java.nio.file.Path home, String conversation) {
        List<java.nio.file.Path> claudeMd = HeadBlock.claudeMdFiles(workingDir, home);
        parts.add(HeadBlock.measuredPart("CLAUDE.md files (" + claudeMd.size() + "; @-imports are not followed)",
                HeadBlock.readAll(claudeMd), HeadBlock.PERSON_EDITED_FILE));
        if (workingDir != null) {
            java.nio.file.Path memory = HeadBlock.memoryIndex(workingDir, home);
            parts.add(HeadBlock.measuredPart("MEMORY.md index" + (Files.isRegularFile(memory) ? "" : " (none found)"),
                    Files.isRegularFile(memory) ? HeadBlock.readAll(List.of(memory)) : "",
                    HeadBlock.PERSON_EDITED_FILE));
        }
        parts.add(skillListPart());
        mcpConfigOfTheCli(workingDir, home).forEach((name, server) -> {
            String url = server.optString("url", "");
            if (url.isEmpty()) {
                parts.add(HeadBlock.unmeasuredPart("MCP server " + name + " (started by the CLI, tools not listed here)",
                        HeadBlock.PERSON_EDITED_FILE));
            } else {
                parts.addAll(mcpParts("MCP server " + name, url, conversation, HeadBlock.PERSON_EDITED_FILE));
            }
        });
        parts.add(HeadBlock.unmeasuredPart("CLI built-in prompt and tools (not measurable here)",
                HeadBlock.FIXED_BY_THE_CLI));
    }

    private Part skillListPart() {
        var registry = actorSystem.getSkillRegistry();
        if (registry == null) return HeadBlock.unmeasuredPart("Skill list (skill registry unavailable)",
                HeadBlock.PERSON_EDITED_FILE);
        try {
            List<String> lines = registry.ask(r -> r.getSkills().stream()
                    .map(s -> s.name() + ": " + s.description()).toList()).get(10, TimeUnit.SECONDS);
            return HeadBlock.measuredPart("Skill list (" + lines.size() + " skills, name and description)",
                    String.join("\n", lines), HeadBlock.PERSON_EDITED_FILE);
        } catch (Exception e) {
            return HeadBlock.unmeasuredPart("Skill list (the registry did not answer)", HeadBlock.PERSON_EDITED_FILE);
        }
    }

    /**
     * One part for an MCP server's tools, counted from the JSON its {@code tools/list} answers; a server that
     * does not answer is a part with no count, whose name says so.
     */
    private List<Part> mcpParts(String label, String url, String conversation, String whoCanCut) {
        // The conversation is named only to a server of this machine: chat-ui's own server lists a conversation's tools.
        boolean local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost");
        try {
            List<String[]> tools = new ArrayList<>();
            for (ToolDefinition t : new McpToolClient(url, local ? conversation : null).listTools()) {
                tools.add(new String[] {t.name(), t.description(), t.parametersJson()});
            }
            return List.of(HeadBlock.measuredPart(label + " (" + tools.size() + " tools)", HeadBlock.toolsJson(tools),
                    whoCanCut));
        } catch (RuntimeException e) {
            return List.of(HeadBlock.unmeasuredPart(label + " (tools/list failed: " + e.getMessage() + ")", whoCanCut));
        }
    }

    /** The MCP servers the CLI is configured with: {@code .mcp.json} of the working directory, then the user's {@code ~/.claude.json}. */
    private static Map<String, JSONObject> mcpConfigOfTheCli(java.nio.file.Path workingDir, java.nio.file.Path home) {
        Map<String, JSONObject> servers = new LinkedHashMap<>();
        List<java.nio.file.Path> files = new ArrayList<>();
        if (workingDir != null) files.add(workingDir.resolve(".mcp.json"));
        files.add(home.resolve(".claude.json"));
        for (java.nio.file.Path f : files) {
            if (!Files.isRegularFile(f)) continue;
            try {
                JSONObject root = new JSONObject(Files.readString(f));
                addServers(servers, root.optJSONObject("mcpServers"));
                JSONObject projects = root.optJSONObject("projects");
                if (projects != null && workingDir != null) {
                    JSONObject mine = projects.optJSONObject(workingDir.toAbsolutePath().normalize().toString());
                    if (mine != null) addServers(servers, mine.optJSONObject("mcpServers"));
                }
            } catch (IOException | JSONException e) {
                // A configuration the CLI could not read lists no servers.
            }
        }
        return servers;
    }

    private static void addServers(Map<String, JSONObject> into, JSONObject servers) {
        if (servers == null) return;
        for (String name : servers.keySet()) {
            JSONObject s = servers.optJSONObject(name);
            if (s != null) into.putIfAbsent(name, s);
        }
    }

    /** The first request's input tokens, read from {@code GET /api/sessions/{id}/tokens} of this instance; null when it has none. */
    private Integer measuredFirstRequestTotal(String conversation) {
        long sessionId = ioLog.sessionIdOf(conversation);
        if (sessionId < 0) return null;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + httpPort + "/api/sessions/" + sessionId + "/tokens"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? HeadBlock.firstRequestTotal(r.body()) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
