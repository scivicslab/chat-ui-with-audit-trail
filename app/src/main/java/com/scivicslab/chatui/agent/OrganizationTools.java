package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ConversationControls;

/**
 * The tools that read and change a project's organization ({@code ConversationReportingLines_261108_oo01}):
 * {@code list_chats}, the project's conversations with their names and reporting lines, and
 * {@code set_superior}, which puts a conversation under another or takes it out from under one. Only
 * the caller's own project, as the other conversation tools.
 */
public final class OrganizationTools {

    private OrganizationTools() {}

    /** @return one line per conversation of the caller's project, then the subordinate limit */
    public static String list(ConversationControls controls, String myProjectId, String myChatId) {
        if (controls == null) return "error: list_chats is not available here";
        return controls.listChats(myProjectId, myChatId);
    }

    /**
     * @param target   the conversation to place, as a bare id of this project
     * @param superior its new superior as a bare id, or blank / {@code none} for no superior
     * @return {@code ok: ...} or {@code error: ...} with the reason and nothing changed
     */
    public static String setSuperior(ConversationControls controls, String myProjectId, String myChatId,
                                     String target, String superior) {
        if (controls == null) return "error: set_superior is not available here";
        if (target == null || target.isBlank()) return "error: chatId is required";
        String chatId = CreateChatTool.superiorIdOf(myProjectId, myChatId, target);
        if (chatId == null || chatId.startsWith("error:")) {
            return "error: refused — the conversation must be one of this project";
        }
        String sup = superior == null || superior.isBlank() ? null : CreateChatTool.superiorIdOf(myProjectId, myChatId, superior);
        if (sup != null && sup.startsWith("error:")) return sup;
        return controls.setSuperior(myProjectId, chatId, sup);
    }
}
