package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code list_chat_models} tool: lists the models another conversation's provider offers, so
 * {@code set_chat_model} can be given a name that exists.
 */
public final class ListChatModelsTool {

    private ListChatModelsTool() {}

    private static final Logger LOG = Logger.getLogger(ListChatModelsTool.class.getName());
    private static final int TIMEOUT_SECONDS = 10;

    /**
     * @param system      this conversation's actor system
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id for this project, or {@code "project2/02"}
     * @return one line per model, or an {@code error: ...} string
     */
    public static String list(IIActorSystem system, String myProjectId, String target) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        List<LlmProvider.ModelEntry> models;
        try {
            models = modelsOf(system, myProjectId, target);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "list_chat_models: failed for " + target, e);
            return "error: list_chat_models failed: " + e.getMessage();
        }
        return ChatInspection.formatModels(models);
    }

    /**
     * @return the models the target's provider offers
     * @throws IllegalArgumentException with an {@code error: ...} message when the chat does not exist
     */
    static List<LlmProvider.ModelEntry> modelsOf(IIActorSystem system, String myProjectId, String target)
            throws Exception {
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        IIActorRef<?> iiar = system.getIIActor(targetName + ".chat");
        if (!(iiar instanceof ChatSessionIIAR chat)) {
            throw new IllegalArgumentException("error: chat not found: " + target);
        }
        return chat.<List<LlmProvider.ModelEntry>>ask(interp -> ((ChatSession) interp).getAvailableModels())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
