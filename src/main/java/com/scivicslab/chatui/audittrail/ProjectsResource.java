package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Creates new, independent projects — see {@code ProjectScopedActorTree_260829_oo01}. Each
 * project is a self-contained actor subtree (its own {@code CallWatchdog}/{@code
 * CollaborationGraph}), so one project's {@code ask_chat}/{@code set_collaborator} calls can
 * never reach another project's tabs.
 */
@Path("/api/projects")
public class ProjectsResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Creates a new project and its first conversation tab.
     *
     * @return {@code {"chatId": "project<N>-01"}} — the new tab to switch the UI to
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response create() {
        String chatId = actorSystem.createProject();
        return Response.ok(Map.of("chatId", chatId)).build();
    }
}
