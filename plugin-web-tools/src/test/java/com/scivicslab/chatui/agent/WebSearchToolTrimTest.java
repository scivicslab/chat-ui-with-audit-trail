package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WebSearchTool — what it says about the part of a page it dropped")
class WebSearchToolTrimTest {

    @Test
    void shorterThanTheLimit_isReturnedWhole() {
        assertEquals("short page", WebSearchTool.trim("  short page  ", 600));
    }

    /**
     * The old form appended " …" and nothing else. A page may end that way on its own, so the
     * reader could not tell a whole page from a cut one and answered from an article's opening
     * believing it had the article.
     */
    @Test
    void longerThanTheLimit_saysHowMuchWasKeptOfHowMuch() {
        String page = "x".repeat(5000);

        String out = WebSearchTool.trim(page, 600);

        assertTrue(out.startsWith("x".repeat(600)));
        assertTrue(out.contains("[kept 600 of 5000 chars]"));
        assertFalse(out.endsWith(" …"), "the bare ellipsis is what could not be told apart");
    }

    @Test
    void exactlyTheLimit_isNotMarkedAsCut() {
        String out = WebSearchTool.trim("y".repeat(600), 600);

        assertEquals(600, out.length());
        assertFalse(out.contains("kept"));
    }
}
