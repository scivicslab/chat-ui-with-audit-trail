package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for reading a recorded conversation tab's name back into the project and
 * conversation it names. This is what decides which conversations a restart re-opens: a name this
 * refuses stays closed, and the conversation behind it is invisible until someone guesses it.
 */
class TabIdSplitTest {

    /** Whatever chatActorName writes, splitTabId must read back. */
    @Test
    void everyNameChatActorNameProducesIsReadBack() {
        for (String projectId : new String[] {"project1", "project2", "project17"}) {
            for (String chatId : new String[] {"01", "02", "10"}) {
                String name = ChatUiActorSystem.chatActorName(projectId, chatId);
                assertArrayEquals(new String[] {projectId, chatId}, ChatUiActorSystem.splitTabId(name),
                        "round trip failed for " + name);
            }
        }
    }

    @Test
    void theProjectAndTheConversationAreSeparated() {
        assertEquals("project2", ChatUiActorSystem.splitTabId("project2/chat-01")[0]);
        assertEquals("01", ChatUiActorSystem.splitTabId("project2/chat-01")[1]);
    }

    /** Names from before this scheme, and malformed ones, re-open nothing rather than half a tab. */
    @Test
    void aNameThisSchemeNeverProducedIsRefused() {
        assertNull(ChatUiActorSystem.splitTabId(null));
        assertNull(ChatUiActorSystem.splitTabId(""));
        assertNull(ChatUiActorSystem.splitTabId("01"));               // the pre-project tab id
        assertNull(ChatUiActorSystem.splitTabId("project2"));         // no conversation part
        assertNull(ChatUiActorSystem.splitTabId("project2/chat-"));   // empty conversation id
        assertNull(ChatUiActorSystem.splitTabId("/chat-01"));         // no project part
    }
}
