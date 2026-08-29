package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Runs one plan — a workflow a conversation wrote — as an actor of its own, separate from the
 * conversation that asked for it ({@code BabysitterRealisticE2eScenario_260828_oo01}
 * "chat-01のworkflowは、chat-01自身のagent loopとしてではなく、別アクター（PlanRunner）として動かす").
 *
 * <p>A conversation cannot install a plan on itself: {@code set_workflow} asks the target actor and
 * waits for the answer, so aimed at itself the message would sit in its own mailbox while its only
 * thread is blocked waiting for it. Running the plan in a separate actor avoids that, and — because
 * it is an actor rather than a plain object — leaves it reachable while it runs, so
 * {@code requestStop()} (inherited from {@link Interpreter}, safe to send via {@code tellNow}) can
 * stop it between transitions.</p>
 *
 * <p>Holds no {@code ActorRef} to the conversation that owns it; it only knows that conversation's
 * name, which it uses as the waiter id when it waits on someone
 * ({@code ChatSessionPorting_260823_oo01}'s "look up by name at the point of use").</p>
 */
public class PlanRunner extends Interpreter {

    private static final Logger LOG = Logger.getLogger(PlanRunner.class.getName());

    /** Long, for the same reason {@code ChatSession}'s babysitter phases use one: the conversation
     *  this asks runs its own multi-step turn inside the call. */
    private static final int ASK_TIMEOUT_SECONDS = 1800;

    private final ActorRef<CallWatchdog> watchdog;
    /** This runner's own actor name, e.g. {@code project1/chat-01.plan} — its waiter id. */
    private final String myName;

    /** The last reply a plan step received, and the plan's result when it finishes. */
    private String lastReply;
    /** Completed by {@link #finish()} (or by the caller if the plan never reaches it). */
    private CompletableFuture<String> done;

    /**
     * @param myName   this runner's own actor name
     * @param system   the actor system, used to reach the conversations a plan step talks to
     * @param watchdog the shared {@link CallWatchdog}
     */
    public PlanRunner(String myName, IIActorSystem system, ActorRef<CallWatchdog> watchdog) {
        this.myName = myName;
        this.watchdog = watchdog;
        // Assigns Interpreter's own inherited field — do not redeclare it here. A private field of
        // the same name would shadow it, leaving the one Interpreter.action() reads null, and the
        // plan would die on its first step with an NPE.
        this.system = system;
    }

    /** @param done completed with the plan's result once it finishes */
    public void setDone(CompletableFuture<String> done) { this.done = done; }

    /** @return the reply the last plan step received, or {@code null} */
    public String lastReply() { return lastReply; }

    /**
     * Plan step: sends {@code prompt} to a conversation and waits for its reply. The target is a
     * qualified conversation name ({@code project1/chat-02}), since a plan is written knowing which
     * conversations it drives.
     *
     * @param target the target conversation's qualified name
     * @param prompt what to ask it to do
     * @return {@link ActionResult} with {@code success=true} iff the conversation replied
     */
    public ActionResult askChat(String target, String prompt) {
        if (target == null || target.isBlank()) {
            return new ActionResult(false, "askChat: target is required");
        }
        String reply = AskChatTool.askQualified(system, watchdog, myName, target, prompt,
                ASK_TIMEOUT_SECONDS);
        if (reply == null || reply.startsWith("error:")) {
            lastReply = reply != null ? reply : "no reply from " + target;
            return new ActionResult(false, lastReply);
        }
        lastReply = reply;
        return new ActionResult(true, "reply received");
    }

    /**
     * Terminal step: hands the last reply back to whoever is waiting on this plan.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult finish() {
        complete(lastReply != null ? lastReply : "(plan finished with no result)");
        return new ActionResult(true, "finished");
    }

    /**
     * Fallback step, for the same reason the babysitter workflows have one: a failed
     * {@link #askChat} would otherwise leave its state with no transition left to try, and whoever
     * is waiting would wait until their own timeout instead of being told what happened.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult reportFailure() {
        complete("(plan stopped: " + lastReply + ")");
        return new ActionResult(true, "reported failure");
    }

    /**
     * Completes the waiting caller, unless the plan already did. Called by {@link #finish()}/
     * {@link #reportFailure()}, and by the code that started the plan if it ended without reaching
     * either — so nobody is left waiting on a plan that has stopped running.
     *
     * @param result what to hand back
     */
    public void complete(String result) {
        if (done != null && !done.isDone()) {
            done.complete(result);
        } else if (done == null) {
            LOG.warning("PlanRunner " + myName + " completed with no waiter: " + result);
        }
    }
}
