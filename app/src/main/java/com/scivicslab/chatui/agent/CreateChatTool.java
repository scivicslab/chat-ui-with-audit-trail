package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.TeamRoles;
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
 * <p>With no provider named, the conversation is made on {@link #DEFAULT_PROVIDER} (Claude Code): the usual
 * team is a few Claude Code conversations, and a local LLM is named for many workers that repeat one job. An
 * instance that has no such provider refuses, naming the providers it has, rather than making a local LLM one.</p>
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

    /** The provider a conversation is made on when {@code create_chat} names none. */
    public static final String DEFAULT_PROVIDER = "claude";

    /** @return the superior's bare id, {@code null} for none, or an {@code error: ...} string */
    static String superiorIdOf(String myProjectId, String myChatId, String superior) {
        if (superior == null || superior.isBlank()) return myChatId;
        if (superior.strip().equalsIgnoreCase("none")) return null;
        String name = ChatUiActorSystem.resolveChatName(myProjectId, superior.strip());
        String prefix = myProjectId + "/chat-";
        if (!name.startsWith(prefix)) return "error: refused — the superior must be a conversation of this project";
        return name.substring(prefix.length());
    }

    private static final Logger LOG = Logger.getLogger(CreateChatTool.class.getName());

    /**
     * @param system      the actor system, to read the new conversation's model list
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param provider    the provider's value as {@code list_providers} printed it, or {@code null}/blank
     *                    for the default ({@link #DEFAULT_PROVIDER})
     * @param model       one of the models the provider offers, or {@code null}/blank for its default
     * @return {@code ok: ...} naming the new conversation, its provider and model, or an {@code error: ...} string
     */
    public static String create(IIActorSystem system, ConversationControls controls, String myProjectId,
                                String provider, String model) {
        return create(system, controls, myProjectId, null, provider, model, "none");
    }

    /**
     * As {@link #create(IIActorSystem, ConversationControls, String, String, String)}, and puts the new
     * conversation under a superior ({@code ConversationReportingLines_261108_oo01}).
     *
     * @param myChatId the calling conversation's bare id
     * @param superior the new conversation's superior: blank for the caller itself, {@code none} for no
     *                 superior, or another conversation of this project. A superior already holding the
     *                 limit is refused before anything is made.
     */
    public static String create(IIActorSystem system, ConversationControls controls, String myProjectId,
                                String myChatId, String provider, String model, String superior) {
        return create(system, controls, myProjectId, myChatId, provider, model, superior, null);
    }

    /**
     * As above, and with a role: the provider, model, name and timekeeper limits of the new conversation come from the
     * role table ({@code TeamBuiltByProjectWorkflow_261108_oo01}), so the caller cannot choose them. A role given with a
     * provider or a model is refused, and so is a role the table does not define.
     *
     * @param role {@code manager}, {@code worker} or {@code repeater}; blank for none
     */
    public static String create(IIActorSystem system, ConversationControls controls, String myProjectId,
                                String myChatId, String provider, String model, String superior, String role) {
        if (controls == null) return "error: create_chat is not available here";
        TeamRoles.Role roleSettings = null;
        String roleName = role == null ? "" : role.strip();
        if (!roleName.isEmpty()) {
            if ((provider != null && !provider.isBlank()) || (model != null && !model.isBlank())) {
                return "error: refused — a role decides the provider and the model; give the role or the provider and "
                        + "model, not both (no conversation was created)";
            }
            try {
                TeamRoles roles = TeamRoles.of(controls.rolesText(myProjectId));
                if (!TeamRoles.NAMES.contains(roleName)) {
                    return "error: unknown role '" + roleName + "'; choose one of: " + String.join(", ", TeamRoles.NAMES)
                            + " (no conversation was created)";
                }
                roleSettings = roles.role(roleName);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return "error: " + e.getMessage() + " (no conversation was created)";
            }
            provider = roleSettings.provider();
            model = roleSettings.model();
        }
        String sup = superiorIdOf(myProjectId, myChatId, superior);
        if (sup != null && sup.startsWith("error:")) return sup;
        if (sup != null) {
            String full = controls.roomFor(myProjectId, sup);
            if (full != null) return "error: refused — " + full + " (no conversation was created)";
        }
        boolean providerGiven = provider != null && !provider.isBlank();
        boolean modelGiven = model != null && !model.isBlank();
        ConversationControls.ProviderOption option = ProviderTools.find(controls, providerGiven ? provider : DEFAULT_PROVIDER);
        if (option == null) {
            if (providerGiven) return ProviderTools.unknownProvider(controls, provider);
            return "error: refused — this instance has no " + DEFAULT_PROVIDER + " provider, which create_chat uses "
                    + "when no provider is named; name one of: "
                    + controls.providerOptions().stream().map(ConversationControls.ProviderOption::value)
                            .collect(java.util.stream.Collectors.joining(", ")) + " (no conversation was created)";
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
            controls.setProvider(myProjectId, chatId, option.kind(), option.toolSet());
            if (modelGiven) {
                List<LlmProvider.ModelEntry> available = ListChatModelsTool.modelsOf(system, myProjectId, chatId);
                String refusal = ChatInspection.refusalFor(model, available);
                if (refusal != null) {
                    controls.deleteChat(myProjectId, chatId);
                    return refusal.replace("this conversation's models", "the models of " + option.value())
                            + "\n(no conversation was created)";
                }
                controls.setModel(myProjectId, chatId, model);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "create_chat: could not set up " + name, e);
            controls.deleteChat(myProjectId, chatId);
            return "error: create_chat failed: " + e.getMessage() + " (no conversation was created)";
        }
        String reportsTo = "";
        if (sup != null) {
            String placed = controls.setSuperior(myProjectId, chatId, sup);
            if (placed.startsWith("error:")) {
                controls.deleteChat(myProjectId, chatId);
                return placed + " (no conversation was created)";
            }
            reportsTo = ", reporting to chat-" + sup;
        }
        String asRole = "";
        if (roleSettings != null) {
            try {
                String named = controls.setChatName(myProjectId, chatId, roleName + " chat-" + chatId);
                if (named.startsWith("error:")) throw new IllegalStateException(named);
                controls.setTimekeeperLimits(myProjectId, chatId, roleSettings.urgentWaitMinutes(),
                        roleSettings.childReplyWarnMinutes());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "create_chat: could not give " + name + " its role", e);
                controls.deleteChat(myProjectId, chatId);
                return "error: create_chat failed: " + e.getMessage() + " (no conversation was created)";
            }
            asRole = ", as " + roleName;
        }
        return "ok: created " + name + " (address it as \"" + chatId + "\") on " + option.value()
                + ", model " + (modelGiven ? model : "the provider's default") + reportsTo + asRole;
    }
}
