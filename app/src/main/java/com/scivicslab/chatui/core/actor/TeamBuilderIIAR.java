package com.scivicslab.chatui.core.actor;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link TeamBuilder}, for the project workflow {@code build-team}
 * ({@code TeamBuiltByProjectWorkflow_261108_oo01}). The job makes it as its own child with
 * {@code loader.createChild}, and calls it by name:
 *
 * <pre>{@code
 * - actor: loader
 *   method: createChild
 *   arguments: ['jexl:self', "./team", "com.scivicslab.chatui.core.actor.TeamBuilderIIAR"]
 * - actor: ./team
 *   method: check
 *   arguments: {owner: 'jexl:self', managers: 'jexl:state.getString("managers")', ...}
 * }</pre>
 *
 * <p>{@code createChild} can give it only its name and the actor system, so it finds the ConversationSquad actors it
 * works on the way the actors of plugin-rst-frame find their files: by the name of an actor the instance registered at
 * start-up, {@value ChatUiActorSystem#WORKFLOW_CONVERSATIONS}. Where none is registered, every action fails and says so.
 * A job makes its own {@code team}, so one job's plan never reaches another's; the plan {@code check} made and what
 * {@code build} made are kept here for the steps that follow in the same job.</p>
 */
public class TeamBuilderIIAR extends IIActorRef<TeamBuilder> {

    private TeamBuilder.Plan plan;
    private List<TeamBuilder.Built> built;

    public TeamBuilderIIAR(String actorName, IIActorSystem system) {
        super(actorName, new TeamBuilder(deskOf(system)), system);
    }

    /**
     * Looks up the {@code conversations} actor. A lookup only: it makes nothing, and an instance without the actor
     * gets a desk that refuses every operation.
     */
    @SuppressWarnings("unchecked")
    static TeamDesk deskOf(IIActorSystem system) {
        IIActorRef<Object> ref = system == null ? null
                : (IIActorRef<Object>) system.getIIActor(ChatUiActorSystem.WORKFLOW_CONVERSATIONS);
        if (ref == null) return TeamDesk.absent();
        try {
            Object desk = ref.ask(o -> o).get(5, TimeUnit.SECONDS);
            return desk instanceof TeamDesk d ? d : TeamDesk.absent();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TeamDesk.absent();
        } catch (ExecutionException | TimeoutException e) {
            return TeamDesk.absent();
        }
    }

    /**
     * Decides the team from the inputs and makes nothing. Fails, with the reason as the message, when the inputs cannot
     * make a team.
     *
     * @param args {@code {owner, managers, workersPerManager, repeatersPerManager, work, managerProvider, managerModel,
     *             workerProvider, workerModel, repeaterProvider, repeaterModel}}, all text; {@code owner} is the job,
     *             {@code jexl:self}
     */
    @Action("check")
    public ActionResult check(String args) {
        JSONObject a = new JSONObject(args);
        TeamBuilder.Inputs in = new TeamBuilder.Inputs(text(a, "owner"), text(a, "managers"), text(a, "workersPerManager"),
                text(a, "repeatersPerManager"), text(a, "work"), text(a, "managerProvider"), text(a, "managerModel"),
                text(a, "workerProvider"), text(a, "workerModel"), text(a, "repeaterProvider"), text(a, "repeaterModel"));
        try {
            plan = wrapped().check(in);
            return new ActionResult(true, plan.members().size() + " ConversationSquad actors planned under chat-" + plan.liaison());
        } catch (IllegalArgumentException | IllegalStateException e) {
            plan = null;
            return new ActionResult(false, e.getMessage());
        }
    }

    /**
     * Makes the team that {@code check} decided: all of it, or none of it. Fails, with the reason as the message, after
     * deleting what it made.
     *
     * @param args ignored
     */
    @Action("build")
    public ActionResult build(String args) {
        if (plan == null) return new ActionResult(false, "build needs a plan: check has not succeeded in this job");
        try {
            built = wrapped().build(plan);
            return new ActionResult(true, built.size() + " ConversationSquad actors built");
        } catch (IllegalArgumentException | IllegalStateException e) {
            built = null;
            return new ActionResult(false, e.getMessage());
        }
    }

    /**
     * Writes the tree that was built, which is the message, and puts the same tree in the liaison's queue as one
     * message from the job. Keep it with {@code this.putJson {path: report, value: "jexl:result"}}.
     *
     * @param args {@code {owner}}: the job, {@code jexl:self}
     */
    @Action("report")
    public ActionResult report(String args) {
        if (plan == null || built == null) return new ActionResult(false, "report needs a built team: build has not succeeded in this job");
        return new ActionResult(true, wrapped().report(text(new JSONObject(args), "owner"), plan, built));
    }

    private static String text(JSONObject a, String key) {
        return a.isNull(key) ? "" : a.get(key).toString();
    }
}
