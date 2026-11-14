package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation's name in the Project Property list and its name in the left-pane tree show the conversation
 * through one function, {@code showConversation} in console.js, so the two cannot drift apart
 * ({@code ProjectPropertyChatLinks_261114_oo01}). The behaviour in a browser is checked by
 * {@code e2e/ProjectChatLinkCheck.java}; this test keeps the structure that makes the two the same.
 */
class ProjectPropertyChatLinkTest {

    private static String consoleJs() throws IOException {
        try (InputStream in = ProjectPropertyChatLinkTest.class.getResourceAsStream("/META-INF/resources/console.js")) {
            assertTrue(in != null, "console.js is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) n++;
        return n;
    }

    /** The source of the function {@code name}: from its header to the next function at the same indent. */
    private static String functionBody(String js, String name) {
        Matcher m = Pattern.compile("(?m)^    function " + name + "\\(").matcher(js);
        assertTrue(m.find(), name + " is defined");
        Matcher next = Pattern.compile("(?m)^    (function |var |// ──)").matcher(js);
        int end = js.length();
        if (next.find(m.end())) end = next.start();
        return js.substring(m.start(), end);
    }

    @Test
    void neitherClickSwitchesTheChatPaneItself() throws IOException {
        String js = consoleJs();
        assertTrue(!functionBody(js, "actorNodeEl").contains("chatUiSwitchChat("),
                "the tree's click goes through showConversation");
        assertTrue(!functionBody(js, "projectPropListChats").contains("chatUiSwitchChat("),
                "the Project Property click goes through showConversation");
        assertTrue(functionBody(js, "showConversation").contains("window.chatUiSwitchChat("),
                "showConversation is where a conversation is shown");
    }

    @Test
    void theTreeNameAndTheProjectPropertyNameBothCallShowConversation() throws IOException {
        String js = consoleJs();
        assertEquals(2, count(js, "showConversation(") - count(js, "function showConversation("),
                "two callers: the tree and Project Property");
        assertTrue(functionBody(js, "actorNodeEl").contains("showConversation(tabMatch[1], tabMatch[2])"),
                "the tree's click");
        assertTrue(functionBody(js, "projectPropListChats").contains("showConversation(projectId, chatId)"),
                "the Project Property click");
    }

    @Test
    void showConversationDoesEverythingATreeClickDid() throws IOException {
        String body = functionBody(consoleJs(), "showConversation");
        for (String step : new String[] {"switchPerspective(\"chat\")", "refreshActors()", "expandActorsAbove(",
                "ioSessionsLoaded = false", "lastLogSig = null", "wfOnShow()", "ioLoadSessions()", "refreshLogs()"}) {
            assertTrue(body.contains(step), "showConversation does " + step);
        }
    }
}
