package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.chatui.core.remote.RemoteNodes;
import com.scivicslab.pojoactor.action.ActionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code list_running_tools} tool: the tool instances the AI-workspace runs, asked of the AI-workspace's node with
 * {@code listInstances} on {@value #GATEWAY} ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 5). It
 * calls that one read and nothing else on the gateway. Each row of a chat-ui instance this instance knows as a node is
 * shown with the node name {@code ask_chat}'s {@code node} takes, and without an AI-workspace list the known node names
 * alone are given ({@code ListRunningToolsShowsNodeNames_261119_oo01}, {@link NodeNameListing}).
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
     * @return the instances with the node names as the Observation text; the known node names alone when there is no
     *         AI-workspace list; an error only when this instance has no path to other instances at all
     */
    public static String list(RemoteNodes remote, String tool) {
        if (remote == null) {
            return "error: this chat-ui instance has no path to other instances, so it knows no AI-workspace and no"
                    + " other chat-ui node. It learns of them from chat-ui.peers or from the AI-workspace that launched it.";
        }
        // Read the node list once before the names are matched, as knows() does, so that an instance started a
        // moment ago is not missed (ListRunningToolsShowsNodeNames_261119_oo01).
        remote.refreshPeers();
        String me = remote.myName();
        List<String> others = remote.others();
        List<RemoteNodes.ToolNode> workspaces = remote.toolNodes(KIND);
        if (workspaces.isEmpty()) {
            return NodeNameListing.namesOnly("this chat-ui instance knows no AI-workspace node", me, others);
        }
        String name = workspaces.get(0).name();
        String wanted = tool == null ? "" : tool.strip();
        String args = wanted.isEmpty() ? "{}" : JSON.createObjectNode().put("tool", wanted).toString();
        ActionResult r = remote.callTool(name, GATEWAY, "listInstances", args, TIMEOUT);
        if (!r.isSuccess()) return NodeNameListing.namesOnly("the AI-workspace " + name + " did not answer: " + r.getResult(), me, others);
        JsonNode instances;
        try {
            instances = JSON.readTree(r.getResult());
        } catch (Exception e) {
            return NodeNameListing.namesOnly("the AI-workspace " + name + " answered something that is not a list: "
                    + e.getMessage(), me, others);
        }
        List<NodeNameListing.Row> rows = new ArrayList<>();
        if (instances.isArray()) {
            for (JsonNode i : instances) {
                rows.add(new NodeNameListing.Row(i.path("name").asText(""), i.path("port").asInt(),
                        i.path("state").asText(""), i.path("url").asText(""), i.path("title").asText("")));
            }
        }
        return NodeNameListing.withRows(name, wanted, rows, me, others);
    }
}
