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
 * <p>Holds no state, and does nothing. It exists so that
 * {@code ActorRef<ConversationSquad>.createChild(...)} can register those actors as tracked
 * children, and so that the conversation has one name to be addressed by. References to the
 * children are never stored here; callers resolve them through
 * {@code ActorRef<ConversationSquad>.getNamesOfChildren()} instead.</p>
 *
 * <p>A squad may itself be the parent of another squad, so that the tree shows which conversation
 * is working for which ({@code NestedConversationTree_260830_oo01}).</p>
 */
public class ConversationSquad {
}
