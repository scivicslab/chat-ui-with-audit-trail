package com.scivicslab.chatui.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code list_running_tools} shows, beside an AI-workspace row, the chat-ui node name {@code ask_chat} takes, only
 * when that name is a node this instance knows; the known nodes no row was matched to come on one more line; without
 * an AI-workspace list it gives the known names only (ListRunningToolsShowsNodeNames_261119_oo01).
 */
@Tag("ListRunningToolsShowsNodeNames_261119_oo01")
@DisplayName("list_running_tools - the node names ask_chat takes")
class RunningToolsNodeNamesTest {

    static final List<NodeNameListing.Row> ROWS = List.of(
            new NodeNameListing.Row("html-saurus", 28001, "READY", "http://localhost:28001/", ""),
            new NodeNameListing.Row("chat-ui-with-audit-trail-cloud-llm", 28010, "READY", "http://localhost:28010/", "dev"),
            new NodeNameListing.Row("chat-ui-with-audit-trail-local-llm", 28014, "READY", "http://localhost:28014/", ""),
            new NodeNameListing.Row("chat-ui-with-audit-trail", 28016, "STARTING", "http://localhost:28016/", ""));

    static final String ME = "chat-ui-28010";
    static final List<String> OTHERS = List.of("chat-ui-28014", "chat-ui-29999-custom");

    static String listing() {
        String out = NodeNameListing.withRows("ai-workspace-test", "", ROWS, ME, OTHERS);
        System.out.println(out);
        return out;
    }

    static String line(String out, String start) {
        return out.lines().filter(l -> l.startsWith(start)).findFirst().orElse("<no line starting " + start + ">");
    }

    @Test
    void aRowWhoseNameIsAKnownNodeShowsIt() {
        assertTrue(line(listing(), "- chat-ui-with-audit-trail-local-llm").contains("node chat-ui-28014"), listing());
    }

    @Test
    void thisInstanceIsMarked() {
        String mine = line(listing(), "- chat-ui-with-audit-trail-cloud-llm");
        assertTrue(mine.contains("node chat-ui-28010 (this instance)"), mine);
        assertFalse(line(listing(), "- chat-ui-with-audit-trail-local-llm").contains("this instance"));
    }

    @Test
    void aChatUiRowWhoseNameIsNotKnownShowsNoNode() {
        String starting = line(listing(), "- chat-ui-with-audit-trail  port 28016");
        assertTrue(starting.contains("STARTING"), starting);
        assertFalse(starting.contains("node "), starting);
    }

    @Test
    void aKnownNodeWithNoRowIsOnTheOtherNodesLine() {
        assertEquals("other nodes this instance knows: [chat-ui-29999-custom]",
                line(listing(), "other nodes"));
    }

    @Test
    void theOtherNodesLineIsLeftOutWhenEveryKnownNodeHasARow() {
        String out = NodeNameListing.withRows("ai-workspace-test", "", ROWS, ME, List.of("chat-ui-28014"));
        assertFalse(out.contains("other nodes"), out);
    }

    @Test
    void thisInstanceWithoutARowIsOnTheOtherNodesLineMarked() {
        String out = NodeNameListing.withRows("ai-workspace-test", "html-saurus", ROWS.subList(0, 1), ME, OTHERS);
        assertEquals("other nodes this instance knows: [chat-ui-28010 (this instance), chat-ui-28014, chat-ui-29999-custom]",
                line(out, "other nodes"), out);
        assertTrue(out.startsWith("1 tool instance of html-saurus in the AI-workspace ai-workspace-test"), out);
    }

    @Test
    void aRowThatIsNotChatUiGetsNoNode() {
        String saurus = line(listing(), "- html-saurus");
        assertEquals("- html-saurus  port 28001  READY  http://localhost:28001/", saurus);
    }

    @Test
    void theRowsKeepTheirFieldsAndCount() {
        String out = listing();
        assertTrue(out.startsWith("4 tool instances in the AI-workspace ai-workspace-test, in any state:"), out);
        assertEquals("- chat-ui-with-audit-trail-cloud-llm  port 28010  READY  node chat-ui-28010 (this instance)"
                + "  http://localhost:28010/  \"dev\"", line(out, "- chat-ui-with-audit-trail-cloud-llm"));
    }

    @Test
    void noRowsStillListTheKnownNodes() {
        String out = NodeNameListing.withRows("ai-workspace-test", "frame-viewer", List.of(), ME, OTHERS);
        assertTrue(out.startsWith("No tool instances of frame-viewer are running in the AI-workspace ai-workspace-test."), out);
        assertTrue(out.contains("other nodes this instance knows: [chat-ui-28010 (this instance), chat-ui-28014, chat-ui-29999-custom]"), out);
    }

    @Test
    void withoutAnAiWorkspaceListTheNamesAloneAreGiven() {
        String out = NodeNameListing.namesOnly("this chat-ui instance knows no AI-workspace node", ME, OTHERS);
        System.out.println(out);
        assertFalse(out.startsWith("error:"), out);
        assertEquals(List.of(
                "There is no AI-workspace list of running tools here: this chat-ui instance knows no AI-workspace node."
                        + " The chat-ui nodes this instance knows, by name only:",
                "- chat-ui-28010 (this instance)",
                "- chat-ui-28014",
                "- chat-ui-29999-custom"), out.lines().toList());
    }
}
