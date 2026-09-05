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
        assertTrue(rootChildNames.contains(ChatUiActorSystem.HOUSEKEEPER),
                "expected the housekeeper branch under ROOT, got " + rootChildNames);

        // The always-there singletons hang off the housekeeper, not off ROOT, so ROOT's children
        // show the work (NestedConversationTree_260830_oo01).
        ActorNode housekeeper = root.children().stream()
                .filter(n -> ChatUiActorSystem.HOUSEKEEPER.equals(n.name())).findFirst().orElseThrow();
        List<String> keptNames = housekeeper.children().stream().map(ActorNode::name).toList();
        assertTrue(keptNames.contains("callWatchdog"), "expected callWatchdog under housekeeper, got " + keptNames);
        assertTrue(keptNames.contains("collaborationGraph"), "expected collaborationGraph under housekeeper, got " + keptNames);
        assertTrue(keptNames.contains("skillRegistry"), "expected skillRegistry under housekeeper, got " + keptNames);

        ActorNode project1 = root.children().stream()
                .filter(n -> "project1".equals(n.name())).findFirst().orElseThrow();
        List<String> project1ChildNames = project1.children().stream().map(ActorNode::name).toList();
        assertTrue(project1ChildNames.contains("project1/chat-01"),
                "expected project1/chat-01 under project1, got " + project1ChildNames);
        // chat-02 no longer pre-seeded — only created lazily once actual work needs it.
        assertTrue(!project1ChildNames.contains("project1/chat-02"),
                "chat-02 should not be pre-seeded, got " + project1ChildNames);
    }

    /**
     * The provider must be findable by project and conversation id.
     *
     * <p>It was not: the lookup asked for {@code project1/chat-01.provider} while the provider is
     * registered under {@code project1/chat-01.chat.provider}, one step further down, because it is
     * a child of the ChatSession rather than of the conversation. Nothing failed loudly — the
     * caller got {@code null} and the activity summary reported that it had no conversations.</p>
     */
    @Test
    void getProviderRef_findsTheProviderOfASeededConversation() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        assertTrue(system.getProviderRef("project1", "01") != null,
                "project1/chat-01 has a provider and it must be reachable by ids");
        assertEquals("project1/chat-01.chat.provider",
                system.getProviderRef("project1", "01").getName());
    }

    @Test
    void createChat_under_anotherConversation_placesItInsideThatConversation() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        system.createChat("project1", "02", "01");

        ActorNode root = system.getActorTree();
        ActorNode project1 = root.children().stream()
                .filter(n -> "project1".equals(n.name())).findFirst().orElseThrow();
        List<String> project1ChildNames = project1.children().stream().map(ActorNode::name).toList();
        assertTrue(!project1ChildNames.contains("project1/chat-02"),
                "chat-02 was created under chat-01, so it is not a child of the project: " + project1ChildNames);

        ActorNode chat01 = project1.children().stream()
                .filter(n -> "project1/chat-01".equals(n.name())).findFirst().orElseThrow();
        List<String> chat01ChildNames = chat01.children().stream().map(ActorNode::name).toList();
        assertTrue(chat01ChildNames.contains("project1/chat-02"),
                "expected chat-02 under chat-01, got " + chat01ChildNames);
        // Addressing is unchanged: the registry is flat, so the name is the same either way.
        assertTrue(system.getChat("project1", "02") != null, "chat-02 is still reachable by name");
    }

    @Test
    void createProject_addsSecondProjectWithItsOwnFirstTab() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();

        String newProjectId = system.createProject();
        assertEquals("project2", newProjectId);

        ActorNode root = system.getActorTree();
        List<String> rootChildNames = root.children().stream().map(ActorNode::name).toList();
        assertTrue(rootChildNames.contains("project2"), "expected project2 under ROOT, got " + rootChildNames);

        ActorNode project2 = root.children().stream()
                .filter(n -> "project2".equals(n.name())).findFirst().orElseThrow();
        List<String> project2ChildNames = project2.children().stream().map(ActorNode::name).toList();
        assertTrue(project2ChildNames.contains("project2/chat-01"),
                "expected project2/chat-01 under project2, got " + project2ChildNames);
    }
}
