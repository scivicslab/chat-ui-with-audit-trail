package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code delete_chat} tool: deletes a conversation of the calling conversation's project, with
 * the same refusals as {@code DELETE /api/projects/{p}/chats/{c}} ({@code ConversationDeletion_261011_oo01}):
 * {@code project1}'s {@code chat-01}, a project's last conversation, one that is answering or holds
 * queued prompts, and one with conversations under it. The reason is in the answer and nothing is
 * changed. A conversation cannot delete itself, because its own turn is still running.
 */
public final class DeleteChatTool {

    private DeleteChatTool() {}

    private static final Logger LOG = Logger.getLogger(DeleteChatTool.class.getName());

    /**
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param myChatName  the calling conversation's qualified name, e.g. {@code project1/chat-01}
     * @param target      a bare id for this project (a qualified id of another project is refused)
     * @return {@code ok: ...} when deleted, or an {@code error: ...} string with the reason
     */
    public static String delete(ConversationControls controls, String myProjectId, String myChatName,
                                String target) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (controls == null) return "error: delete_chat is not available here";

        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project; delete_chat across"
                    + " projects is not allowed";
        }
        if (targetName.equals(myChatName)) {
            return "error: refused — a conversation cannot delete itself while it is answering";
        }
        String targetChatId = targetName.substring((myProjectId + "/chat-").length());
        try {
            ChatUiActorSystem.ChatDeletion outcome = controls.deleteChat(myProjectId, targetChatId);
            if (outcome.kind() == ChatUiActorSystem.ChatDeletion.Kind.DELETED) {
                return "ok: deleted " + targetName;
            }
            return "error: " + outcome.reason();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "delete_chat: failed for " + target, e);
            return "error: delete_chat failed: " + e.getMessage();
        }
    }
}
