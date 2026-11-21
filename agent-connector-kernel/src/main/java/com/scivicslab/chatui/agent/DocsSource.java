package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Where {@code search_docs} and {@code list_references} get their answers: an html-saurus portal reached as a node of
 * the distributed actor system, or, when this instance knows none, html-saurus's HTTP API
 * ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 7). Both answer the same JSON hits, so the tools'
 * answers do not depend on which one was used.
 *
 * <p>Choosing between them needs the distributed actor system, which stays with the screen and the record; that choice
 * is {@code DocsSources.choose} in the app. This interface and its HTTP implementation are part of
 * agent-connector-kernel.</p>
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
}
