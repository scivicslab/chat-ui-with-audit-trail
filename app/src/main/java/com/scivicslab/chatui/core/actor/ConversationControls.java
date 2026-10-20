package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.logging.RecentEntriesAccumulator;

import java.util.List;

/**
 * The four things the conversation tools {@code set_chat_model}, {@code get_chat_log},
 * {@code create_chat} and {@code delete_chat} need from the actor system, and nothing else.
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
}
