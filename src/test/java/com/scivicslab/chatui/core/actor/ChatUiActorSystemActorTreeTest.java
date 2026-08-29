package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@link ChatUiActorSystem#getActorTree()}.
 *
 * <p>Exercises the load-bearing path: after {@code init()} builds the {@code IIActorSystem} and
 * seeds one project with one {@link ConversationTab} child, {@code getActorTree()} must return a
 * ROOT node whose only project child, in turn, has that tab as a child
 * ({@code ProjectScopedActorTree_260829_oo01} — "1 top actor = 1 project"). No CDI container and
 * no external services are involved.</p>
 */
class ChatUiActorSystemActorTreeTest {

    @Test
    void getActorTree_afterInit_defaultProjectHasOnlyChatOne() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        ActorNode root = system.getActorTree();

        assertEquals("ROOT", root.name());
        assertTrue(root.alive());
        List<String> rootChildNames = root.children().stream().map(ActorNode::name).toList();
        assertTrue(rootChildNames.contains("project1"), "expected project1 under ROOT, got " + rootChildNames);
        // Global singletons, not per-project: a wait chain / role assignment may span projects.
        assertTrue(rootChildNames.contains("callWatchdog"), "expected callWatchdog under ROOT, got " + rootChildNames);
        assertTrue(rootChildNames.contains("collaborationGraph"), "expected collaborationGraph under ROOT, got " + rootChildNames);

        ActorNode project1 = root.children().stream()
                .filter(n -> "project1".equals(n.name())).findFirst().orElseThrow();
        List<String> project1ChildNames = project1.children().stream().map(ActorNode::name).toList();
        assertTrue(project1ChildNames.contains("chat-01"), "expected chat-01 under project1, got " + project1ChildNames);
        // chat-02 no longer pre-seeded — only created lazily once actual work needs it.
        assertTrue(!project1ChildNames.contains("chat-02"), "chat-02 should not be pre-seeded, got " + project1ChildNames);
    }

    @Test
    void createProject_addsSecondProjectWithItsOwnFirstTab() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        String newChatId = system.createProject();
        assertEquals("project2-01", newChatId);

        ActorNode root = system.getActorTree();
        List<String> rootChildNames = root.children().stream().map(ActorNode::name).toList();
        assertTrue(rootChildNames.contains("project2"), "expected project2 under ROOT, got " + rootChildNames);

        ActorNode project2 = root.children().stream()
                .filter(n -> "project2".equals(n.name())).findFirst().orElseThrow();
        List<String> project2ChildNames = project2.children().stream().map(ActorNode::name).toList();
        assertTrue(project2ChildNames.contains("chat-project2-01"),
                "expected chat-project2-01 under project2, got " + project2ChildNames);
    }
}
