package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.scivicslab.chatui.agent.OrganizationTools;
import com.scivicslab.chatui.agent.SetCollaboratorTool;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationGraph;
import com.scivicslab.chatui.core.actor.StartedSystem;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/projects/{projectId}/graph} on a real actor system, without Quarkus
 * ({@code ConversationGraphInTheLeftPane_261117_oo01}): the superiors set_superior made and the role edges
 * set_collaborator made come back, and a change shows on the next call, since nothing is copied.
 */
class ConversationGraphEndpointTest {

    private static ChatResource resource(ChatUiActorSystem system) {
        ChatResource r = new ChatResource();
        r.actorSystem = system;
        return r;
    }

    private static ConversationGraph graph(ChatResource r, String projectId) {
        Response res = r.graph(projectId);
        assertEquals(200, res.getStatus());
        return (ConversationGraph) res.getEntity();
    }

    @Test
    void theGraphShowsTheSuperiorsAndTheRoleEdgesAndFollowsAChange() {
        ChatUiActorSystem system = StartedSystem.start();
        system.createChat("project1");
        system.createChat("project1");
        system.createChat("project1");
        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "02", "01").startsWith("ok"));
        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "03", "02").startsWith("ok"));
        assertTrue(SetCollaboratorTool.setCollaborator(system.getCollaborationGraph(), "project1/chat-01",
                "02", "worker", "03").startsWith("ok"));
        ChatResource r = resource(system);

        ConversationGraph g = graph(r, "project1");
        assertEquals(List.of("01", "02", "03", "04"), g.nodes().stream().map(ConversationGraph.Node::chatId).toList());
        assertTrue(g.nodes().get(0).liaison(), "chat-01 is the liaison");
        assertTrue(g.superiorEdges().contains(new ConversationGraph.SuperiorEdge("01", "02")));
        assertTrue(g.superiorEdges().contains(new ConversationGraph.SuperiorEdge("02", "03")));
        assertEquals(List.of(new ConversationGraph.RoleEdge("02", "worker", "03", "project1")), g.roleEdges());

        // A change made after the first call shows on the next one.
        assertTrue(OrganizationTools.setSuperior(system, "project1", "01", "04", "02").startsWith("ok"));
        assertTrue(SetCollaboratorTool.setCollaborator(system.getCollaborationGraph(), "project1/chat-01",
                "02", "worker", "04").startsWith("ok"));
        ConversationGraph after = graph(r, "project1");
        assertTrue(after.superiorEdges().contains(new ConversationGraph.SuperiorEdge("02", "04")), after.toString());
        assertEquals(List.of(new ConversationGraph.RoleEdge("02", "worker", "04", "project1")), after.roleEdges(),
                "the role was reassigned, not added");
    }

    @Test
    void anUnknownProjectIs404() {
        ChatUiActorSystem system = StartedSystem.start();
        assertEquals(404, resource(system).graph("project99").getStatus());
    }
}
