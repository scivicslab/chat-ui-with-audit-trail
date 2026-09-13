package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure tests (no HTTP): the request the tool builds and how it lays out what OpenAlex returns. */
@DisplayName("ScholarSearchTool — the OpenAlex request and the shape of its answer")
class ScholarSearchToolTest {

    @Test
    void buildUrl_searchesTitleAndAbstract_andSortsByRelevanceByDefault() {
        String url = ScholarSearchTool.buildUrl("gamification vocabulary", null, null, 10, null);

        assertTrue(url.startsWith("https://api.openalex.org/works?filter="));
        assertTrue(url.contains("title_and_abstract.search%3Agamification+vocabulary"), url);
        assertTrue(url.contains("sort=relevance_score%3Adesc"), url);
        assertTrue(url.contains("per-page=10"), url);
        assertFalse(url.contains("mailto"), "no mailto unless configured");
    }

    @Test
    void buildUrl_citedSort_yearFrom_andMailto() {
        String url = ScholarSearchTool.buildUrl("spaced repetition", "cited", 2015, 25, "ops@example.org");

        assertTrue(url.contains("sort=cited_by_count%3Adesc"), url);
        assertTrue(url.contains("from_publication_date%3A2015-01-01"), url);
        assertTrue(url.contains("per-page=25"), url);
        assertTrue(url.contains("mailto=ops%40example.org"), url);
    }

    @Test
    void buildUrl_commaInQuery_doesNotBecomeASecondFilter() {
        // OpenAlex separates filters with commas; a comma typed in the query must not split it.
        String url = ScholarSearchTool.buildUrl("gamification, vocabulary", null, null, 5, null);
        assertTrue(url.contains("gamification++vocabulary") || url.contains("gamification+vocabulary"), url);
        assertFalse(url.contains("%2C"), "a comma would start a new filter: " + url);
    }

    @Test
    void parseLimit_clampsToOneThroughTwentyFive_andDefaultsToTen() {
        assertEquals(10, ScholarSearchTool.parseLimit(null));
        assertEquals(10, ScholarSearchTool.parseLimit("abc"));
        assertEquals(1, ScholarSearchTool.parseLimit("0"));
        assertEquals(25, ScholarSearchTool.parseLimit("100"));
        assertEquals(7, ScholarSearchTool.parseLimit(" 7 "));
    }

    @Test
    void parseYear_acceptsFourDigitYearsOnly() {
        assertEquals(2015, ScholarSearchTool.parseYear("2015"));
        assertEquals(null, ScholarSearchTool.parseYear(""));
        assertEquals(null, ScholarSearchTool.parseYear("15"));
        assertEquals(null, ScholarSearchTool.parseYear("last year"));
    }

    @Test
    void abstractOf_putsTheInvertedIndexBackInOrder() {
        // Parsed from text, as the real response is: put(String, int[]) would store the bare array.
        JSONObject inverted = new JSONObject(
                "{\"vocabulary\": [2], \"Gamified\": [0], \"learning\": [1, 3]}");
        assertEquals("Gamified learning vocabulary learning", ScholarSearchTool.abstractOf(inverted));
        assertEquals("", ScholarSearchTool.abstractOf(null));
    }

    @Test
    void format_listsEachWorkWithItsMetadata_andSaysWhenAbstractOrDoiIsMissing() {
        String response = """
            {"meta": {"count": 859},
             "results": [
               {"display_name": "Vocabulary by Gamification", "publication_year": 2017, "cited_by_count": 61,
                "doi": "https://doi.org/10.1002/trtr.1645",
                "authorships": [{"author": {"display_name": "Tara Kingsley"}}, {"author": {"display_name": "Melissa Grabner-Hagen"}}],
                "primary_location": {"source": {"display_name": "The Reading Teacher"}},
                "open_access": {"oa_url": null},
                "abstract_inverted_index": {"Gamification": [0], "uses": [1], "quests.": [2]}},
               {"display_name": "Untitled draft", "publication_year": 0, "cited_by_count": 0,
                "authorships": [], "primary_location": null, "open_access": {"oa_url": "https://example.org/p.pdf"},
                "abstract_inverted_index": null}
             ]}
            """;

        String out = ScholarSearchTool.format(new JSONObject(response), "gamification vocabulary", "cited", 10);

        assertTrue(out.startsWith("OpenAlex: 859 works match \"gamification vocabulary\"; showing 2 sorted by citation count."), out);
        assertTrue(out.contains("1. Vocabulary by Gamification (2017)"), out);
        assertTrue(out.contains("Authors: Tara Kingsley, Melissa Grabner-Hagen"), out);
        assertTrue(out.contains("Venue: The Reading Teacher"), out);
        assertTrue(out.contains("Cited by: 61"), out);
        assertTrue(out.contains("DOI: https://doi.org/10.1002/trtr.1645"), out);
        assertTrue(out.contains("Abstract: Gamification uses quests."), out);
        assertFalse(out.contains("Open access: null"), "a null oa_url must not be printed");
        assertTrue(out.contains("2. Untitled draft\n"), "no year when OpenAlex has none: " + out);
        assertTrue(out.contains("Open access: https://example.org/p.pdf"), out);
        assertTrue(out.contains("Abstract: (none)"), out);
        assertFalse(out.contains("DOI: \n"), out);
    }

    @Test
    void format_emptyResults_saysSo() {
        String out = ScholarSearchTool.format(new JSONObject("{\"meta\":{\"count\":0},\"results\":[]}"), "zzz", "relevance", 10);
        assertEquals("No works found in OpenAlex for \"zzz\".", out);
    }

    @Test
    void trim_marksACutAbstractWithItsRealLength() {
        String out = ScholarSearchTool.trim("a".repeat(1000), 900);
        assertTrue(out.startsWith("a".repeat(900)));
        assertTrue(out.endsWith("…[1000 chars]"), out);
    }
}
