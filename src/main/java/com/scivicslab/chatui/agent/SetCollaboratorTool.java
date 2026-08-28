package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CollaborationGraph;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_collaborator} tool: assigns another conversation tab as {@code chatId}'s
 * collaborator for a given role (e.g. {@code "worker"}), via the shared
 * {@link CollaborationGraph} ({@code CollaborationGraph_260828_oo01}).
 */
public final class SetCollaboratorTool {

    private SetCollaboratorTool() {}

    private static final Logger LOG = Logger.getLogger(SetCollaboratorTool.class.getName());
    private static final int TIMEOUT_SECONDS = 5;

    /**
     * @param graph              the shared {@link CollaborationGraph}
     * @param chatId             the tab whose role table to update
     * @param role               a free-form role name (e.g. {@code "worker"})
     * @param collaboratorChatId the tab to assign to that role
     * @return {@code "ok: ..."} on success, or an {@code error: ...} string
     */
    public static String setCollaborator(ActorRef<CollaborationGraph> graph,
                                          String chatId, String role, String collaboratorChatId) {
        if (chatId == null || chatId.isBlank()) return "error: chatId is required";
        if (role == null || role.isBlank()) return "error: role is required";
        if (collaboratorChatId == null || collaboratorChatId.isBlank()) {
            return "error: collaboratorChatId is required";
        }
        try {
            graph.ask(g -> {
                g.setCollaborator(chatId, role, collaboratorChatId);
                return "ok";
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return "ok: chat " + chatId + "'s '" + role + "' collaborator set to chat " + collaboratorChatId;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_collaborator: failed", e);
            return "error: set_collaborator failed: " + e.getMessage();
        }
    }
}
