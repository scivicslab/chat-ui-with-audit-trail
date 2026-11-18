package com.scivicslab.chatui.core.remote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.NodeDiscovery;

/**
 * The other chat-ui instances, as the AI-workspace that runs this one lists them at
 * {@code <workspace-url>/api/chat-ui/peers} ({@code ConversationSquadsAcrossInstances_261115_oo01}): a JSON array of
 * {@code {"name", "host", "actorPort"}}, this instance included. This instance's own entry is left out. The list is
 * read again by {@link #refresh}; when the workspace cannot be reached, the last list stays and a warning is logged.
 *
 * <p>The same refresh reads the workspace's other list, {@code <workspace-url>/api/nodes}: every node of the
 * distributed actor system it knows, each with its kind ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}). The
 * nodes of a kind other than {@value #CHAT_UI_KIND} are the tool nodes ({@link #toolNodes}): the AI-workspace itself
 * and the html-saurus portals. chat-ui instances still come from the first list only. A workspace without
 * {@code /api/nodes} (before 5.0) has no tool nodes.</p>
 */
public final class WorkspaceNodeDiscovery implements NodeDiscovery {

    private static final Logger LOG = Logger.getLogger(WorkspaceNodeDiscovery.class.getName());

    /** The path the workspace answers the list at. */
    public static final String PEERS_PATH = "/api/chat-ui/peers";

    /** The path the workspace answers its node list at. */
    public static final String NODES_PATH = "/api/nodes";

    /** The kind of a chat-ui node in that list: those are peers, not tool nodes. */
    public static final String CHAT_UI_KIND = "chat-ui";

    private final NodeInfo me;
    private final URI peersUri;
    private final URI nodesUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile List<NodeInfo> peers = List.of();
    private volatile List<RemoteNodes.ToolNode> toolNodes = List.of();

    /**
     * @param me           this instance's node
     * @param workspaceUrl the workspace's base URL, e.g. {@code http://localhost:28000}
     */
    public WorkspaceNodeDiscovery(NodeInfo me, String workspaceUrl) {
        this.me = me;
        this.peersUri = URI.create(workspaceUrl.replaceAll("/+$", "") + PEERS_PATH);
        this.nodesUri = URI.create(workspaceUrl.replaceAll("/+$", "") + NODES_PATH);
    }

    /**
     * Reads the list from the workspace. Keeps the last list when the workspace does not answer, or answers
     * something that is not the list.
     *
     * @return whether the list was read
     */
    public synchronized boolean refresh() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(peersUri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                LOG.warning("The workspace answered " + r.statusCode() + " at " + peersUri + "; the last peer list stays");
                return false;
            }
            peers = parse(r.body(), me.getNodeId());
            readToolNodes();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warning("The workspace could not be read at " + peersUri + " (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + "); the last peer list stays");
            return false;
        }
    }

    /** Reads {@code /api/nodes}; keeps the last tool nodes when it cannot, and has none when the workspace has no such list. */
    private void readToolNodes() throws InterruptedException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(nodesUri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                toolNodes = List.of();
            } else if (r.statusCode() != 200) {
                LOG.warning("The workspace answered " + r.statusCode() + " at " + nodesUri + "; the last tool nodes stay");
            } else {
                toolNodes = parseToolNodes(r.body(), me.getNodeId());
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            LOG.warning("The workspace's node list could not be read at " + nodesUri + " (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "); the last tool nodes stay");
        }
    }

    /** @return the entries of {@code /api/nodes} whose kind is not {@value #CHAT_UI_KIND}, without {@code myName} */
    static List<RemoteNodes.ToolNode> parseToolNodes(String body, String myName) {
        JSONArray array = new JSONArray(body);
        List<RemoteNodes.ToolNode> nodes = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String name = o.getString("name");
            String kind = o.optString("kind", "");
            if (name.equals(myName) || kind.isBlank() || kind.equals(CHAT_UI_KIND)) continue;
            nodes.add(new RemoteNodes.ToolNode(name, o.optString("host", "127.0.0.1"), o.getInt("actorPort"), kind));
        }
        return List.copyOf(nodes);
    }

    /** @return the tool nodes in the last node list read: the AI-workspace and the html-saurus portals */
    public List<RemoteNodes.ToolNode> toolNodes() {
        return toolNodes;
    }

    /** @return the nodes in the workspace's answer, without {@code myName} */
    static List<NodeInfo> parse(String body, String myName) {
        JSONArray array = new JSONArray(body);
        List<NodeInfo> nodes = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String name = o.getString("name");
            if (name.equals(myName)) continue;
            nodes.add(new NodeInfo(name, o.optString("host", "127.0.0.1"), o.getInt("actorPort")));
        }
        return List.copyOf(nodes);
    }

    /** @return the other instances in the last list read */
    public List<NodeInfo> peers() {
        return peers;
    }

    @Override public String getMyNodeId() { return me.getNodeId(); }
    @Override public String getMyHost() { return me.getHost(); }
    @Override public int getMyPort() { return me.getPort(); }

    @Override
    public List<NodeInfo> getAllNodes() {
        List<NodeInfo> all = new ArrayList<>();
        all.add(me);
        all.addAll(peers);
        return all;
    }

    @Override
    public boolean isApplicable() {
        return true;
    }
}
