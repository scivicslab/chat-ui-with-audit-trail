package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Follows the reference links an author declared between documents
 * ({@code DocRetrievalAgentLoop_260830_oo01}).
 *
 * <p>This is not a search. {@link DocSearchTool} returns documents that resemble a query, ranked by
 * similarity, and the ranking moves when the corpus or the query changes. This tool returns the
 * documents named in a document's own {@code ## 参考文献} section, or the documents that name it
 * there. That relation is written in the Markdown source, so the same id returns the same documents
 * until someone edits the source.</p>
 *
 * <p>Each edge carries a kind, written by the author as {@code data-relation}. The vocabulary is not
 * defined in advance ({@code RelationKind_260830_oo01}): this tool never checks a kind against a
 * list of known names, and never drops an edge whose kind it does not recognise. It groups by
 * whatever string came back.</p>
 */
public final class ReferenceLinkTool {

    private static final Logger LOG = Logger.getLogger(ReferenceLinkTool.class.getName());

    /** The documents this one refers to: its own {@code ## 参考文献} section. */
    static final String FORWARD = "forward";
    /** The documents that refer to this one. */
    static final String BACKWARD = "backward";

    /**
     * Cap on the backward direction. A document's own section names as many as its author wrote, so
     * the forward direction is bounded by the source; the backward direction is not — a standard
     * that many documents declare as their prerequisite has as many edges as there are documents.
     */
    static final int BACKWARD_LIMIT = 30;

    private ReferenceLinkTool() {}

    /**
     * Lists the documents related to one document by an author-declared reference.
     *
     * @param id        the document id, as {@code search_docs} prints it on each candidate and as a
     *                  document's own front matter carries it
     * @param direction {@value #FORWARD} (the default when blank) or {@value #BACKWARD}
     * @param relation  keep only edges of this kind; blank keeps every kind
     * @return the related documents as the Observation text, or a message saying there are none
     */
    public static String list(String id, String direction, String relation) {
        if (id == null || id.isBlank()) return "error: id required";
        String docId = id.strip();
        String which = (direction == null || direction.isBlank()) ? FORWARD : direction.strip();
        if (!FORWARD.equals(which) && !BACKWARD.equals(which)) {
            return "error: direction must be \"" + FORWARD + "\" or \"" + BACKWARD + "\"";
        }
        String wanted = (relation == null) ? "" : relation.strip();

        String endpoint = FORWARD.equals(which) ? "prerequisites" : "prerequisite-of";
        String url = DocSearchTool.baseUrl() + "/api/" + endpoint
                + "?id=" + URLEncoder.encode(docId, StandardCharsets.UTF_8);
        LOG.info("list_references (" + which + (wanted.isEmpty() ? "" : ", " + wanted) + "): " + docId);
        List<JsonNode> hits = DocSearchTool.hitsFrom(url, "list_references/" + which, docId);

        if (!wanted.isEmpty()) {
            List<JsonNode> kept = new ArrayList<>();
            for (JsonNode h : hits) if (wanted.equals(kindOf(h))) kept.add(h);
            if (kept.isEmpty() && !hits.isEmpty() && !anyKindPresent(hits)) {
                return "Cannot filter by relation yet: html-saurus does not report the kind of an"
                        + " edge, so " + hits.size() + " edge(s) were found but none could be matched"
                        + " against \"" + wanted + "\". Call again without the relation argument.";
            }
            hits = kept;
        }
        if (hits.isEmpty()) return nothingFound(which, docId, wanted);

        boolean truncated = false;
        if (BACKWARD.equals(which) && hits.size() > BACKWARD_LIMIT) {
            hits = hits.subList(0, BACKWARD_LIMIT);
            truncated = true;
        }
        return header(which, docId, hits.size(), truncated) + "\n\n" + body(hits);
    }

    /** @return the edge's kind as the author wrote it, or {@code ""} when none came back */
    private static String kindOf(JsonNode hit) {
        return hit.path("relation").asText("").strip();
    }

    private static boolean anyKindPresent(List<JsonNode> hits) {
        for (JsonNode h : hits) if (!kindOf(h).isEmpty()) return true;
        return false;
    }

    /**
     * Renders the edges grouped by kind, one group per kind in the order the kinds first appear.
     * Numbering runs continuously across the groups, so an entry can be named by its number alone.
     *
     * <p>When no edge carries a kind — which is the case until html-saurus reports it — the list is
     * rendered flat, with a line saying the kind is missing rather than a group named after an
     * empty string.</p>
     */
    private static String body(List<JsonNode> hits) {
        if (!anyKindPresent(hits)) {
            return DocSearchTool.renderHits(hits)
                    + "\n\n(html-saurus does not report the kind of each edge yet, so these are"
                    + " ungrouped. Read a document to see why its author listed it.)";
        }
        Map<String, List<JsonNode>> byKind = new LinkedHashMap<>();
        for (JsonNode h : hits) {
            String k = kindOf(h);
            byKind.computeIfAbsent(k.isEmpty() ? "(kind not stated)" : k, x -> new ArrayList<>()).add(h);
        }
        StringBuilder sb = new StringBuilder();
        int next = 1;
        for (Map.Entry<String, List<JsonNode>> e : byKind.entrySet()) {
            List<JsonNode> group = e.getValue();
            sb.append("[").append(e.getKey()).append("] ")
              .append(group.size()).append(group.size() == 1 ? " document" : " documents").append("\n")
              .append(DocSearchTool.renderHits(group, next)).append("\n\n");
            next += group.size();
        }
        return sb.toString().stripTrailing();
    }

    private static String header(String which, String docId, int shown, boolean truncated) {
        String what = FORWARD.equals(which)
                ? "Documents " + docId + " refers to (forward)."
                : "Documents that refer to " + docId + " (backward).";
        String tail = truncated
                ? " Showing the first " + shown + "; more exist."
                : " " + shown + (shown == 1 ? " document." : " documents.");
        return what + tail + " These are the authors' own references, not search results — call read"
                + " on a path to see what a document actually says.";
    }

    /**
     * @return why nothing came back. An id that resolves but declares nothing, an id that does not
     *         resolve, and a section whose form html-saurus could not parse all arrive here as an
     *         empty list, so the wording says which checks are worth making rather than asserting
     *         that no relation exists.
     */
    private static String nothingFound(String which, String docId, String wanted) {
        String scope = wanted.isEmpty() ? "" : " of kind \"" + wanted + "\"";
        return FORWARD.equals(which)
                ? "No documents" + scope + " are listed in " + docId + "'s references. Either it"
                  + " declares none, its section is not in the form html-saurus can parse, or the id"
                  + " does not resolve — check the id against a search_docs candidate."
                : "No documents" + scope + " refer to " + docId + ". Note this direction is answered"
                  + " from a precomputed index, so a very recent edit may be missing.";
    }
}
