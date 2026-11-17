package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The graph the left pane draws (ConversationGraphInTheLeftPane_261117_oo01): built from the organization's superior
 * table and the role table's edges, without actors.
 */
class ConversationGraphTest {

    @Test
    void nodesCarryTheirNameAndTheLiaison() {
        ConversationGraph graph = ConversationGraph.of("project1", null, List.of("02", "01", "03"),
                Map.of("01", "liaison desk"), Map.of(), "01", List.of(), Map.of());
        assertEquals("project1", graph.projectId());
        assertEquals("01", graph.liaison());
        assertEquals(List.of(
                new ConversationGraph.Node("01", "liaison desk", true, null, null),
                new ConversationGraph.Node("02", null, false, null, null),
                new ConversationGraph.Node("03", null, false, null, null)), graph.nodes(), "in id order");
    }

    @Test
    void superiorEdgesGoFromTheSuperiorToTheSubordinateAndOnlyBetweenNodes() {
        ConversationGraph graph = ConversationGraph.of("project1", null, List.of("01", "02", "03"), Map.of(),
                Map.of("02", "01", "03", "02", "09", "01", "03x", "99"), "01", List.of(), Map.of());
        assertEquals(List.of(
                new ConversationGraph.SuperiorEdge("01", "02"),
                new ConversationGraph.SuperiorEdge("02", "03")), graph.superiorEdges(),
                "a row naming a conversation that is not in the project is dropped");
    }

    @Test
    void roleEdgesNameTheTargetAndItsProject() {
        List<RoleTable.Edge> edges = List.of(
                new RoleTable.Edge("project1/chat-02", "worker", "project1/chat-03"),
                new RoleTable.Edge("project1/chat-02", "auditor", "project2/chat-05"),
                new RoleTable.Edge("project1/chat-09", "worker", "project1/chat-02"),
                new RoleTable.Edge("project2/chat-01", "worker", "project1/chat-02"));
        ConversationGraph graph = ConversationGraph.of("project1", null, List.of("01", "02", "03"), Map.of(), Map.of(),
                "01", edges, Map.of());
        assertEquals(List.of(
                new ConversationGraph.RoleEdge("02", "auditor", "05", "project2"),
                new ConversationGraph.RoleEdge("02", "worker", "03", "project1")), graph.roleEdges(),
                "edges from a deleted conversation (09) or from another project are left out; "
                        + "a target in another project keeps its project");
    }

    @Test
    void aLiaisonThatIsNotInTheProjectMarksNoNode() {
        ConversationGraph graph = ConversationGraph.of("project1", null, List.of("02"), Map.of(), Map.of(), "01", List.of(), Map.of());
        assertFalse(graph.nodes().get(0).liaison());
        assertNull(graph.nodes().get(0).name());
        assertTrue(graph.superiorEdges().isEmpty() && graph.roleEdges().isEmpty());
    }

    @Test
    void aNodeCarriesWhatItRunsOnAndOneWithoutAnEntryCarriesNothing() {
        ConversationGraph graph = ConversationGraph.of("project1", null, List.of("01", "02"), Map.of(), Map.of(), "01", List.of(),
                Map.of("01", new ConversationGraph.Model("Claude Code", "opus")));
        assertEquals(new ConversationGraph.Node("01", null, true, "Claude Code", "opus"), graph.nodes().get(0));
        assertEquals(new ConversationGraph.Node("02", null, false, null, null), graph.nodes().get(1));
    }

    @Test
    void theProviderIsNamedAsTheDropdownNamesIt() {
        List<ConversationControls.ProviderOption> options = List.of(
                new ConversationControls.ProviderOption("openai-compat", "openai-compat", com.scivicslab.chatui.agent.ToolSet.FULL, "Local LLM"),
                new ConversationControls.ProviderOption("claude", "claude", com.scivicslab.chatui.agent.ToolSet.FULL, "Claude Code"));
        assertEquals("Claude Code", ChatUiActorSystem.providerLabel(options, "claude", com.scivicslab.chatui.agent.ToolSet.FULL.id()));
        assertEquals("Local LLM", ChatUiActorSystem.providerLabel(options, "openai-compat", "some-other-set"),
                "no entry with that tool set: the kind's first entry");
        assertEquals("codex", ChatUiActorSystem.providerLabel(options, "codex", "full"), "not in the dropdown: the kind");
    }

    @Test
    void theGraphCarriesTheProjectsName() {
        ConversationGraph graph = ConversationGraph.of("project1", "Graph check", List.of("01"), Map.of(), Map.of(), "01",
                List.of(), Map.of());
        assertEquals("Graph check", graph.name());
        assertNull(ConversationGraph.of("project1", null, List.of("01"), Map.of(), Map.of(), "01", List.of(), Map.of()).name());
    }
}
