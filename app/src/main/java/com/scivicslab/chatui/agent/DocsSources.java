package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.remote.RemoteNodes;

import java.util.List;

/**
 * Chooses the {@link DocsSource} {@code search_docs} and {@code list_references} use: an html-saurus node of the
 * distributed actor system, or html-saurus's HTTP API ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 7).
 * It stays in the app because it reads {@link RemoteNodes}; {@link DocsSource} itself is part of agent-connector-kernel.
 */
public final class DocsSources {

    private DocsSources() {
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
    public static DocsSource.Choice choose(RemoteNodes remote, String node, String httpBase) {
        String wanted = node == null ? "" : node.strip();
        List<RemoteNodes.ToolNode> portals = remote == null ? List.of() : remote.toolNodes(DocsSource.KIND);
        if (!wanted.isEmpty()) {
            if (remote != null && portals.stream().noneMatch(n -> n.name().equals(wanted))) {
                remote.refreshPeers();
                portals = remote.toolNodes(DocsSource.KIND);
            }
            if (portals.stream().noneMatch(n -> n.name().equals(wanted))) {
                return new DocsSource.Choice(null, "error: no html-saurus node named \"" + wanted + "\"; the html-saurus nodes this"
                        + " instance knows are " + portals.stream().map(RemoteNodes.ToolNode::name).toList()
                        + ". Leave node out to use the default.");
            }
            return new DocsSource.Choice(new NodeDocs(remote, wanted), null);
        }
        if (portals.isEmpty()) return new DocsSource.Choice(DocsSource.http(httpBase), null);
        return new DocsSource.Choice(new NodeDocs(remote, portals.get(0).name()), null);
    }

}
