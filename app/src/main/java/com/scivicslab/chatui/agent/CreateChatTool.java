package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code create_chat} tool: adds a conversation to the calling conversation's project, so an
 * experiment can have a conversation of its own to talk to without a side effect of some other
 * request creating it.
 *
 * <p>The id is allocated by the actor system, as {@code POST /api/projects/{p}/chats} does, so two
 * callers never get the same one. The conversation is made in the caller's own project only, as
 * {@code set_chat_model} changes only that project's conversations.</p>
 */
public final class CreateChatTool {

    private CreateChatTool() {}

    private static final Logger LOG = Logger.getLogger(CreateChatTool.class.getName());

    /**
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @return {@code ok: ...} naming the new conversation, or an {@code error: ...} string
     */
    public static String create(ConversationControls controls, String myProjectId) {
        if (controls == null) return "error: create_chat is not available here";
        try {
            String chatId = controls.createChat(myProjectId);
            return "ok: created " + ChatUiActorSystem.chatActorName(myProjectId, chatId)
                    + " (address it as \"" + chatId + "\")";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "create_chat: failed in " + myProjectId, e);
            return "error: create_chat failed: " + e.getMessage();
        }
    }
}
