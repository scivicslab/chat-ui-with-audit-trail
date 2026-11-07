package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code create_chat} tool: adds a conversation to the calling conversation's project, on the
 * provider and model the caller names, so a supervising conversation can put its workers on a local
 * LLM and another supervisor on Claude Code ({@code ProviderAndModelChosenAtCreateChat_261107_oo01}).
 *
 * <p>The id is allocated by the actor system, as {@code POST /api/projects/{p}/chats} does, so two
 * callers never get the same one. The conversation is made in the caller's own project only, as
 * {@code set_chat_model} changes only that project's conversations.</p>
 *
 * <p>A conversation is left behind only when the whole call succeeds. A provider value that names none
 * of this instance's providers is refused before anything is made. A model can only be checked against
 * the list the new provider offers, so it is checked after the conversation is made and put on that
 * provider, and a model not in the list deletes the conversation again before the refusal is returned.</p>
 */
public final class CreateChatTool {

    private CreateChatTool() {}

    private static final Logger LOG = Logger.getLogger(CreateChatTool.class.getName());

    /**
     * @param system      the actor system, to read the new conversation's model list
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param provider    the provider's value as {@code list_providers} printed it, or {@code null}/blank
     *                    for the default ({@code openai-compat} with every tool)
     * @param model       one of the models the provider offers, or {@code null}/blank for its default
     * @return {@code ok: ...} naming the new conversation, its provider and model, or an {@code error: ...} string
     */
    public static String create(IIActorSystem system, ConversationControls controls, String myProjectId,
                                String provider, String model) {
        if (controls == null) return "error: create_chat is not available here";
        boolean providerGiven = provider != null && !provider.isBlank();
        boolean modelGiven = model != null && !model.isBlank();
        ConversationControls.ProviderOption option = null;
        if (providerGiven) {
            option = ProviderTools.find(controls, provider);
            if (option == null) return ProviderTools.unknownProvider(controls, provider);
        }
        String chatId;
        try {
            chatId = controls.createChat(myProjectId);
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "create_chat: failed in " + myProjectId, e);
            return "error: create_chat failed: " + e.getMessage();
        }
        String name = ChatUiActorSystem.chatActorName(myProjectId, chatId);
        try {
            if (option != null) controls.setProvider(myProjectId, chatId, option.kind(), option.toolSet());
            if (modelGiven) {
                List<LlmProvider.ModelEntry> available = ListChatModelsTool.modelsOf(system, myProjectId, chatId);
                String refusal = ChatInspection.refusalFor(model, available);
                if (refusal != null) {
                    controls.deleteChat(myProjectId, chatId);
                    return refusal.replace("this conversation's models", "the models of "
                            + (option == null ? "the default provider" : option.value()))
                            + "\n(no conversation was created)";
                }
                controls.setModel(myProjectId, chatId, model);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "create_chat: could not set up " + name, e);
            controls.deleteChat(myProjectId, chatId);
            return "error: create_chat failed: " + e.getMessage() + " (no conversation was created)";
        }
        return "ok: created " + name + " (address it as \"" + chatId + "\") on "
                + (option == null ? "the default provider" : option.value())
                + ", model " + (modelGiven ? model : "the provider's default");
    }
}
