package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.Project;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.pojoactor.core.ActorRef;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * The batch jobs of one project, for the project perspective's right pane
 * ({@code ProjectPerspective_260911_oo01}).
 *
 * <p>A job is a workflow of the project's catalog run as the project's child actor; the
 * {@link Project} actor owns the list. Starting one reads the workflow from the catalog and checks
 * it the way saving does, so what runs is what the catalog shows.</p>
 */
@Path("/api/projects/{projectId}/jobs")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectJobResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * Starts a workflow as a new job.
     *
     * @param projectId the project
     * @param body      {@code {"workflow": name}}, the name as the catalog lists it
     * @return the new job; 404 when there is no such project or workflow; 400 when the workflow
     *         cannot be read or the request names none
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(@PathParam("projectId") String projectId, Map<String, String> body) {
        String workflow = body == null ? null : body.get("workflow");
        if (workflow == null || workflow.isBlank()) {
            return Response.status(400).entity(Map.of("error", "workflow is required")).build();
        }
        if (!ProjectWorkflowCatalog.isSafeName(workflow)) {
            return Response.status(400).entity(Map.of("error", "not a workflow name: " + workflow)).build();
        }
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return noSuchProject(projectId);
        ProjectWorkflowCatalog.Document doc =
                ProjectWorkflowResource.catalogFor(actorSystem, projectId).read(workflow);
        if (doc == null) {
            return Response.status(404).entity(Map.of("error", "unknown workflow: " + workflow)).build();
        }
        String why = ProjectWorkflowCatalog.validate(doc.yaml());
        if (why != null) return Response.status(400).entity(Map.of("error", why)).build();
        Project.JobView job = project.ask(p -> p.startJob(workflow, doc.yaml())).join();
        return Response.ok(job).build();
    }

    /**
     * @param projectId the project
     * @return its jobs, newest first; 404 when there is no such project
     */
    @GET
    public Response list(@PathParam("projectId") String projectId) {
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return noSuchProject(projectId);
        List<Project.JobView> jobs = project.ask(Project::jobs).join();
        return Response.ok(jobs).build();
    }

    /**
     * @param projectId the project
     * @param jobId     the job
     * @return the job; 404 when there is no such project or job
     */
    @GET
    @Path("/{jobId}")
    public Response one(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return noSuchProject(projectId);
        Project.JobView job = project.ask(p -> p.job(jobId)).join();
        if (job == null) return noSuchJob(jobId);
        return Response.ok(job).build();
    }

    /**
     * Asks a running job to stop.
     *
     * @param projectId the project
     * @param jobId     the job
     * @return {@code {"type":"stopping"}}; 404 when there is no such project or job; 409 when the
     *         job is not running
     */
    @POST
    @Path("/{jobId}/stop")
    public Response stop(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return noSuchProject(projectId);
        Project.JobView job = project.ask(p -> p.job(jobId)).join();
        if (job == null) return noSuchJob(jobId);
        boolean asked = project.ask(p -> p.stopJob(jobId)).join();
        if (!asked) {
            return Response.status(409).entity(Map.of("error", "job " + jobId + " is " + job.state().toLowerCase())).build();
        }
        return Response.ok(Map.of("type", "stopping")).build();
    }

    /**
     * @param projectId the project
     * @param jobId     the job
     * @return the job's log lines, oldest first; 404 when there is no such project or job
     */
    @GET
    @Path("/{jobId}/log")
    public Response log(@PathParam("projectId") String projectId, @PathParam("jobId") String jobId) {
        ActorRef<Project> project = actorSystem.getProject(projectId);
        if (project == null) return noSuchProject(projectId);
        List<RecentEntriesAccumulator.Entry> entries = project.ask(p -> p.jobLog(jobId)).join();
        if (entries == null) return noSuchJob(jobId);
        return Response.ok(entries).build();
    }

    private static Response noSuchProject(String projectId) {
        return Response.status(404).entity(Map.of("error", "unknown project: " + projectId)).build();
    }

    private static Response noSuchJob(String jobId) {
        return Response.status(404).entity(Map.of("error", "unknown job: " + jobId)).build();
    }
}
