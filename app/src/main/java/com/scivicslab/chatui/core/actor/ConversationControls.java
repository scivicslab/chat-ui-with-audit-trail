package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;

import java.util.List;

/**
 * What the conversation tools {@code set_chat_model}, {@code get_chat_log}, {@code create_chat},
 * {@code delete_chat}, {@code list_providers} and {@code set_chat_provider} need from the actor
 * system, and nothing else.
 *
 * <p>A conversation's tools are handed an {@code IIActorSystem}, which can reach another
 * conversation's actors but not the actor system's settings record or its log buffers.
 * {@link ChatUiActorSystem} implements this and gives it to each {@link ChatSession}, so a tool
 * gets exactly these operations rather than the whole system.</p>
 */
public interface ConversationControls {

    /**
     * Sets a conversation's model and records it with the conversation's other settings, so a
     * restart puts the conversation back on it. Delivered to the provider's own actor, so a change
     * made while a turn is running lands after that turn.
     *
     * @return {@code false} when the conversation has no provider
     */
    boolean setModel(String projectId, String chatId, String model);

    /** @return the conversation's recent log entries, oldest first, or {@code null} if it does not exist */
    List<RecentEntriesAccumulator.Entry> getChatLogEntries(String projectId, String chatId);

    /**
     * Adds a conversation to a project, the actor system allocating its id.
     *
     * @return the new conversation's id, e.g. {@code "06"}
     * @throws IllegalArgumentException when the project does not exist
     */
    String createChat(String projectId);

    /** Deletes a conversation, or refuses and changes nothing; see {@link ChatUiActorSystem#deleteChat}. */
    ChatUiActorSystem.ChatDeletion deleteChat(String projectId, String chatId);

    /**
     * One provider a conversation can be put on, with the tool set it comes with, as the screen's
     * provider dropdown offers it ({@code ProviderAndModelChosenAtCreateChat_261107_oo01}).
     *
     * @param value   what a caller names it by: the kind alone when the tool set is the kind's
     *                default ({@code claude}), else {@code kind:toolset} ({@code claude:full})
     * @param kind    the provider kind ({@code openai-compat}, {@code claude}, {@code codex})
     * @param toolSet the tool set the conversation gets with it
     * @param label   the name the screen shows for it
     */
    record ProviderOption(String value, String kind, ToolSet toolSet, String label) {}

    /** @return the providers this instance can put a conversation on, in the order the screen lists them */
    List<ProviderOption> providerOptions();

    /**
     * Puts a conversation on another provider and tool set, and records it with the conversation's
     * other settings, so a restart puts the conversation back on it. A turn already running finishes
     * on the old provider.
     *
     * @throws IllegalArgumentException for an unknown conversation or kind, or a tool set the kind cannot take
     */
    void setProvider(String projectId, String chatId, String kind, ToolSet toolSet);

    /**
     * Changes a conversation's timekeeper limits and records them with its other settings
     * ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     *
     * @param urgentWaitMinutes     how long an urgent request may wait at the front of a running turn, or
     *                              {@code null} to keep it
     * @param childReplyWarnMinutes how long a sent request may go without a reply, or {@code null} to keep it
     * @return the limits now in force, as {@code [urgentWaitMinutes, childReplyWarnMinutes]}
     * @throws IllegalArgumentException for an unknown conversation or a limit below one minute
     */
    long[] setTimekeeperLimits(String projectId, String chatId, Long urgentWaitMinutes, Long childReplyWarnMinutes);
}
