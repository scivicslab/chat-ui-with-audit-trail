package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@link ChatUiActorSystem#getActorTree()}.
 *
 * <p>Exercises the load-bearing path: after {@code init()} builds the {@code IIActorSystem} and
 * seeds two {@link ConversationTab} children, {@code getActorTree()} must return a ROOT node whose
 * children include the seeded tabs. No CDI container and no external services are involved.</p>
 */
class ChatUiActorSystemActorTreeTest {

    @Test
    void getActorTree_afterInit_rootHasSeededTabChildren() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        ActorNode root = system.getActorTree();

        assertEquals("ROOT", root.name());
        assertTrue(root.alive());
        List<String> childNames = root.children().stream().map(ActorNode::name).toList();
        assertTrue(childNames.contains("chat-01"), "expected chat-01 under ROOT, got " + childNames);
        assertTrue(childNames.contains("chat-02"), "expected chat-02 under ROOT, got " + childNames);
    }
}
