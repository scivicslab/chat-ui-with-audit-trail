package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.plugin.ConversationTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner chat-ui's MCP server calls tools with: it offers only the tools that do not drive the conversation and
 * that the conversation's tool set contains, and runs them without the conversation's {@code ChatSession}
 * (ToolCatalogAndChatUiMcpServer_261120_oo01, achieved states 4 and 6).
 */
@Tag("ToolCatalogAndChatUiMcpServer_261120_oo01")
@DisplayName("ConversationToolRunner runs a conversation's non-driving tools on its own")
class ConversationToolRunnerTest {

    static final ConversationTool ECHO = new ConversationTool() {
        @Override public String name() { return "echo"; }
        @Override public String description() { return "- echo(text): return the text\n"; }
        @Override public String execute(String argumentsJson) { return "echoed " + argumentsJson; }
    };

    static ConversationToolRunner runner(Path dir, ToolSet toolSet) {
        return new ConversationToolRunner(null, "project1", "07", new FileAccessScope(dir, List.of()), null, null,
                null, Map.of("echo", ECHO), toolSet, new ConversationToolRunner.Calculator(), null,
                () -> new ConversationToolRunner.IoPosition(-1, 0, 0));
    }

    @Test
    void itOffersNoToolThatDrivesTheConversation(@TempDir Path dir) {
        ConversationToolRunner r = runner(dir, ToolSet.FULL);
        for (String driving : ConversationToolCatalog.conversationDrivingTools()) assertFalse(r.offers(driving), driving);
        assertTrue(r.offers("read"));
        assertTrue(r.offers("list_chats"));
        assertTrue(r.offers("echo"), "a plugin tool");
        assertFalse(r.offers("no_such_tool"));
    }

    @Test
    void itKeepsToTheConversationsToolSet(@TempDir Path dir) {
        ConversationToolRunner r = runner(dir, ToolSet.HARNESS);
        for (String excluded : ToolSet.HARNESS.excluded()) assertFalse(r.offers(excluded), excluded);
        assertTrue(r.offers("search_docs"));
        assertTrue(r.callFromMcp("read", "{\"path\":\"a.txt\"}").startsWith("error: tool 'read' is not available"));
    }

    @Test
    void itRunsFileToolsInTheConversationsScope(@TempDir Path dir) throws Exception {
        ConversationToolRunner r = runner(dir, ToolSet.FULL);
        r.callFromMcp("write", "{\"path\":\"a.txt\",\"content\":\"hello\"}");
        assertEquals("hello", Files.readString(dir.resolve("a.txt")));
        assertTrue(r.callFromMcp("read", "{\"path\":\"a.txt\"}").contains("hello"));
    }

    @Test
    void itRunsCalcAndPluginTools(@TempDir Path dir) {
        ConversationToolRunner r = runner(dir, ToolSet.FULL);
        assertTrue(r.callFromMcp("calc", "{\"expression\":\"23*47\"}").contains("1081"));
        assertEquals("echoed {\"text\":\"x\"}", r.callFromMcp("echo", "{\"text\":\"x\"}"));
    }

    @Test
    void aDrivingToolCalledThroughTheMcpServerIsRefused(@TempDir Path dir) {
        ConversationToolRunner r = runner(dir, ToolSet.FULL);
        assertTrue(r.callFromMcp("ask_chat", "{\"chatId\":\"02\",\"prompt\":\"hi\"}").startsWith("error: "));
    }

    @Test
    void itIsNamedAfterItsConversation(@TempDir Path dir) {
        assertEquals("project1/chat-07", runner(dir, ToolSet.FULL).conversationName());
    }
}
