package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.StalledTurnRecovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code list_stalled_chats} and {@code recover_stalled_chats} say ({@code tasks/stalled-turn-recovery.md}). */
class StalledChatsToolTest {

    @Test
    void theListing_writesEachConversationWithItsProject_andSaysWhenNoneIsStalled() {
        assertEquals("no conversation is stalled (a turn is stalled when its model call has produced nothing for 10 min)",
                StalledChatsTool.describe(List.of(), 10 * 60_000));
        String line = StalledChatsTool.describe(List.of(
                new StalledTurnRecovery.Stalled("project1", "57", "silent", 0, 43 * 60_000, 19)), 10 * 60_000);
        assertTrue(line.startsWith("project1/chat-57: its model call has produced nothing for 43 min"), line);
        assertTrue(line.endsWith("19 prompt(s) waiting in its queue"), line);
    }

    @Test
    void aQueueHeldForTheUsageLimit_isSaidAsSuch() {
        String line = StalledChatsTool.describe(List.of(
                new StalledTurnRecovery.Stalled("project1", "57", "usage-limit", 0, 3 * 60_000, 19)), 10 * 60_000);
        assertTrue(line.contains("refused for the usage limit 3 min ago; its request is back at the front and its"
                + " queue is held"), line);
    }

    @Test
    void withoutTheRecoveryActor_bothToolsSaySo() {
        assertTrue(StalledChatsTool.list(null).startsWith("error: "));
        assertTrue(StalledChatsTool.recover(null, "project1", "chat-57").startsWith("error: "));
    }

    @Test
    void theRecoverDescription_saysOneCallIsEnough_andTakesNoNode() {
        String d = ToolCatalog.builtIn("recover_stalled_chats").description().replaceAll("\\s+", " ");
        assertTrue(d.contains("One call brings a conversation back"), d);
        assertTrue(d.contains("\"project1/chat-57\""), d);
        assertTrue(d.contains("takes no node"), d);
    }
}
