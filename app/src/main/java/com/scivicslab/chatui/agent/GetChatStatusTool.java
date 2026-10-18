package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code get_chat_status} tool: reports which model, provider and tool set another
 * conversation is on, and whether it is busy. The same values {@code GET .../status} gives the
 * browser, read without waiting behind the conversation's turn.
 */
public final class GetChatStatusTool {

    private GetChatStatusTool() {}

    /**
     * @param system      this conversation's actor system, used to resolve the target's actor
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id ({@code "02"}) for this project, or {@code "project2/02"}
     * @return {@code key: value} lines, or an {@code error: ...} string
     */
    public static String get(IIActorSystem system, String myProjectId, String target) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        IIActorRef<?> iiar = system.getIIActor(targetName + ".chat");
        if (!(iiar instanceof ChatSessionIIAR chat)) return "error: chat not found: " + target;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("chat", targetName);
        status.put("busy", chat.isBusyDirect());
        String model = chat.getModelDirect();
        status.put("model", model == null ? "" : model);
        status.put("provider", chat.getProviderIdDirect());
        status.put("tools", chat.getToolSetDirect());
        status.put("noThink", chat.isNoThinkDirect());
        String effort = chat.getEffortDirect();
        if (effort != null) status.put("effort", effort);
        return ChatInspection.formatStatus(status);
    }
}
