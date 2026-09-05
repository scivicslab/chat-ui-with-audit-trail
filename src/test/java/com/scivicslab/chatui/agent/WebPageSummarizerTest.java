package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebPageSummarizer — summarising each fetched page instead of keeping its opening")
class WebPageSummarizerTest {

    private static String observation(String... bodies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bodies.length; i++) {
            sb.append(i + 1).append(". Title ").append(i + 1).append("\n")
              .append("   URL: https://example.com/").append(i + 1).append("\n")
              .append("   ").append(bodies[i]).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String longPage(int chars) {
        return "word ".repeat(chars / 5);
    }

    @Test
    void eachResultBecomesItsOwnBlock() {
        List<String> blocks = WebPageSummarizer.splitBlocks(observation("a", "b", "c"));

        assertEquals(3, blocks.size());
        assertTrue(blocks.get(0).startsWith("1. Title 1"));
        assertTrue(blocks.get(2).startsWith("3. Title 3"));
    }

    /** The fetched page is indented, so a numbered list inside a page must not split the block. */
    @Test
    void aNumberedLineInsideAPageDoesNotSplitIt() {
        String obs = "1. Title\n   URL: https://example.com\n   text\n   2. this is part of the page\n";

        assertEquals(1, WebPageSummarizer.splitBlocks(obs).size());
    }

    @Test
    void aLongPageIsReplacedByItsSummaryAndSaysWhatItCameFrom() {
        WebPageSummarizer summarizer = new WebPageSummarizer(page -> "SUMMARY");

        String out = summarizer.summarize(observation(longPage(5000)));

        assertTrue(out.contains("SUMMARY"));
        assertTrue(out.contains("[summarised from"));
        assertTrue(out.contains("1. Title 1"), "the numbering and title stay");
        assertTrue(out.contains("URL: https://example.com/1"), "the URL stays, so the page can be fetched whole");
        assertFalse(out.contains("word word word word"), "the page body is gone from the model's copy");
    }

    /** A page already short enough costs nothing to pass through, and an LLM call would be waste. */
    @Test
    void aShortPageIsLeftAloneAndCostsNoCall() {
        ConcurrentLinkedQueue<String> calls = new ConcurrentLinkedQueue<>();
        WebPageSummarizer summarizer = new WebPageSummarizer(page -> {
            calls.add(page);
            return "SUMMARY";
        });

        String out = summarizer.summarize(observation("a short page"));

        assertTrue(out.contains("a short page"));
        assertEquals(0, calls.size());
    }

    @Test
    void everyLongPageIsSummarised() {
        ConcurrentLinkedQueue<String> calls = new ConcurrentLinkedQueue<>();
        WebPageSummarizer summarizer = new WebPageSummarizer(page -> {
            calls.add(page);
            return "S";
        });

        summarizer.summarize(observation(longPage(3000), longPage(3000), longPage(3000)));

        assertEquals(3, calls.size());
    }

    /** A failed call must leave the page as it was rather than losing it. */
    @Test
    void aFailedCallKeepsThePage() {
        WebPageSummarizer summarizer = new WebPageSummarizer(page -> "");

        String out = summarizer.summarize(observation(longPage(3000)));

        assertTrue(out.contains("word word"), "the page survives when the summary comes back empty");
    }

    @Test
    void somethingThatIsNotAResultListIsPassedThrough() {
        WebPageSummarizer summarizer = new WebPageSummarizer(page -> "SUMMARY");

        assertEquals("No results found.", summarizer.summarize("No results found."));
    }

    @Test
    void theInstructionNamesWhatMustSurviveTheSummary() {
        String prompt = WebPageSummarizer.promptFor("PAGE TEXT");

        assertTrue(prompt.contains("PAGE TEXT"));
        assertTrue(prompt.contains("fact"));
        assertTrue(prompt.contains("number"));
    }
}
