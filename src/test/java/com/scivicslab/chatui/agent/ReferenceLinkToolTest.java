package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the reference-following tool's arguments and for the list shape it shares with
 * {@code search_docs} ({@code DocRetrievalAgentLoop_260830_oo01}). No html-saurus is contacted: the
 * hit objects are built here in the shape {@code /api/prerequisites} returns.
 *
 * <p>The shape matters as much as the values. The tool returns the same kind of candidate list the
 * search tool does, so that a conversation which has learned to call {@code read} on a candidate's
 * {@code path} does not have to learn a second way of reading a result.</p>
 */
class ReferenceLinkToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One hit in the shape html-saurus's /api/prerequisites returns. */
    private static JsonNode hit(String id, String title, String srcPath, String summary) {
        return MAPPER.createObjectNode()
                .put("id", id)
                .put("title", title)
                .put("path", "/site/" + id + ".html")
                .put("srcPath", srcPath)
                .put("summary", summary)
                .put("category", "");
    }

    @Test
    void aBlankIdIsRejectedBeforeAnyRequest() {
        assertEquals("error: id required", ReferenceLinkTool.list(null, "prerequisites"));
        assertEquals("error: id required", ReferenceLinkTool.list("  ", "prerequisites"));
    }

    /**
     * A direction that is neither of the two says so, rather than silently following the default.
     * Silently defaulting would answer the opposite question from the one that was asked whenever
     * the model wrote the direction slightly wrong.
     */
    @Test
    void anUnknownDirectionIsRejectedAndNamesTheTwoThatWork() {
        String result = ReferenceLinkTool.list("Overview_260712_oo01", "backwards");

        assertTrue(result.startsWith("error: direction must be"), "got: " + result);
        assertTrue(result.contains("prerequisites"), "got: " + result);
        assertTrue(result.contains("prerequisite-of"), "got: " + result);
    }

    /** The rendering both tools share: number, title, id, source path, summary. */
    @Test
    void hitsRenderWithTheIdAndThePathTheNextCallNeeds() {
        String block = DocSearchTool.renderHits(List.of(
                hit("GraphEngineering_260731_oo01", "Graph Engineering",
                        "/home/devteam/works/doc_SCIVICS000/docs/.../060_GraphEngineering.md",
                        "複数のAIエージェントを有向グラフとして設計する"),
                hit("LoopEngineering_260731_oo01", "Loop Engineering",
                        "/home/devteam/works/doc_SCIVICS000/docs/.../050_LoopEngineering.md",
                        "自律的な開発サイクルの解説")));

        assertTrue(block.contains("1. Graph Engineering"), block);
        assertTrue(block.contains("2. Loop Engineering"), block);
        // The id is what a following list_references call takes; the path is what read takes.
        assertTrue(block.contains("id: GraphEngineering_260731_oo01"), block);
        assertTrue(block.contains("path: /home/devteam/works/doc_SCIVICS000/docs/.../060_GraphEngineering.md"),
                block);
        assertTrue(block.contains("複数のAIエージェント"), block);
    }

    /** Nothing to show renders as empty, so each caller can say what "none" means for it. */
    @Test
    void noHitsRenderAsEmptySoTheCallerSuppliesItsOwnWording() {
        assertEquals("", DocSearchTool.renderHits(List.of()));
        assertEquals("", DocSearchTool.renderHits(List.of(MAPPER.createObjectNode())));
    }
}
