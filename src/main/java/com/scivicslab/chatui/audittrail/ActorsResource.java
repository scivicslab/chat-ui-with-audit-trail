package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ActorNode;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the actor tree for the right-pane Actors tab.
 */
@Path("/api/actors")
public class ActorsResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Returns the current actor tree.
     *
     * @return the root {@link ActorNode}, serialized as JSON
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ActorNode get() {
        return actorSystem.getActorTree();
    }
}
