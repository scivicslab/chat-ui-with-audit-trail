package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.scivicslab.chatui.core.actor.TurnReply;
import org.junit.jupiter.api.Test;

/** What the asker reads when the turn it asked for left no answer. */
class AskChatReplyMessageTest {

    @Test
    void failedTurn_saysWhy() {
        String m = AskChatTool.replyMessage("project1/chat-27", "project1/chat-01:s1:t1:r1",
                new TurnReply(null, "its turn failed: Server error (http://x): restart both",
                        "project1/chat-27:s2:t1"),
                null);
        assertEquals("[request project1/chat-01:s1:t1:r1 to project1/chat-27 ended without a reply: "
                + "its turn failed: Server error (http://x): restart both]", m);
    }

    @Test
    void turnWithNothingToSay_keepsTheGeneralLine() {
        String m = AskChatTool.replyMessage("project1/chat-27", "project1/chat-01:s1:t1:r1",
                TurnReply.NONE, null);
        assertEquals("[request project1/chat-01:s1:t1:r1 to project1/chat-27 ended without a reply: "
                + "its turn left no result]", m);
    }
}
