package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;

/**
 * {@code IIActorRef} adapter that lets a plan's workflow call its own {@link PlanRunner} by
 * {@code actor: this} — the same bridge {@link ChatSessionIIAR} provides for a conversation.
 *
 * <p>Each plan step is one {@code @Action} method whose Javadoc the build writes into the action
 * manifest, so the Actions tab can show what the step does, what its arguments are and how a
 * workflow writes it ({@code ActionCatalogWithJavadoc_260930_oo01}). Every step takes its arguments
 * as a JSON array, in the order the {@code @param args} line gives; the bundled workflows write
 * them as {@code arguments: ["first", "second"]}. A {@code jexl:} text reaches the step as the value
 * the expression answers. The engine's own steps — {@code putJson}, {@code appendJson},
 * {@code onlyIf}, {@code call}, ... — come from {@link InterpreterIIAR} and are listed with these.</p>
 */
public class PlanRunnerIIAR extends InterpreterIIAR {

    public PlanRunnerIIAR(String actorName, PlanRunner runner, IIActorSystem system) {
        super(actorName, runner, system);
        // Without this, "actor: this" falls through Interpreter.action()'s selfActorRef==null path
        // and looks for an actor literally named "this" (see ChatSessionIIAR's own note).
        runner.setSelfActorRef(this);
    }

    private PlanRunner runner() { return (PlanRunner) object; }

    /**
     * Moves the first line of a list into a key and shortens the list; fails when the list is empty.
     *
     * <p>The failure is what ends a loop over files: the transition falls through when nothing is left.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: takeNext
     *   arguments: ["files", "file"]
     * }</pre>
     *
     * @param args {@code [listKey, key]}: where the remaining lines are, then where to put the line taken
     */
    @Action("takeNext")
    public ActionResult takeNext(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().takeNext(at(a, 0), at(a, 1));
    }

    /**
     * Copies one value of this plan's state to another key.
     *
     * <p>Keeps a text as it arrived before a role rewrites it, so a judge can be given both. Fails
     * when there was nothing to copy.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: copyState
     *   arguments: ["text", "original"]
     * }</pre>
     *
     * @param args {@code [from, to]}: which value to copy, then where to put the copy
     */
    @Action("copyState")
    public ActionResult copyState(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().copyState(at(a, 0), at(a, 1));
    }

    /**
     * Succeeds when the value under a key begins with the expected text, compared without case or
     * surrounding space.
     *
     * <p>Turns a verdict kept in the state into a transition: {@code ACCEPT} takes this transition
     * and anything else falls through to the one written after it.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: checkState
     *   arguments: ["verdict", "ACCEPT"]
     * }</pre>
     *
     * @param args {@code [key, expected]}: where the value is, then what it must begin with
     */
    @Action("checkState")
    public ActionResult checkState(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().checkState(at(a, 0), at(a, 1));
    }

    /**
     * Counts one attempt at a key and succeeds while the budget holds.
     *
     * <p>Each criterion can have its own key, so one loop does not spend another's budget. Fails on
     * the attempt past the limit.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: countUp
     *   arguments: ["tries-1", 'jexl:state.getString("tries")']
     * }</pre>
     *
     * @param args {@code [key, limit]}: where the count is kept, then how many attempts are allowed
     */
    @Action("countUp")
    public ActionResult countUp(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().countUp(at(a, 0), at(a, 1));
    }

    /**
     * Terminal step: hands the plan's product back to whoever is waiting on it.
     *
     * <p>With a key, the product is what this plan's state holds there; without one, the last reply
     * a step received. Put it on the transition into {@code end}.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: finish
     *   arguments: ["text"]
     * }</pre>
     *
     * @param args {@code [key]}: where the result is in this plan's state; empty for the last reply
     */
    @Action("finish")
    public ActionResult finish(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().finish(a.length() > 0 ? a.getString(0) : null);
    }

    /**
     * Fallback step: tells whoever is waiting that the plan failed, instead of leaving them to time out.
     *
     * <p>Put it on the transition a failed step falls through to.</p>
     *
     * <pre>{@code
     * - states: ["ask", "end"]
     *   actions:
     *     - actor: this
     *       method: reportFailure
     * }</pre>
     *
     * @param args ignored
     */
    @Action("reportFailure")
    public ActionResult reportFailure(String args) {
        return runner().reportFailure();
    }

    private static org.json.JSONArray arguments(String arg) {
        if (arg == null || arg.isBlank()) return new org.json.JSONArray();
        try {
            return new org.json.JSONArray(arg);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    /** The {@code i}th element as text, or "" when the array is shorter. */
    private static String at(org.json.JSONArray a, int i) {
        return a.length() > i ? a.getString(i) : "";
    }
}
