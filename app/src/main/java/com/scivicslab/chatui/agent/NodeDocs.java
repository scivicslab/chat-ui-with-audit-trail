package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.remote.RemoteNodes;
import com.scivicslab.pojoactor.action.ActionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * An html-saurus portal reached as a node: each question is one {@code callByActionName} on its gateway
 * {@value #GATEWAY}, which answers the JSON the HTTP API answers ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}).
 */
record NodeDocs(RemoteNodes remote, String node) implements DocsSource {

    private static final Logger LOG = Logger.getLogger(NodeDocs.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The portal's gateway actor. */
    static final String GATEWAY = "html-saurus-remote";
    /** How long a search may take (decision 9). */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Override
    public List<JsonNode> fullText(String query) {
        return hits("search", JSON.createObjectNode().put("q", query.strip()), query);
    }

    @Override
    public List<JsonNode> related(String text) {
        return hits("findRelated", JSON.createObjectNode().put("text", text), text);
    }

    @Override
    public List<JsonNode> semantic(String query) {
        return hits("searchSemantic", JSON.createObjectNode().put("q", query.strip()), query);
    }

    @Override
    public List<JsonNode> references(String id, boolean forward) {
        return hits("references", JSON.createObjectNode().put("id", id).put("direction", forward ? "forward" : "backward"), id);
    }

    @Override
    public String where() {
        return node;
    }

    /** One call; its JSON array as hits, or none when the call fails (an id that does not resolve fails as "not found"). */
    private List<JsonNode> hits(String action, ObjectNode args, String subject) {
        List<JsonNode> hits = new ArrayList<>();
        LOG.info("search_docs (" + action + " on " + node + "): " + subject);
        ActionResult r = remote.callTool(node, GATEWAY, action, args.toString(), TIMEOUT);
        if (!r.isSuccess()) {
            LOG.warning("search_docs " + action + " on " + node + " failed for '" + subject + "': " + r.getResult());
            return hits;
        }
        try {
            JsonNode arr = JSON.readTree(r.getResult());
            if (arr.isArray()) arr.forEach(hits::add);
        } catch (Exception e) {
            LOG.warning("search_docs " + action + " on " + node + " answered something that is not JSON: " + e.getMessage());
        }
        return hits;
    }
}
