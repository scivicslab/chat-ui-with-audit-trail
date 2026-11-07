package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_chat_name} tool: gives a conversation of the caller's own project the name a person reads beside
 * its id, the same name the Project tab's field sets. A conversation that hands work out can name the ones it
 * created after what each is for. The id ({@code chat-06}) does not change, and every other tool still takes it.
 */
public final class ChatNameTool {

    private ChatNameTool() {}

    private static final Logger LOG = Logger.getLogger(ChatNameTool.class.getName());

    /** Longer than any name a person needs beside an id; a whole paragraph in the field is a mistake. */
    static final int MAX_LENGTH = 80;

    /**
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id for this project, the caller's own included (another project's is refused)
     * @param name        the name, or blank to clear it
     * @return {@code ok: ...} with the name now in force, or an {@code error: ...} string
     */
    public static String set(ConversationControls controls, String myProjectId, String target, String name) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (controls == null) return "error: set_chat_name is not available here";
        String applied = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (applied.length() > MAX_LENGTH) {
            return "error: a name is at most " + MAX_LENGTH + " characters; this one has " + applied.length();
        }
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project";
        }
        String targetChatId = targetName.substring((myProjectId + "/chat-").length());
        try {
            String outcome = controls.setChatName(myProjectId, targetChatId, applied.isEmpty() ? null : applied);
            if (outcome.startsWith("error:")) return outcome;
            return applied.isEmpty() ? "ok: " + targetName + " has no name now"
                    : "ok: " + targetName + " is now named \"" + applied + "\"";
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_chat_name: failed for " + target, e);
            return "error: set_chat_name failed: " + e.getMessage();
        }
    }
}
