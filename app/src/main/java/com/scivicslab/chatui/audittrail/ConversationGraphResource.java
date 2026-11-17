package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationGraph;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Every project's conversations and the edges between them, in one call, for the left pane's single tree
 * ({@code ConversationGraphInTheLeftPane_261117_oo01}). Each project is read from its actors (the organization,
 * the collaboration graph, the conversations) on this call; nothing is kept. Read-only.
 */
@Path("/api/graph")
public class ConversationGraphResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /** @return {@code {"projects": [<one ConversationGraph per project>]}}, in the order of the projects' numbers */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ConversationGraph.All all() {
        return actorSystem.conversationGraphs();
    }
}
