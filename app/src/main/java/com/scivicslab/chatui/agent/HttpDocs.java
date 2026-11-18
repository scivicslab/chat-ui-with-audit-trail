package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** html-saurus's HTTP API at {@code baseUrl}: what {@code search_docs} used before the portals were nodes. */
record HttpDocs(String baseUrl) implements DocsSource {

    @Override
    public List<JsonNode> fullText(String query) {
        return DocSearchTool.hitsFrom(baseUrl + "/api/search?q=" + encode(query) + "&lang=ja", "fulltext", query);
    }

    @Override
    public List<JsonNode> related(String text) {
        return DocSearchTool.postHitsTo(baseUrl + "/api/find-related", text);
    }

    @Override
    public List<JsonNode> semantic(String query) {
        return DocSearchTool.hitsFrom(baseUrl + "/api/search-semantic?q=" + encode(query), "semantic", query);
    }

    @Override
    public List<JsonNode> references(String id, boolean forward) {
        return DocSearchTool.hitsFrom(baseUrl + "/api/" + (forward ? "prerequisites" : "prerequisite-of") + "?id="
                + URLEncoder.encode(id, StandardCharsets.UTF_8), "list_references/" + (forward ? "forward" : "backward"), id);
    }

    @Override
    public String where() {
        return baseUrl;
    }

    private static String encode(String query) {
        return URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
    }
}
