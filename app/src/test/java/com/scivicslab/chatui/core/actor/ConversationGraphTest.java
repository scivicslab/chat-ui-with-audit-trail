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
 * table and the collaboration graph's role edges, without actors.
 */
class ConversationGraphTest {

    @Test
    void edgesFromReturnsTheRoleEdgesOfOneProjectInOrder() {
        CollaborationGraph g = new CollaborationGraph();
        g.setCollaborator("project1/chat-02", "worker", "project1/chat-03");
        g.setCollaborator("project1/chat-02", "reviewer", "project1/chat-04");
        g.setCollaborator("project10/chat-01", "worker", "project10/chat-02");
        g.setCollaborator("project2/chat-01", "worker", "project1/chat-02");

        List<CollaborationGraph.Edge> edges = g.edgesFrom("project1/");
        assertEquals(List.of(
                new CollaborationGraph.Edge("project1/chat-02", "reviewer", "project1/chat-04"),
                new CollaborationGraph.Edge("project1/chat-02", "worker", "project1/chat-03")), edges,
                "only edges whose source is in project1, sorted by source then role; project10 is not project1");
    }

    @Test
    void nodesCarryTheirNameAndTheLiaison() {
        ConversationGraph graph = ConversationGraph.of("project1", List.of("02", "01", "03"),
                Map.of("01", "liaison desk"), Map.of(), "01", List.of());
        assertEquals("project1", graph.projectId());
        assertEquals("01", graph.liaison());
        assertEquals(List.of(
                new ConversationGraph.Node("01", "liaison desk", true),
                new ConversationGraph.Node("02", null, false),
                new ConversationGraph.Node("03", null, false)), graph.nodes(), "in id order");
    }

    @Test
    void superiorEdgesGoFromTheSuperiorToTheSubordinateAndOnlyBetweenNodes() {
        ConversationGraph graph = ConversationGraph.of("project1", List.of("01", "02", "03"), Map.of(),
                Map.of("02", "01", "03", "02", "09", "01", "03x", "99"), "01", List.of());
        assertEquals(List.of(
                new ConversationGraph.SuperiorEdge("01", "02"),
                new ConversationGraph.SuperiorEdge("02", "03")), graph.superiorEdges(),
                "a row naming a conversation that is not in the project is dropped");
    }

    @Test
    void roleEdgesNameTheTargetAndItsProject() {
        List<CollaborationGraph.Edge> edges = List.of(
                new CollaborationGraph.Edge("project1/chat-02", "worker", "project1/chat-03"),
                new CollaborationGraph.Edge("project1/chat-02", "auditor", "project2/chat-05"),
                new CollaborationGraph.Edge("project1/chat-09", "worker", "project1/chat-02"),
                new CollaborationGraph.Edge("project2/chat-01", "worker", "project1/chat-02"));
        ConversationGraph graph = ConversationGraph.of("project1", List.of("01", "02", "03"), Map.of(), Map.of(),
                "01", edges);
        assertEquals(List.of(
                new ConversationGraph.RoleEdge("02", "auditor", "05", "project2"),
                new ConversationGraph.RoleEdge("02", "worker", "03", "project1")), graph.roleEdges(),
                "edges from a deleted conversation (09) or from another project are left out; "
                        + "a target in another project keeps its project");
    }

    @Test
    void aLiaisonThatIsNotInTheProjectMarksNoNode() {
        ConversationGraph graph = ConversationGraph.of("project1", List.of("02"), Map.of(), Map.of(), "01", List.of());
        assertFalse(graph.nodes().get(0).liaison());
        assertNull(graph.nodes().get(0).name());
        assertTrue(graph.superiorEdges().isEmpty() && graph.roleEdges().isEmpty());
    }
}
