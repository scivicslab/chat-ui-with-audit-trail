package com.scivicslab.chatui.core.actor;

/**
 * What one turn of a conversation left for whoever asked for it: the answer, and the line saying how
 * the turn ended. A {@link ChatSession} completes a prompt's {@code done} future with this when the
 * turn ends, so the asker reads the answer from the future itself instead of asking the conversation
 * for it afterwards — which would wait behind the conversation's next turn
 * ({@code AskChatWithoutWaiting_261022_oo01}).
 *
 * @param reply  the answer, or {@code null} when the turn left none (a prompt without a result key,
 *               a cancelled or failed turn, a prompt refused because the conversation was busy)
 * @param ending the line saying how the turn ended ({@code [ended: answered; ...]}), or {@code null}
 *               when there is no turn to describe
 */
public record TurnReply(String reply, String ending) {

    /** A turn that left nothing for its asker. */
    public static final TurnReply NONE = new TurnReply(null, null);
}
