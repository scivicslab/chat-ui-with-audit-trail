package com.scivicslab.chatui.core.actor;

/**
 * Anchor POJO for one conversation's actor subtree.
 *
 * <p>Holds no state. It exists only so that {@code ActorRef<ConversationTab>.createChild(...)}
 * can register the conversation's {@link ChatSession}, {@link PromptQueue} and
 * {@link SseConnection}, plus its log multiplexer, as tracked children. References to those
 * children are never stored here; callers resolve them through
 * {@code ActorRef<ConversationTab>.getNamesOfChildren()} instead.</p>
 *
 * <p>A conversation may itself be the parent of another conversation, so that the tree shows
 * which conversation is working for which ({@code NestedConversationTree_260830_oo01}).</p>
 */
public class ConversationTab {
}
