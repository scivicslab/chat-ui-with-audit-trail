package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_chat_model} tool: puts another conversation on a different model, so one
 * conversation can choose which local model does a job it hands to another.
 *
 * <p>Refuses a conversation of another project, as {@code set_workflow} and {@code set_collaborator}
 * do: it changes how that conversation runs. Refuses a name its provider does not list, with the list
 * in the answer. The change lands after the conversation's current turn, because it is delivered to
 * the provider's own actor.</p>
 */
public final class SetChatModelTool {

    private SetChatModelTool() {}

    private static final Logger LOG = Logger.getLogger(SetChatModelTool.class.getName());

    /**
     * @param system      this conversation's actor system
     * @param controls    the settings window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id for this project (a qualified id of another project is refused)
     * @param model       the model name, as {@code list_chat_models} printed it
     * @return {@code ok: ...} on success, or an {@code error: ...} string
     */
    public static String set(IIActorSystem system, ConversationControls controls, String myProjectId,
                             String target, String model) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (model == null || model.isBlank()) return "error: model is required";
        if (controls == null) return "error: set_chat_model is not available here";

        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project; set_chat_model across"
                    + " projects must go through that project's gateway (not implemented yet)";
        }
        String targetChatId = targetName.substring((myProjectId + "/chat-").length());
        try {
            List<LlmProvider.ModelEntry> available = ListChatModelsTool.modelsOf(system, myProjectId, target);
            String refusal = ChatInspection.refusalFor(model, available);
            if (refusal != null) return refusal;
            if (!controls.setModel(myProjectId, targetChatId, model)) {
                return "error: chat " + target + " has no provider";
            }
            return "ok: " + targetName + " is set to " + model
                    + " (takes effect after its current turn, if it is running one)";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_chat_model: failed for " + target, e);
            return "error: set_chat_model failed: " + e.getMessage();
        }
    }
}
