package com.scivicslab.chatui.audittrail;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.pojoactor.action.schema.ActionCatalog;
import com.scivicslab.pojoactor.action.schema.ActionManifest;
import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;
import com.scivicslab.pojoactor.action.schema.ActionStepYaml;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

/**
 * The Actions tab: what a workflow YAML may write in an {@code actor:} / {@code method:} pair,
 * answered without a run ({@code ActionCatalogWithJavadoc_260930_oo01}).
 *
 * <p>An actor is named as the YAML names it — a live actor's qualified name such as
 * {@code project1/chat-02}, or {@code this} for the plan's own runner — and the answer comes from
 * the class behind that name: its {@code @Action} methods, the JSON Schemas the build generated
 * from their argument records, and the Javadoc the doclet wrote into the action manifest. The actor
 * name goes in a query parameter because it may contain {@code /}.</p>
 */
@Path("/api/actions")
public class ActionsResource {

    private static final ActionSchemaRegistry SCHEMAS = new ActionSchemaRegistry();
    private static final ActionManifest MANIFEST = new ActionManifest();

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * The actors a workflow may name, for the tab's upper pane:
     * {@code {actors:[{name, type, origin}, ...]}} — {@code this} first, then every live actor.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response actors() {
        return Response.ok(Map.of("actors", actorSystem.workflowActors())).build();
    }

    /**
     * The actions of one actor, each with the first sentence of its Javadoc:
     * {@code {actor, class, actions:[{name, description}, ...]}}.
     *
     * @param actor  the actor name as a workflow writes it
     * @param origin the row's origin as {@link #actors} gave it; names are unique here, so it only
     *               keeps the request the same shape as quarkus-chat-ui's
     */
    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@QueryParam("actor") String actor, @QueryParam("origin") String origin) {
        Class<?> cls = actorSystem.actorClassOf(actor);
        if (cls == null) return Response.status(404).entity(Map.of("error", "unknown actor: " + actor)).build();
        SortedSet<String> names = ActionCatalog.actionNamesOf(cls);
        List<Map<String, String>> actions = new ArrayList<>();
        for (String name : names) {
            ActionManifest.ActionDoc doc = MANIFEST.docFor(cls, name);
            actions.add(Map.of("name", name, "description", doc == null ? "" : doc.description()));
        }
        return Response.ok(Map.of("actor", actor, "class", cls.getName(), "actions", actions)).build();
    }

    /**
     * The description of one action: the Javadoc's first sentence, the rest as {@code details}, its
     * {@code <pre>} block as {@code example}; the JSON {@code schema} of a record argument or the
     * {@code argument} of a raw-String one; and {@code yaml}, the step as a workflow writes it.
     *
     * @param actor  the actor name as a workflow writes it
     * @param action the action name
     * @param origin the row's origin as {@link #actors} gave it; not needed to resolve a name here
     */
    @GET
    @Path("/describe")
    @Produces(MediaType.APPLICATION_JSON)
    public Response describe(@QueryParam("actor") String actor, @QueryParam("action") String action,
                             @QueryParam("origin") String origin) {
        Class<?> cls = actorSystem.actorClassOf(actor);
        if (cls == null) return Response.status(404).entity(Map.of("error", "unknown actor: " + actor)).build();
        if (action == null || !ActionCatalog.actionNamesOf(cls).contains(action)) {
            return Response.status(404).entity(Map.of("error", "actor " + actor + " has no action " + action)).build();
        }
        ObjectNode node = ActionCatalog.describe(cls, action, SCHEMAS, MANIFEST);
        node.put("actor", actor);
        node.put("yaml", ActionStepYaml.of(actor, action, node));
        return Response.ok(node.toString()).build();
    }
}
