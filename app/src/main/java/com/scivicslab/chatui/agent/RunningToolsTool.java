package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.remote.RemoteNodes;
import com.scivicslab.pojoactor.action.ActionResult;

import java.time.Duration;
import java.util.List;

/**
 * The {@code list_running_tools} tool: the tool instances the AI-workspace runs, asked of the AI-workspace's node with
 * {@code listInstances} on {@value #GATEWAY} ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 5). It
 * calls that one read and nothing else on the gateway.
 */
public final class RunningToolsTool {

    /** The kind of the AI-workspace's node in its node list. */
    static final String KIND = "ai-workspace";
    /** The AI-workspace's gateway actor. */
    static final String GATEWAY = "ai-workspace-remote";
    /** How long the list may take (decision 9). */
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunningToolsTool() {}

    /**
     * @param remote this instance's remote path, or {@code null} when it has none
     * @param tool   only that tool's instances; blank for all
     * @return the instances as the Observation text, or an error saying why there is no list
     */
    public static String list(RemoteNodes remote, String tool) {
        List<RemoteNodes.ToolNode> workspaces = remote == null ? List.of() : remote.toolNodes(KIND);
        if (workspaces.isEmpty()) {
            return "error: this chat-ui instance knows no AI-workspace node, so it cannot list the running tools. It"
                    + " learns of one from the AI-workspace that launched it (5.0 or later).";
        }
        String name = workspaces.get(0).name();
        String wanted = tool == null ? "" : tool.strip();
        String args = wanted.isEmpty() ? "{}" : JSON.createObjectNode().put("tool", wanted).toString();
        ActionResult r = remote.callTool(name, GATEWAY, "listInstances", args, TIMEOUT);
        if (!r.isSuccess()) return "error: the AI-workspace " + name + " did not answer: " + r.getResult();
        JsonNode instances;
        try {
            instances = JSON.readTree(r.getResult());
        } catch (Exception e) {
            return "error: the AI-workspace " + name + " answered something that is not a list: " + e.getMessage();
        }
        String of = wanted.isEmpty() ? "" : " of " + wanted;
        if (!instances.isArray() || instances.isEmpty()) return "No tool instances" + of + " are running in the AI-workspace " + name + ".";
        StringBuilder sb = new StringBuilder();
        sb.append(instances.size()).append(instances.size() == 1 ? " tool instance" : " tool instances").append(of)
          .append(" in the AI-workspace ").append(name).append(", in any state:\n");
        for (JsonNode i : instances) {
            sb.append("- ").append(i.path("name").asText("")).append("  port ").append(i.path("port").asInt())
              .append("  ").append(i.path("state").asText(""));
            String url = i.path("url").asText("");
            if (!url.isBlank()) sb.append("  ").append(url);
            String title = i.path("title").asText("");
            if (!title.isBlank()) sb.append("  \"").append(title).append("\"");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
