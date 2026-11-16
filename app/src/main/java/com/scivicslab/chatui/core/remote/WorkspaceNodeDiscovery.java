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
 */
public final class WorkspaceNodeDiscovery implements NodeDiscovery {

    private static final Logger LOG = Logger.getLogger(WorkspaceNodeDiscovery.class.getName());

    /** The path the workspace answers the list at. */
    public static final String PEERS_PATH = "/api/chat-ui/peers";

    private final NodeInfo me;
    private final URI peersUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile List<NodeInfo> peers = List.of();

    /**
     * @param me           this instance's node
     * @param workspaceUrl the workspace's base URL, e.g. {@code http://localhost:28000}
     */
    public WorkspaceNodeDiscovery(NodeInfo me, String workspaceUrl) {
        this.me = me;
        this.peersUri = URI.create(workspaceUrl.replaceAll("/+$", "") + PEERS_PATH);
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
