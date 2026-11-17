package com.scivicslab.chatui.core.actor;

/**
 * The set of actors one conversation needs, held as one node of the actor tree.
 *
 * <p>Named for what it is rather than for what it once was: the class used to be called
 * {@code ConversationTab} because one conversation was one tab of the UI. It has not corresponded
 * to a tab of the UI for a long time. A squad is the smallest group of actors that can carry a conversation,
 * each member with a role of its own: {@link ChatSession} runs the agent loop, {@link PromptQueue}
 * holds the prompts waiting their turn, {@link SseConnection} pushes events to the browser, and a
 * log multiplexer collects the conversation's log entries.</p>
 *
 * <p>Holds only the name a person gave the conversation, which it records itself, and does nothing else. It exists so that
 * {@code ActorRef<ConversationSquad>.createChild(...)} can register those actors as tracked
 * children, and so that the conversation has one name to be addressed by. References to the
 * children are never stored here; callers resolve them through
 * {@code ActorRef<ConversationSquad>.getNamesOfChildren()} instead.</p>
 *
 * <p>A squad may itself be the parent of another squad, so that the tree shows which conversation
 * is working for which ({@code NestedConversationTree_260830_oo01}).</p>
 */
public class ConversationSquad {

    private String name;

    /**
     * Sets the name a person reads, or clears it when {@code newName} is null or blank.
     *
     * <p>Separate from the actor's own name ({@code project1/chat-02}), which stays as it is: the
     * REST paths, ask_chat and the I/O log sessions are keyed by it, while this one is meant to
     * change whenever the work does, as a project's name is ({@link Project#setName}).</p>
     */
    public void setName(String newName) {
        String before = name;
        name = cleaned(newName);
        if (entries != null && !java.util.Objects.equals(before, name)) {
            entries.write(com.scivicslab.chatui.core.iolog.IoLogView.NAME_LABEL, name == null ? "" : name);
        }
    }

    private static String cleaned(String newName) {
        return newName == null || newName.isBlank() ? null : newName.strip();
    }

    /** The conversation's own session of the I/O log; {@code null} records nothing. */
    private com.scivicslab.chatui.core.iolog.SessionEntries entries;

    /**
     * Records the name, from now on, to the conversation's own session, as its {@code name} entry
     * ({@code EachActorRecordsItsOwnState_261118_oo01}).
     */
    public void recordsTo(com.scivicslab.chatui.core.iolog.SessionEntries entries) {
        this.entries = entries;
    }

    /** Puts back the name last recorded, and writes nothing; an empty entry is a name that was cleared. */
    public void restore() {
        if (entries == null) return;
        String recorded = entries.latest(com.scivicslab.chatui.core.iolog.IoLogView.NAME_LABEL);
        if (recorded != null) name = cleaned(recorded);
    }

    /** @return the name a person reads, or {@code null} when none has been set */
    public String getName() {
        return name;
    }
}
