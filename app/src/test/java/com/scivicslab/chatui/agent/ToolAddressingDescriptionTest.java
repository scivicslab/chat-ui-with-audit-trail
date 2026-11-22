package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * How the tools that take {@code node} tell a model to write where a conversation is. project9/chat-01 sent
 * {@code chatId:"chat-01"} with {@code node:"chat-ui-28013"} to reach project1/chat-01, which is on its own instance:
 * the description gave {@code "02"} and {@code "project2/02"} as examples, never said that every project of this
 * instance takes no node, and never said that a chatId on another instance starts with its project.
 */
class ToolAddressingDescriptionTest {

    @Test
    void askChat_namesTheThreeWaysToWriteWhereAConversationIs_inTheFormIdsHave() {
        String d = ToolCatalog.builtIn("ask_chat").description().replaceAll("\\s+", " ");
        assertTrue(d.contains("\"chat-02\""), "own project: " + d);
        assertTrue(d.contains("\"project1/chat-01\", with no node"), "another project of this instance: " + d);
        assertTrue(d.contains("Every project shown on this instance's screen is on this instance"), d);
        assertTrue(d.contains("list_running_tools"), "another instance: " + d);
        assertTrue(d.contains("with node, a chatId without its project"), "the refusal: " + d);
        assertFalse(d.contains("\"02\" for one in your own project"), "the old example is gone: " + d);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask_chat", "ask_chat_urgent", "cancel_request", "expect_report", "set_collaborator"})
    void everyToolThatTakesNode_saysAConversationOfThisInstanceTakesNoNode(String tool) {
        String d = ToolCatalog.builtIn(tool).description().replaceAll("\\s+", " ");
        assertTrue(d.contains("takes no node"), tool + ": " + d);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask_chat_urgent", "expect_report", "set_collaborator"})
    void toolsThatNameAConversationOnANode_writeItWithItsProject(String tool) {
        String d = ToolCatalog.builtIn(tool).description().replaceAll("\\s+", " ");
        assertTrue(d.contains("\"project1/chat-01\""), tool + ": " + d);
    }
}
