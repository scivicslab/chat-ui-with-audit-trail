package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.scivicslab.chatui.core.remote.RemoteNodes;

import java.util.List;

/**
 * Where {@code search_docs} and {@code list_references} get their answers: an html-saurus portal reached as a node of
 * the distributed actor system, or, when this instance knows none, html-saurus's HTTP API
 * ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 7). Both answer the same JSON hits, so the tools'
 * answers do not depend on which one was used.
 */
public interface DocsSource {

    /** The kind of an html-saurus node in the AI-workspace's node list. */
    String KIND = "html-saurus";

    /** A source, or why none could be chosen; exactly one of the two is set. */
    record Choice(DocsSource source, String error) {}

    /** Full-text hits for {@code query}, in rank order; empty when there are none or the source cannot be reached. */
    List<JsonNode> fullText(String query);

    /** Documents like {@code text} (term frequencies), in rank order; empty likewise. */
    List<JsonNode> related(String text);

    /** Semantic (embedding) hits for {@code query}, in rank order; empty likewise. */
    List<JsonNode> semantic(String query);

    /** The documents {@code id} refers to ({@code forward}), or that refer to it; empty when there are none or the id does not resolve. */
    List<JsonNode> references(String id, boolean forward);

    /** @return the node name or base URL answers come from, for messages */
    String where();

    /** @return html-saurus's HTTP API at {@code baseUrl} */
    static DocsSource http(String baseUrl) {
        return new HttpDocs(baseUrl);
    }

    /**
     * Chooses where to search. With {@code node} named, that html-saurus node; an unknown name is an error that names
     * the known ones. Without, the html-saurus node with the lowest port; and when this instance knows none (no
     * remote path, or an AI-workspace that lists none), the HTTP API at {@code httpBase}.
     *
     * @param remote   this instance's remote path, or {@code null} when it has none
     * @param node     the node the conversation named, or blank
     * @param httpBase the HTTP API's base URL, for the fallback
     */
    static Choice choose(RemoteNodes remote, String node, String httpBase) {
        String wanted = node == null ? "" : node.strip();
        List<RemoteNodes.ToolNode> portals = remote == null ? List.of() : remote.toolNodes(KIND);
        if (!wanted.isEmpty()) {
            if (remote != null && portals.stream().noneMatch(n -> n.name().equals(wanted))) {
                remote.refreshPeers();
                portals = remote.toolNodes(KIND);
            }
            if (portals.stream().noneMatch(n -> n.name().equals(wanted))) {
                return new Choice(null, "error: no html-saurus node named \"" + wanted + "\"; the html-saurus nodes this"
                        + " instance knows are " + portals.stream().map(RemoteNodes.ToolNode::name).toList()
                        + ". Leave node out to use the default.");
            }
            return new Choice(new NodeDocs(remote, wanted), null);
        }
        if (portals.isEmpty()) return new Choice(http(httpBase), null);
        return new Choice(new NodeDocs(remote, portals.get(0).name()), null);
    }
}
