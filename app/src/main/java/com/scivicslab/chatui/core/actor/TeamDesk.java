package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;

import java.util.List;

/**
 * What {@link TeamBuilder} may do to a project's ConversationSquad actors, and nothing more
 * ({@code TeamBuiltByProjectWorkflow_261108_oo01}): make one on a provider and model, name it, put it under a
 * superior, set its timekeeper limits, say how many more subordinates a superior can take, name the liaison, delete
 * one again, and put one message in one's queue.
 *
 * <p>It is a separate, narrow interface so that the shared actor {@code conversations}, which plugin code looks up by
 * name, does not hand out the whole {@code ChatUiActorSystem}. Every conversation is named by its bare id
 * ({@code "02"}). An operation that can be refused returns the reason, or {@code null} when it was done.</p>
 */
public interface TeamDesk {

    /**
     * One provider a ConversationSquad actor can be put on.
     *
     * @param value   what a person names it by: {@code claude}, {@code openai-compat}
     * @param kind    the provider kind
     * @param toolSet the tool set it comes with
     */
    record Provider(String value, String kind, ToolSet toolSet) {}

    /** @return the providers this instance offers, in the order the screen lists them */
    List<Provider> providers();

    /** @return the text of the project's own role table, or {@code ""} when the project has none */
    String rolesText(String projectId);

    /** @return the bare id of the project's liaison */
    String liaisonOf(String projectId);

    /** @return how many subordinates one superior may have */
    int subordinateLimit(String projectId);

    /** @return how many more subordinates the ConversationSquad actor can take */
    int roomLeft(String projectId, String chatId);

    /** @return the bare id of a new ConversationSquad actor, made on the default provider */
    String createChat(String projectId);

    /** Puts the ConversationSquad actor on the provider. */
    void setProvider(String projectId, String chatId, Provider provider);

    /** @return the model names the ConversationSquad actor's provider lists; empty when it lists none */
    List<String> modelNames(String projectId, String chatId);

    /** Puts the ConversationSquad actor on the model. */
    void setModel(String projectId, String chatId, String model);

    /** @return {@code null} when named, else why not */
    String setChatName(String projectId, String chatId, String name);

    /** @return {@code null} when done, else why not */
    String setSuperior(String projectId, String chatId, String superiorChatId);

    /** Sets the timekeeper limits, in minutes. */
    void setTimekeeperLimits(String projectId, String chatId, long urgentWaitMinutes, long childReplyWarnMinutes);

    /** @return whether the ConversationSquad actor was deleted */
    boolean deleteChat(String projectId, String chatId);

    /**
     * Puts one message in the ConversationSquad actor's queue.
     *
     * @param source where the message comes from, e.g. {@code job:project1/job-02}
     * @return {@code null} when queued, else why not
     */
    String notifyConversation(String projectId, String chatId, String source, String text);

    /** @return a desk for an instance where no {@code conversations} actor is registered: every operation says so */
    static TeamDesk absent() {
        return new TeamDesk() {
            private IllegalStateException none() {
                return new IllegalStateException("no 'conversations' actor is registered, so a team cannot be built here");
            }
            @Override public List<Provider> providers() { throw none(); }
            @Override public String rolesText(String projectId) { throw none(); }
            @Override public String liaisonOf(String projectId) { throw none(); }
            @Override public int subordinateLimit(String projectId) { throw none(); }
            @Override public int roomLeft(String projectId, String chatId) { throw none(); }
            @Override public String createChat(String projectId) { throw none(); }
            @Override public void setProvider(String projectId, String chatId, Provider provider) { throw none(); }
            @Override public List<String> modelNames(String projectId, String chatId) { throw none(); }
            @Override public void setModel(String projectId, String chatId, String model) { throw none(); }
            @Override public String setChatName(String projectId, String chatId, String name) { throw none(); }
            @Override public String setSuperior(String projectId, String chatId, String superiorChatId) { throw none(); }
            @Override public void setTimekeeperLimits(String projectId, String chatId, long u, long w) { throw none(); }
            @Override public boolean deleteChat(String projectId, String chatId) { throw none(); }
            @Override public String notifyConversation(String projectId, String chatId, String source, String text) { throw none(); }
        };
    }
}
