package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.Project;
import com.scivicslab.pojoactor.core.ActorRef;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The workflows of one project, for the project perspective's centre pane
 * ({@code ProjectPerspective_260911_oo01}).
 *
 * <p>Project-scoped, unlike {@code ChatResource}'s {@code /chats/{chatId}/workflows}, which
 * answers only the two files one conversation is configured with. This lists everything the
 * project could run — its own files under {@code workflows/} in its working directory, and the
 * bundled ones — and reads any of them.</p>
 */
@Path("/api/projects/{projectId}/workflows")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectWorkflowResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Lists the project's workflows.
     *
     * @param projectId the project
     * @param q         text to match, or absent for all — see {@link ProjectWorkflowCatalog#list}
     * @return the rows, or 404 when there is no such project
     */
    @GET
    public Response list(@PathParam("projectId") String projectId, @QueryParam("q") String q) {
        ProjectWorkflowCatalog catalog = catalogOf(projectId);
        if (catalog == null) return noSuchProject(projectId);
        List<ProjectWorkflowCatalog.Entry> rows = catalog.list(q);
        return Response.ok(rows).build();
    }

    /**
     * Reads one workflow.
     *
     * @param projectId the project
     * @param name      the basename without {@code .yaml}
     * @return {@code {name, yaml, origin, editable}}, or 404 when there is no such project or
     *         workflow, or 400 when the name is not a plain basename
     */
    @GET
    @Path("/{name}")
    public Response read(@PathParam("projectId") String projectId, @PathParam("name") String name) {
        if (!ProjectWorkflowCatalog.isSafeName(name)) {
            return Response.status(400).entity(Map.of("error", "not a workflow name: " + name)).build();
        }
        ProjectWorkflowCatalog catalog = catalogOf(projectId);
        if (catalog == null) return noSuchProject(projectId);
        ProjectWorkflowCatalog.Document doc = catalog.read(name);
        if (doc == null) {
            return Response.status(404).entity(Map.of("error", "unknown workflow: " + name)).build();
        }
        return Response.ok(doc).build();
    }

    /**
     * @return a catalog over the project's current working directory, or {@code null} when there
     *         is no such project
     */
    private ProjectWorkflowCatalog catalogOf(String projectId) {
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return null;
        java.nio.file.Path dir = project.ask(Project::getWorkingDir).join();
        return new ProjectWorkflowCatalog(dir);
    }

    private static Response noSuchProject(String projectId) {
        return Response.status(404).entity(Map.of("error", "unknown project: " + projectId)).build();
    }
}
