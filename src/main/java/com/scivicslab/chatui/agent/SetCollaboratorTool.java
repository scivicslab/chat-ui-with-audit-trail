package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
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
     * @param myChatName         the calling conversation's qualified name, e.g. {@code "project1/chat-01"}
     * @param target             the conversation whose role table to update — bare id for this
     *                           project, or {@code "project2/02"} to cross into another
     * @param role               a free-form role name (e.g. {@code "worker"})
     * @param collaboratorChatId the conversation to assign to that role, in the same two forms
     * @return {@code "ok: ..."} on success, or an {@code error: ...} string
     */
    public static String setCollaborator(ActorRef<CollaborationGraph> graph, String myChatName,
                                          String target, String role, String collaboratorChatId) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (role == null || role.isBlank()) return "error: role is required";
        if (collaboratorChatId == null || collaboratorChatId.isBlank()) {
            return "error: collaboratorChatId is required";
        }
        String myProjectId = myChatName.substring(0, myChatName.indexOf('/'));
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            // Same reasoning as set_workflow: this rewrites who the target will hand work to.
            return "error: refused — " + target + " belongs to another project; set_collaborator"
                    + " across projects must go through that project's gateway (not implemented yet)";
        }
        String collaboratorName = ChatUiActorSystem.resolveChatName(myProjectId, collaboratorChatId);
        try {
            graph.ask(g -> {
                g.setCollaborator(targetName, role, collaboratorName);
                return "ok";
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return "ok: " + targetName + "'s '" + role + "' collaborator set to " + collaboratorName;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_collaborator: failed", e);
            return "error: set_collaborator failed: " + e.getMessage();
        }
    }
}
