package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.SkillRegistry;
import com.scivicslab.pojoactor.core.ActorRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Serves the skill catalog for the Extensions panel's Skills tab, and one skill's instructions
 * for anyone who wants to read what a conversation would receive
 * ({@code SkillAndAgentsFile_260830_oo01}).
 *
 * <p>Read-only apart from the rescan: skills are authored as files, not through this system.</p>
 */
@Path("/api/skills")
public class SkillsResource {

    private static final int TIMEOUT_SECONDS = 10;

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * One catalog entry.
     *
     * @param name        the skill's name
     * @param description what it does and when to use it
     * @param directory   the skill's directory, where its own files live
     */
    public record SkillView(String name, String description, String directory) {}

    /**
     * The whole catalog.
     *
     * @param roots    the directories searched, in precedence order
     * @param skills   the indexed skills
     * @param problems one message per skill directory that was found but could not be indexed
     */
    public record CatalogView(List<String> roots, List<SkillView> skills, List<String> problems) {}

    /**
     * @return the catalog as it stands, or 503 if the registry cannot be reached
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        return catalogResponse(false);
    }

    /**
     * Re-indexes every skill root, then returns the new catalog. Adding or renaming a skill needs
     * this; editing an already-indexed skill's body does not, since bodies are read from disk when
     * they are requested.
     *
     * @return the catalog after rescanning
     */
    @POST
    @jakarta.ws.rs.Path("/rescan")
    @Produces(MediaType.APPLICATION_JSON)
    public Response rescan() {
        return catalogResponse(true);
    }

    /**
     * @param name the skill's name
     * @return that skill's instructions as plain text, or 404
     */
    @GET
    @jakarta.ws.rs.Path("/{name}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response body(@PathParam("name") String name) {
        ActorRef<SkillRegistry> registry = actorSystem.getSkillRegistry();
        if (registry == null) return Response.status(503).entity("skill registry unavailable").build();
        try {
            String body = registry.ask(r -> r.bodyOf(name)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (body == null) return Response.status(404).entity("no skill named '" + name + "'").build();
            return Response.ok(body).build();
        } catch (Exception e) {
            return Response.status(503).entity("skill registry error: " + e.getMessage()).build();
        }
    }

    private Response catalogResponse(boolean rescan) {
        ActorRef<SkillRegistry> registry = actorSystem.getSkillRegistry();
        if (registry == null) {
            return Response.status(503)
                    .entity(Map.of("type", "error", "message", "skill registry unavailable")).build();
        }
        try {
            CatalogView view = registry.ask(r -> {
                if (rescan) r.scan();
                List<SkillView> skills = r.getSkills().stream()
                        .map(s -> new SkillView(s.name(), s.description(), s.directory().toString()))
                        .toList();
                List<String> roots = r.getRoots().stream().map(java.nio.file.Path::toString).toList();
                return new CatalogView(roots, skills, r.getProblems());
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Response.ok(view).build();
        } catch (Exception e) {
            return Response.status(503)
                    .entity(Map.of("type", "error", "message", e.getMessage())).build();
        }
    }
}
