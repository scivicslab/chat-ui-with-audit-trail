package com.scivicslab.chatui.core.provider;

/**
 * What a one-shot completion returned ({@code RequestMessagesControl_261018_oo01}).
 *
 * @param reply       the model's reply text, or {@code null} when the call failed
 * @param requestBody the request body that was sent, or {@code null} when none was built
 * @param error       why the call failed or was refused, or {@code null} on success
 */
public record OneShotReply(String reply, String requestBody, String error) {

    /** @return a reply that refused before anything was sent */
    public static OneShotReply refused(String error) {
        return new OneShotReply(null, null, error);
    }
}
