package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.provider.OneShotReply;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.concurrent.TimeUnit;

/**
 * The tools {@code get_request_messages}, {@code set_request_messages} and
 * {@code complete_messages}: the three operations on another conversation's {@code messages}
 * array that {@code .../request-messages} and {@code .../complete-messages} give a person over
 * REST ({@code RequestMessagesControl_261018_oo01}).
 */
public final class RequestMessagesTools {

    private RequestMessagesTools() {}

    private static final String BUSY =
            "error: busy: the conversation is answering; try again when it has finished";

    /** @return the array the target's next request is built from, or an {@code error: ...} string */
    public static String get(IIActorSystem system, String myProjectId, String target) {
        ChatSessionIIAR chat = resolve(system, myProjectId, target);
        if (chat == null) return notFound(target);
        if (chat.isBusyDirect()) return BUSY;
        try {
            String json = chat.ask(a -> ((ChatSession) a).requestMessagesJson()).get(10, TimeUnit.SECONDS);
            return json == null ? "error: this conversation's provider keeps no request history" : json;
        } catch (Exception e) {
            return "error: get_request_messages failed: " + e.getMessage();
        }
    }

    /**
     * Replaces the whole array. Refused for a conversation of another project, as
     * {@code set_chat_model} is.
     *
     * @return {@code ok: ...} or an {@code error: ...} string
     */
    public static String set(IIActorSystem system, String myProjectId, String target, String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank()) return "error: messages is required";
        if (!ChatUiActorSystem.resolveChatName(myProjectId, target == null ? "" : target)
                .startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project";
        }
        ChatSessionIIAR chat = resolve(system, myProjectId, target);
        if (chat == null) return notFound(target);
        if (chat.isBusyDirect()) return BUSY;
        try {
            String refused = chat.ask(a -> ((ChatSession) a).replaceRequestMessages(messagesJson))
                    .get(10, TimeUnit.SECONDS);
            return refused == null ? "ok: the request history of " + target + " was replaced" : "error: " + refused;
        } catch (Exception e) {
            return "error: set_request_messages failed: " + e.getMessage();
        }
    }

    /**
     * Calls the target's model once with exactly the given messages; nothing in the target changes.
     *
     * @param model   a model name, or blank for the target's own
     * @param noThink {@code "true"} or {@code "false"}, or blank for the default
     * @return the reply text, or an {@code error: ...} string
     */
    public static String complete(IIActorSystem system, String myProjectId, String target,
                                  String messagesJson, String model, String noThink) {
        if (messagesJson == null || messagesJson.isBlank()) return "error: messages is required";
        ChatSessionIIAR chat = resolve(system, myProjectId, target);
        if (chat == null) return notFound(target);
        OneShotReply reply = chat.completeOneShotDirect(messagesJson,
                model == null || model.isBlank() ? null : model,
                noThink == null || noThink.isBlank() ? null : Boolean.valueOf(noThink.trim()));
        return reply.error() == null ? (reply.reply() == null ? "" : reply.reply()) : "error: " + reply.error();
    }

    private static ChatSessionIIAR resolve(IIActorSystem system, String myProjectId, String target) {
        if (target == null || target.isBlank()) return null;
        IIActorRef<?> iiar = system.getIIActor(ChatUiActorSystem.resolveChatName(myProjectId, target) + ".chat");
        return iiar instanceof ChatSessionIIAR chat ? chat : null;
    }

    private static String notFound(String target) {
        return target == null || target.isBlank() ? "error: chatId is required" : "error: chat not found: " + target;
    }
}
