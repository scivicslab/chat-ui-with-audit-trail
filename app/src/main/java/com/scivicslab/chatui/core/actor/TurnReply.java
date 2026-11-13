package com.scivicslab.chatui.core.actor;

/**
 * What one turn of a conversation left for whoever asked for it: the answer, the line saying how
 * the turn ended, and the id of the turn that wrote it. A {@link ChatSession} completes a prompt's
 * {@code done} future with this when the turn ends, so the asker reads the answer from the future
 * itself instead of asking the conversation for it afterwards — which would wait behind the
 * conversation's next turn ({@code AskChatWithoutWaiting_261022_oo01}).
 *
 * @param reply  the answer, or {@code null} when the turn left none (a prompt without a result key,
 *               a cancelled or failed turn, a prompt refused because the conversation was busy)
 * @param ending the line saying how the turn ended ({@code [ended: answered; ...]}); when there is no
 *               answer, why ({@code its turn failed: <error>}); {@code null} when there is nothing to say
 * @param turnId the id of the turn that wrote the answer ({@code TurnAndRequestIds_261107_oo01}), or
 *               {@code null} when there is no turn to name
 */
public record TurnReply(String reply, String ending, String turnId) {

    /** A turn that left nothing for its asker. */
    public static final TurnReply NONE = new TurnReply(null, null, null);

    /**
     * The sender took the request back while it waited in the queue ({@code cancel_request}). It is compared
     * by identity: the sender already knows, so no message goes back to it
     * ({@code CancelARequestInTheQueue_261113_oo01}).
     */
    public static final TurnReply WITHDRAWN = new TurnReply(null, "withdrawn by its sender", null);
}
