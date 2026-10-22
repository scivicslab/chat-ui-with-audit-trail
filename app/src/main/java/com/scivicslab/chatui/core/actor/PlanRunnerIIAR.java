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
     * Sends a prompt to a conversation and waits for its reply; the message is the reply.
     *
     * <p>The target is a qualified conversation name, since a plan is written knowing which
     * conversations it drives. Fails when the conversation did not reply. For several
     * conversations at once, give each a worker slot with {@code addWorker} and drive them with
     * {@code apply}.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: askChat
     *   arguments: ["project1/chat-02", "Summarize the file you were given."]
     * }</pre>
     *
     * @param args {@code [target, prompt]}: the conversation's qualified name, then what to ask it
     */
    @Action("askChat")
    public ActionResult askChat(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().askChat(at(a, 0), at(a, 1));
    }

    /**
     * Gives this plan a worker slot standing in for one conversation; the message names the slot.
     *
     * <p>The slot is this plan's child {@code <plan>.worker-<id>}, so several slots can be driven
     * at once with {@code apply} and the pattern {@code *.worker-*}, and one slot can be asked with
     * {@code askWorker}. Fails when the slot could not be created.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: addWorker
     *   arguments: ["a", "project1/chat-02"]
     * }</pre>
     *
     * @param args {@code [workerId, chatName]}: a short id for the slot, then the conversation it
     *             forwards to
     */
    @Action("addWorker")
    public ActionResult addWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().addWorker(at(a, 0), at(a, 1));
    }

    /**
     * Hands a prompt to one worker slot and waits for its reply; the message is the reply.
     *
     * <p>The slot is one {@code addWorker} made. The prompt is a top-level argument, so a
     * {@code jexl:} expression that builds it from the plan's state is evaluated — inside an
     * {@code apply} it would not be. Fails when the slot does not exist or did not reply.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: askWorker
     *   arguments: ["judge", 'jexl:"Does this text follow the rule?\n\n" + state.getString("text")']
     * }</pre>
     *
     * @param args {@code [workerId, prompt]}: the slot's id, then what to ask the conversation behind it
     */
    @Action("askWorker")
    public ActionResult askWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().askWorker(at(a, 0), at(a, 1));
    }

    /**
     * Puts one conversation on a named model.
     *
     * <p>The change is written to that conversation's settings record as any other would be. A
     * blank model leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatModel
     *   arguments: ['jexl:state.getString("fixer")', 'jexl:state.getString("fixerModel")']
     * }</pre>
     *
     * @param args {@code [chatName, model]}: the conversation's qualified name, then the model
     */
    @Action("setChatModel")
    public ActionResult setChatModel(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatModel(at(a, 0), at(a, 1));
    }

    /**
     * Puts a conversation on another agent loop. The loop decides how the conversation runs and how
     * each prompt it sends is built, so this is how a plan gives its writer a loop that sends the
     * turn's own text and nothing else.
     * <p>Fails when the conversation does not exist or the loop file is not bundled; then the
     * conversation keeps the loop it had. A blank loop leaves the conversation as it is.</p>
     * <pre>{@code
     * - actor: this
     *   method: setChatAgentLoop
     *   arguments: ["project1/chat-02", "plain-text-loop.yaml"]
     * }</pre>
     * @param args {@code [chatName, loopFile]}: the conversation's qualified name, then the agent loop's file
     */
    @Action("setChatAgentLoop")
    public ActionResult setChatAgentLoop(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatAgentLoop(at(a, 0), at(a, 1));
    }

    /**
     * Says whether one conversation asks its model to skip the thinking phase.
     *
     * <p>A blank value leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatNoThink
     *   arguments: ["project1/chat-02", "true"]
     * }</pre>
     *
     * @param args {@code [chatName, value]}: the conversation's qualified name, then {@code true} or {@code false}
     */
    @Action("setChatNoThink")
    public ActionResult setChatNoThink(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatNoThink(at(a, 0), at(a, 1));
    }

    /**
     * Says how hard one conversation's provider should work on an answer, where it has such a setting.
     *
     * <p>A blank effort leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatEffort
     *   arguments: ["project1/chat-02", "high"]
     * }</pre>
     *
     * @param args {@code [chatName, effort]}: the conversation's qualified name, then the level as
     *             its provider names it
     */
    @Action("setChatEffort")
    public ActionResult setChatEffort(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatEffort(at(a, 0), at(a, 1));
    }

    /**
     * Puts one conversation on a named provider kind, with a tool set.
     *
     * <p>A blank kind leaves the conversation as it was; a blank tool set gives the kind's own
     * default. Fails when the change could not be made, for example when no plugin provides the kind.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatProvider
     *   arguments: ["project1/chat-02", "claude", ""]
     * }</pre>
     *
     * @param args {@code [chatName, kind, tools]}: the conversation's qualified name, then
     *             {@code openai-compat}, {@code claude} or {@code codex}, then the tool set
     */
    @Action("setChatProvider")
    public ActionResult setChatProvider(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatProvider(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Stops whatever a conversation is in the middle of — the same thing the Cancel button does.
     *
     * <p>Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: stopChat
     *   arguments: ['jexl:state.getString("judge")']
     * }</pre>
     *
     * @param args {@code [chatName]}: the conversation's qualified name
     */
    @Action("stopChat")
    public ActionResult stopChat(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().stopChat(at(a, 0));
    }

    /**
     * Ends one conversation: its recorded session and the history it is holding.
     *
     * <p>Every line it wrote stays in the I/O log; the session is closed, so the conversation is not
     * reopened at the next restart. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: endConversation
     *   arguments: ['jexl:state.getString("name")']
     * }</pre>
     *
     * @param args {@code [chatName]}: the conversation's qualified name
     */
    @Action("endConversation")
    public ActionResult endConversation(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().endConversation(at(a, 0));
    }

    /**
     * Empties the conversation behind one worker slot, so the next prompt starts from nothing.
     *
     * <p>Between self-contained prompts the history adds nothing and costs context. Fails when the
     * slot's conversation was not found.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: clearWorker
     *   arguments: ["fixer"]
     * }</pre>
     *
     * @param args {@code [workerId]}: the slot's id, as {@code addWorker} was given it
     */
    @Action("clearWorker")
    public ActionResult clearWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().clearWorker(at(a, 0));
    }

    /**
     * Puts what one worker slot last replied into this plan's state.
     *
     * <p>The middle of a chain: one role's answer, kept under a key, becomes the next role's input.
     * {@code collectWorkerReplies} is the end of a fan-out and gathers every slot at once. Fails when
     * the slot does not exist or has not replied.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: keepWorkerReply
     *   arguments: ["judge", "verdict"]
     * }</pre>
     *
     * @param args {@code [workerId, key]}: the slot's id, then where to put the reply
     */
    @Action("keepWorkerReply")
    public ActionResult keepWorkerReply(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().keepWorkerReply(at(a, 0), at(a, 1));
    }

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
     * Gathers what every worker slot replied into this plan's result, in slot-name order.
     *
     * <p>The end of a fan-out over {@code apply}. Fails when no slot had a reply.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: collectWorkerReplies
     * }</pre>
     *
     * @param args ignored
     */
    @Action("collectWorkerReplies")
    public ActionResult collectWorkerReplies(String args) {
        return runner().collectWorkerReplies();
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
     * <p>Put it on the transition a failed {@code askChat} or {@code askWorker} falls through to.</p>
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
