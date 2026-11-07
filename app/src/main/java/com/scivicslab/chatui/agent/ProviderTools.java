package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The {@code list_providers} and {@code set_chat_provider} tools, and the lookup of a provider by the
 * value a model writes for it, which {@code create_chat} shares
 * ({@code ProviderAndModelChosenAtCreateChat_261107_oo01}).
 *
 * <p>A provider is named by the value the screen's provider dropdown uses: the kind alone when the tool
 * set is that kind's default ({@code claude}), else {@code kind:toolset} ({@code claude:full}). One value
 * names one combination this instance offers, so a combination it does not offer cannot be asked for.</p>
 */
public final class ProviderTools {

    private ProviderTools() {}

    private static final Logger LOG = Logger.getLogger(ProviderTools.class.getName());

    /**
     * The {@code list_providers} tool.
     *
     * @param controls the window onto the actor system
     * @return one provider per line as {@code value (label)}, or an {@code error: ...} string
     */
    public static String list(ConversationControls controls) {
        if (controls == null) return "error: list_providers is not available here";
        List<ConversationControls.ProviderOption> options = controls.providerOptions();
        if (options.isEmpty()) return "error: this instance has no providers";
        return options.stream().map(o -> o.value() + " (" + o.label() + ")").collect(Collectors.joining("\n"));
    }

    /**
     * Finds the provider a value names.
     *
     * @param controls the window onto the actor system
     * @param value    the value as the model wrote it, compared without case and surrounding spaces
     * @return the provider, or {@code null} when the value names none of them
     */
    static ConversationControls.ProviderOption find(ConversationControls controls, String value) {
        String v = value == null ? "" : value.strip().toLowerCase();
        for (ConversationControls.ProviderOption o : controls.providerOptions()) {
            if (o.value().equals(v)) return o;
        }
        return null;
    }

    /** @return the refusal for a value that names no provider, listing the values that do */
    static String unknownProvider(ConversationControls controls, String value) {
        return "error: unknown provider '" + value + "'; choose one of: "
                + controls.providerOptions().stream().map(ConversationControls.ProviderOption::value)
                        .collect(Collectors.joining(", "));
    }

    /**
     * The {@code set_chat_provider} tool: puts a conversation of the caller's own project on another
     * provider. Its model becomes that provider's default; {@code set_chat_model} changes it after.
     *
     * @param controls    the window onto the actor system
     * @param myProjectId the calling conversation's project id
     * @param target      a bare id for this project (a qualified id of another project is refused)
     * @param provider    the provider's value, as {@code list_providers} printed it
     * @return {@code ok: ...} on success, or an {@code error: ...} string
     */
    public static String setChatProvider(ConversationControls controls, String myProjectId,
                                         String target, String provider) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (provider == null || provider.isBlank()) return "error: provider is required";
        if (controls == null) return "error: set_chat_provider is not available here";

        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project; set_chat_provider across"
                    + " projects is not possible";
        }
        ConversationControls.ProviderOption option = find(controls, provider);
        if (option == null) return unknownProvider(controls, provider);
        String targetChatId = targetName.substring((myProjectId + "/chat-").length());
        try {
            controls.setProvider(myProjectId, targetChatId, option.kind(), option.toolSet());
            return "ok: " + targetName + " is now on " + option.value()
                    + " (takes effect after its current turn, if it is running one)";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_chat_provider: failed for " + target, e);
            return "error: set_chat_provider failed: " + e.getMessage();
        }
    }
}
