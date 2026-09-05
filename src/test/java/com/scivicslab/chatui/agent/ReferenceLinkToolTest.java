package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the reference-following tool's arguments and for the list shape it shares with
 * {@code search_docs} ({@code ReferenceLinkTool_260904_oo01}). No html-saurus is contacted: the
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

    /** The same hit with the kind its author wrote, which is what groups the rendered list. */
    private static JsonNode hit(String id, String title, String srcPath, String summary,
                                String relation) {
        return ((com.fasterxml.jackson.databind.node.ObjectNode)
                hit(id, title, srcPath, summary)).put("relation", relation);
    }

    @Test
    void aBlankIdIsRejectedBeforeAnyRequest() {
        assertEquals("error: id required", ReferenceLinkTool.list(null, "forward", ""));
        assertEquals("error: id required", ReferenceLinkTool.list("  ", "forward", ""));
    }

    /**
     * A direction that is neither of the two says so, rather than silently following the default.
     * Silently defaulting would answer the opposite question from the one that was asked whenever
     * the model wrote the direction slightly wrong.
     */
    @Test
    void anUnknownDirectionIsRejectedAndNamesTheTwoThatWork() {
        String result = ReferenceLinkTool.list("Overview_260712_oo01", "sideways", "");

        assertTrue(result.startsWith("error: direction must be"), "got: " + result);
        assertTrue(result.contains("forward"), "got: " + result);
        assertTrue(result.contains("backward"), "got: " + result);
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

    /**
     * The rendering the conversation actually sees for the two references
     * {@code NamingByTypeAndInstance_260628_oo01} declares. The kinds head their groups, and the
     * numbering runs on across them, so an entry can be named by its number alone. This is the
     * output {@code ReferenceLinkTool_260904_oo01} quotes.
     */
    @Test
    void edgesAreGroupedUnderTheKindTheirAuthorWrote() {
        String out = ReferenceLinkTool.renderForTest(List.of(
                hit("HelloWorld_260808_oo01",
                        "Quarkusの最小形（Hello World）— Quarkus_260808_oo01との比較用",
                        "/home/devteam/works/doc_SCIVICS003/docs/quarkus-gpu-broker/"
                                + "030_development/010_skeleton/000_HelloWorld_260808_oo01/"
                                + "000_HelloWorld_260808_oo01.md",
                        "〈前提条件〉QuarkusのCDIとPOJO-actorのActorSystemがどう同居すべきかを論じる前に、",
                        "best-practice"),
                hit("NamingByTypeAndInstanceExamples_260811_oo01",
                        "実体の命名 — 種別とインスタンスの一意命名（実例）",
                        "/home/devteam/works/doc_SCIVICS000/docs/ProjectStandard/"
                                + "010_ProjectStandards/024_NamingByTypeAndInstanceExamples_260811_oo01/"
                                + "024_NamingByTypeAndInstanceExamples_260811_oo01.md",
                        "`NamingByTypeAndInstance_260628_oo01` が定める命名規則を、実際に起きた事故に対応づける文書です。",
                        "anti-pattern")));

        assertTrue(out.startsWith("[best-practice] 1 document\n1. Quarkusの最小形"), out);
        assertTrue(out.contains("[anti-pattern] 1 document\n2. 実体の命名"), out);
        // The "kind is missing" note belongs only to the ungrouped rendering.
        assertTrue(!out.contains("does not report the kind"), out);
    }
}
