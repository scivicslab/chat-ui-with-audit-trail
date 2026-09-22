package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit test for reading a recorded ConversationSquad's name back into the project and
 * conversation it names. This is what decides which conversations a restart re-opens: a name this
 * refuses stays closed, and the conversation behind it is invisible until someone guesses it.
 */
class ConversationSquadIdSplitTest {

    /** Whatever chatActorName writes, splitConversationSquadId must read back. */
    @Test
    void everyNameChatActorNameProducesIsReadBack() {
        for (String projectId : new String[] {"project1", "project2", "project17"}) {
            for (String chatId : new String[] {"01", "02", "10"}) {
                String name = ChatUiActorSystem.chatActorName(projectId, chatId);
                assertArrayEquals(new String[] {projectId, chatId}, ChatUiActorSystem.splitConversationSquadId(name),
                        "round trip failed for " + name);
            }
        }
    }

    @Test
    void theProjectAndTheConversationAreSeparated() {
        assertEquals("project2", ChatUiActorSystem.splitConversationSquadId("project2/chat-01")[0]);
        assertEquals("01", ChatUiActorSystem.splitConversationSquadId("project2/chat-01")[1]);
    }

    /** Names from before this scheme, and malformed ones, re-open nothing rather than half a ConversationSquad. */
    @Test
    void aNameThisSchemeNeverProducedIsRefused() {
        assertNull(ChatUiActorSystem.splitConversationSquadId(null));
        assertNull(ChatUiActorSystem.splitConversationSquadId(""));
        assertNull(ChatUiActorSystem.splitConversationSquadId("01"));               // the pre-project ConversationSquad id
        assertNull(ChatUiActorSystem.splitConversationSquadId("project2"));         // no conversation part
        assertNull(ChatUiActorSystem.splitConversationSquadId("project2/chat-"));   // empty conversation id
        assertNull(ChatUiActorSystem.splitConversationSquadId("/chat-01"));         // no project part
    }
}
