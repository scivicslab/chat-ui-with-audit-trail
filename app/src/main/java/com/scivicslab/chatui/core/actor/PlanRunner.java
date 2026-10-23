package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Holds nothing of the conversations a plan talks to: a plan reaches them through the actor
 * {@code chats} by their names, and gives its own name ({@code jexl:self}) as the one who waits
 * ({@code ChatSessionPorting_260823_oo01}'s "look up by name at the point of use").</p>
 */
public class PlanRunner extends Interpreter {

    private static final Logger LOG = Logger.getLogger(PlanRunner.class.getName());

    /** This runner's own actor name, e.g. {@code project1/job-03}. */
    private final String myName;

    /** What the last transition left answered; the plan's result when it ends without naming one. */
    private String lastResult;
    /** What the last transition that failed answered: why the plan stopped, when it reports a failure. */
    private String lastFailure;
    /** Completed by {@link #finish()} (or by the caller if the plan never reaches it). */
    private CompletableFuture<String> done;

    /**
     * @param myName this runner's own actor name
     * @param system the actor system, where the actors a plan step calls are
     */
    public PlanRunner(String myName, IIActorSystem system) {
        this.myName = myName;
        // Assigns Interpreter's own inherited field — do not redeclare it here. A private field of
        // the same name would shadow it, leaving the one Interpreter.action() reads null, and the
        // plan would die on its first step with an NPE.
        this.system = system;
    }

    /** @param done completed with the plan's result once it finishes */
    public void setDone(CompletableFuture<String> done) { this.done = done; }

    /** Told one line about every transition this plan leaves; {@code null} means nobody listens. */
    private java.util.function.Consumer<String> stepListener;

    /**
     * @param listener told one line per transition left, e.g. for a job's log
     *                 ({@code ProjectPerspective_260911_oo01}); {@code null} to tell nobody
     */
    public void setStepListener(java.util.function.Consumer<String> listener) {
        this.stepListener = listener;
    }

    /**
     * Reports the transition just left to {@link #setStepListener the listener}, then does what
     * {@code Interpreter} does.
     */
    @Override
    protected void onExitTransition(com.scivicslab.turingworkflow.workflow.Transition transition,
                                    boolean success, ActionResult result) {
        if (result != null && result.getResult() != null && !result.getResult().isBlank()) {
            lastResult = result.getResult();
            if (!success) lastFailure = result.getResult();
        }
        java.util.function.Consumer<String> listener = stepListener;
        if (listener != null && transition != null) {
            String label = transition.getLabel();
            String states = transition.getStates() == null ? "" : String.join(" -> ", transition.getStates());
            String line = "step " + currentTransitionIndex
                    + (label == null || label.isBlank() ? "" : " " + label)
                    + (states.isEmpty() ? "" : " [" + states + "]")
                    + (success ? " ok" : " failed")
                    + (result == null || result.getResult() == null || result.getResult().isBlank()
                        ? "" : ": " + result.getResult());
            try {
                listener.accept(line);
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.FINE, "step listener failed", e);
            }
        }
        super.onExitTransition(transition, success, result);
    }

    /** @return what the last transition left answered, or {@code null} */
    public String lastResult() { return lastResult; }

    /**
     * Runs one transition, with its {@code note} available to anything the transition creates
     * ({@code ActorPurposeFromWorkflowNote_260831_oo01}). {@code code} and
     * {@code currentTransitionIndex} are {@code Interpreter}'s own protected fields, so a subclass
     * can read the transition it is about to execute without changing the library.
     *
     * @return whatever the transition's actions returned
     */
    @Override
    public ActionResult action() {
        String note = null;
        try {
            note = code.getTransitions().get(currentTransitionIndex).getNote();
        } catch (Exception e) {
            // No transition to read: fall through with no note.
        }
        ActorNotes.enterTransition(note);
        try {
            return super.action();
        } finally {
            ActorNotes.leaveTransition();
        }
    }

    /**
     * Plan step: moves the first line of the list at {@code listKey} into {@code key} and shortens
     * the list, succeeding while there was one ({@code UnattendedFileRuns_260913_oo01}).
     *
     * <p>What makes a loop over a list: the transition written after this one is taken when the
     * list is empty, so a plan works through the files and then leaves.</p>
     *
     * @param listKey where the remaining lines are
     * @param key     where to put the line taken
     * @return {@link ActionResult} with {@code success=true} iff a line was taken
     */
    public ActionResult takeNext(String listKey, String key) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (listKey == null || listKey.isBlank()) return new ActionResult(false, "listKey is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        String remaining = selfActorRef.getJsonString(listKey);
        if (remaining == null || remaining.isBlank()) return new ActionResult(false, "nothing left in '" + listKey + "'");
        int newline = remaining.indexOf('\n');
        String head = newline < 0 ? remaining : remaining.substring(0, newline);
        String tail = newline < 0 ? "" : remaining.substring(newline + 1);
        selfActorRef.putJson(key, head);
        selfActorRef.putJson(listKey, tail);
        long left = tail.isBlank() ? 0 : tail.lines().count();
        return new ActionResult(true, "took " + head + " (" + left + " left)");
    }

    /**
     * Plan step: copies one value of this plan's state to another key
     * ({@code KeepTheFactsWhileCutting_260913_oo01}).
     *
     * <p>What lets a judge compare before with after: the text is copied aside before the step that
     * rewrites it, and the judge is given both.</p>
     *
     * @param from which value to copy
     * @param to   where to put the copy
     * @return {@link ActionResult} with {@code success=true} iff there was something to copy
     */
    public ActionResult copyState(String from, String to) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (from == null || from.isBlank()) return new ActionResult(false, "from is required");
        if (to == null || to.isBlank()) return new ActionResult(false, "to is required");
        String value = selfActorRef.getJsonString(from);
        if (value == null) return new ActionResult(false, "nothing is kept as '" + from + "'");
        selfActorRef.putJson(to, value);
        return new ActionResult(true, "copied " + value.length() + " chars of '" + from + "' to '" + to + "'");
    }

    /**
     * Plan step: succeeds when what the plan kept under {@code key} begins with {@code expected}
     * ({@code OneCriterionPerTurn_260913_oo01}).
     *
     * <p>How a plan branches on a judgement. A conversation asked to judge answers with words; the
     * state machine moves on success and failure. This turns the one into the other, so
     * {@code ACCEPT} takes the transition to the next thing to fix and anything else falls through
     * to the redo transition written after it.</p>
     *
     * @param key      where the judgement is in this plan's state
     * @param expected what the value must begin with, compared without case or surrounding space
     * @return {@link ActionResult} with {@code success=true} iff it does
     */
    public ActionResult checkState(String key, String expected) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (expected == null || expected.isBlank()) return new ActionResult(false, "expected is required");
        String value = selfActorRef.getJsonString(key);
        String head = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        boolean matches = head.startsWith(expected.strip().toUpperCase(java.util.Locale.ROOT));
        String first = head.isEmpty() ? "(nothing)" : head.substring(0, Math.min(60, head.length()));
        return new ActionResult(matches, (matches ? "accepted: " : "not yet: ") + first);
    }

    /**
     * Plan step: counts one attempt at {@code key} and succeeds while the budget holds
     * ({@code OneCriterionPerTurn_260913_oo01}).
     *
     * <p>What bounds a redo loop. The count lives in the plan's state, so each thing being fixed
     * can have its own key and the loop for one does not spend another's budget.</p>
     *
     * @param key   where the count is kept
     * @param limit how many attempts are allowed
     * @return {@link ActionResult} with {@code success=true} iff this attempt is within the limit
     */
    public ActionResult countUp(String key, String limit) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        int allowed;
        try {
            allowed = Integer.parseInt(limit == null ? "" : limit.strip());
        } catch (NumberFormatException e) {
            return new ActionResult(false, "limit must be a whole number, not '" + limit + "'");
        }
        int used = 0;
        String current = selfActorRef.getJsonString(key);
        if (current != null && !current.isBlank()) {
            try {
                used = Integer.parseInt(current.strip());
            } catch (NumberFormatException e) {
                used = 0;
            }
        }
        used++;
        selfActorRef.putJson(key, String.valueOf(used));
        return new ActionResult(used <= allowed, "attempt " + used + " of " + allowed);
    }

    /**
     * Terminal step: hands the last reply back to whoever is waiting on this plan.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult finish() {
        return finish(null);
    }

    /**
     * Terminal step: hands back what this plan's state holds under {@code key}, or what the last
     * transition answered when no key is given ({@code ChainedRolesInAPlan_260913_oo01}). A chain that
     * kept each role's answer names the one that is the plan's product, rather than ending with
     * whatever the last step happened to answer.
     *
     * @param key where the result is in this plan's state, or {@code null} for the last answer
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult finish(String key) {
        String kept = (key == null || key.isBlank() || selfActorRef == null)
                ? null : keptUnder(key);
        String result = kept != null && !kept.isBlank() ? kept : lastResult;
        complete(result != null ? result : "(plan finished with no result)");
        return new ActionResult(true, "finished");
    }

    /**
     * What the plan stored under {@code key}, as the text to hand back.
     *
     * <p>A plan that collects things as it goes keeps them as a list, which is what
     * {@code appendJson} writes. Read as text a list answers the empty string, so a job that had
     * removed two actors reported that it had finished with no result. Anything that is not a
     * piece of text is handed back as its JSON.</p>
     *
     * @param key where in this plan's state to look
     * @return the text, or {@code null} when nothing is stored there
     */
    private String keptUnder(String key) {
        com.fasterxml.jackson.databind.JsonNode kept = selfActorRef.json().select(key);
        if (kept == null || kept.isMissingNode() || kept.isNull()) {
            return null;
        }
        return kept.isTextual() ? kept.asText() : kept.toString();
    }

    /**
     * Fallback step, for the same reason the babysitter workflows have one: a failed step would
     * otherwise leave its state with no transition left to try, and whoever is waiting would wait
     * until their own timeout instead of being told what happened. Says what the last failed
     * transition answered.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult reportFailure() {
        complete("(plan stopped: " + (lastFailure != null ? lastFailure : "no step failed") + ")");
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
