package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;

import java.util.List;

/**
 * The {@code get_chat_log} tool: reads the newest entries of another conversation's log without
 * sending it anything, so a caller can see what a conversation is doing while an {@code ask_chat}
 * to it is still waiting, or why a call failed.
 */
public final class GetChatLogTool {

    private GetChatLogTool() {}

    /**
     * @param controls    the window onto the actor system's log buffers
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id for this project, or {@code "project2/02"}
     * @param count       how many of the newest entries; {@code null} for the default
     * @return the entries, oldest first, one per line, or an {@code error: ...} string
     */
    public static String get(ConversationControls controls, String myProjectId, String target, Integer count) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (controls == null) return "error: get_chat_log is not available here";
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        String projectId = targetName.substring(0, targetName.indexOf('/'));
        String chatId = targetName.substring(targetName.indexOf("/chat-") + "/chat-".length());
        List<RecentEntriesAccumulator.Entry> entries = controls.getChatLogEntries(projectId, chatId);
        if (entries == null) return "error: chat not found: " + target;
        return ChatInspection.formatLog(entries, count);
    }
}
