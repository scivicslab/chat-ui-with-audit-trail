package com.scivicslab.chatui.core.actor;

/**
 * Anchor POJO for one conversation tab's actor subtree.
 *
 * <p>Holds no state. It exists only so that {@code ActorRef<ConversationTab>.createChild(...)}
 * can register the tab's {@link ChatActor}, {@link SseActor}, {@link QueueActor},
 * {@link BtwActor}, and {@link McpClientActor} as tracked children. References to those
 * children are never stored here; callers resolve them through
 * {@code ActorRef<ConversationTab>.getNamesOfChildren()} instead.</p>
 */
public class ConversationTab {
}
