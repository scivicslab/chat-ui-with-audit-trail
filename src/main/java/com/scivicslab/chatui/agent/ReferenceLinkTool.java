package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * <p>Both directions are one tool with a {@code direction} argument rather than two tools. Two names
 * would make the model choose between names; one name with a direction makes it choose between
 * "what this document builds on" and "what builds on this document", which is the decision it
 * actually faces.</p>
 */
public final class ReferenceLinkTool {

    private static final Logger LOG = Logger.getLogger(ReferenceLinkTool.class.getName());

    /** Follows the declared prerequisites of a document: what it says to read first. */
    static final String FORWARD = "prerequisites";
    /** Follows the reverse: the documents that declare this one as their prerequisite. */
    static final String REVERSE = "prerequisite-of";

    private ReferenceLinkTool() {}

    /**
     * Lists the documents related to one document by an author-declared reference.
     *
     * @param id        the document id, as {@code search_docs} prints it on each candidate and as
     *                  a document's own front matter carries it
     * @param direction {@value #FORWARD} (the default when blank) or {@value #REVERSE}
     * @return the related documents as the Observation text, or a message saying there are none
     */
    public static String list(String id, String direction) {
        if (id == null || id.isBlank()) return "error: id required";
        String docId = id.strip();
        String which = (direction == null || direction.isBlank()) ? FORWARD : direction.strip();
        if (!FORWARD.equals(which) && !REVERSE.equals(which)) {
            return "error: direction must be \"" + FORWARD + "\" or \"" + REVERSE + "\"";
        }

        String url = DocSearchTool.baseUrl() + "/api/" + which
                + "?id=" + URLEncoder.encode(docId, StandardCharsets.UTF_8);
        LOG.info("list_references (" + which + "): " + docId);
        List<JsonNode> hits = DocSearchTool.hitsFrom(url, "list_references/" + which, docId);
        String block = DocSearchTool.renderHits(hits);

        if (block.isEmpty()) {
            // An id that resolves but has no references gives an empty array, and an id that does
            // not resolve gives HTTP 404, which hitsFrom also turns into an empty list. The two are
            // worth telling apart to the reader, so say what was asked rather than only "none".
            return FORWARD.equals(which)
                    ? "No documents are listed as prerequisites of " + docId
                      + ". Either it declares none, or the id does not resolve — check the id"
                      + " against a search_docs candidate."
                    : "No documents list " + docId + " as their prerequisite. Note this direction is"
                      + " answered from a precomputed index, so a very recent edit may be missing.";
        }
        return header(which, docId) + "\n\n" + block;
    }

    private static String header(String which, String docId) {
        return FORWARD.equals(which)
                ? "Documents that " + docId + " declares should be read first. These are the"
                  + " author's own references, not search results — call read on a path to see"
                  + " what a document actually says."
                : "Documents that declare " + docId + " as a prerequisite. Call read on a path to"
                  + " see what a document actually says.";
    }
}
