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

import java.util.LinkedHashMap;
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
     * @param body      {@code {"workflow": name}}, the name as the catalog lists it, and
     *                  optionally {@code "parameters"}: the values its <code>${key}</code> stand
     *                  for, keyed by the names {@code Document.params} declares
     *                  ({@code JobParameterForm_260913_oo01})
     * @return the new job; 404 when there is no such project or workflow; 400 when the workflow
     *         cannot be read, the request names none, or a required value is missing
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(@PathParam("projectId") String projectId, Map<String, Object> body) {
        Object named = body == null ? null : body.get("workflow");
        String workflow = named == null ? null : String.valueOf(named);
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

        Map<String, String> given = given(body);
        String missing = firstMissing(doc.params(), given);
        if (missing != null) {
            return Response.status(400).entity(Map.of("error", missing)).build();
        }
        Map<String, String> values = withDefaults(doc.params(), given);
        Project.JobView job = project.ask(p -> p.startJob(workflow, doc.yaml(), values)).join();
        return Response.ok(job).build();
    }

    /** @return the {@code parameters} of the request as text, keyed by name; empty when absent */
    private static Map<String, String> given(Map<String, Object> body) {
        Object parameters = body == null ? null : body.get("parameters");
        if (!(parameters instanceof Map<?, ?> m)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return out;
    }

    /**
     * @return why the run cannot start — the first declared input that must have a value and was
     *         given none — or {@code null} when every one of them has one. An input with a default
     *         has one even when the request left it out.
     */
    private static String firstMissing(List<ProjectWorkflowCatalog.ParamSpec> params,
                                       Map<String, String> given) {
        if (params == null) return null;
        for (ProjectWorkflowCatalog.ParamSpec p : params) {
            if (!p.required()) continue;
            String value = given.get(p.key());
            if (value != null && !value.isBlank()) continue;
            if (p.defaultValue() != null) continue;
            return "parameter is required: " + p.key();
        }
        return null;
    }

    /**
     * @return what the run is given: the values of the request, plus the declared default of every
     *         input the request left out — the same order {@code RunCLI} fills them in
     */
    private static Map<String, String> withDefaults(List<ProjectWorkflowCatalog.ParamSpec> params,
                                                    Map<String, String> given) {
        Map<String, String> out = new LinkedHashMap<>(given);
        if (params == null) return out;
        for (ProjectWorkflowCatalog.ParamSpec p : params) {
            String value = out.get(p.key());
            if ((value == null || value.isBlank()) && p.defaultValue() != null) {
                out.put(p.key(), p.defaultValue());
            }
        }
        return out;
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
