package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.CallWatchdog;
import com.scivicslab.chatui.core.actor.PlanRunner;
import com.scivicslab.chatui.core.actor.PlanRunnerIIAR;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code run_plan} tool: runs a workflow the calling conversation wrote, in a {@link PlanRunner}
 * actor of its own, and waits for its result
 * ({@code BabysitterRealisticE2eScenario_260828_oo01}, {@code PlanRunnerLifecycleManagement_260829_oo01}).
 *
 * <p>One runner per conversation, always named {@code <conversation>.plan} and reused: calling this
 * again resets it and loads the new plan. The reset is sent as an ordinary {@code tell}, so if the
 * previous plan is still running it simply queues behind it in the runner's own mailbox — no
 * "is it still running?" check is needed, and no {@code tellNow} is used, which would rewrite the
 * workflow out from under a run still in progress.</p>
 */
public final class RunPlanTool {

    private RunPlanTool() {}

    private static final Logger LOG = Logger.getLogger(RunPlanTool.class.getName());

    /** Long: a plan drives whole conversations, each running its own multi-step turn. */
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 1800;

    /**
     * @param system      the caller's actor system
     * @param watchdog    the shared {@link CallWatchdog}
     * @param myChatName  the calling conversation's qualified name, e.g. {@code project1/chat-01}
     * @param yaml        the plan, as Turing-workflow YAML text
     * @param timeoutSeconds how long to wait for the plan, or {@code null} for the default
     * @return the plan's result, or an {@code error: ...} string
     */
    public static String runPlan(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                  String myChatName, String yaml, Integer timeoutSeconds) {
        if (yaml == null || yaml.isBlank()) return "error: yaml is required";
        int waitSeconds = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds : DEFAULT_WAIT_TIMEOUT_SECONDS;
        String planName = myChatName + ".plan";

        PlanRunnerIIAR planIIAR = runnerFor(system, watchdog, myChatName, planName);
        if (planIIAR == null) return "error: " + planName + " exists but is not a plan runner";

        // The caller waits on its own plan, so record it: a plan step that asks the caller back
        // would otherwise be an undetectable circular wait (AskChatToolAndWatchdog_260827_oo01).
        boolean allowed;
        try {
            allowed = watchdog.ask(w -> w.beginWait(myChatName, planName)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "run_plan: watchdog check failed", e);
            return "error: watchdog check failed: " + e.getMessage();
        }
        if (!allowed) return "error: refused — running this plan would create a circular wait";

        try {
            CompletableFuture<String> done = start(planIIAR, planName, yaml);
            try {
                return done.get(waitSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // The runner keeps going; the next run_plan queues behind it.
                return "error: plan did not finish within " + waitSeconds + "s";
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "run_plan: failed for " + planName, e);
            return "error: run_plan failed: " + e.getMessage();
        } finally {
            watchdog.tell(w -> w.endWait(myChatName));
        }
    }

    /**
     * Submits a plan without waiting for it — for a plan handed in from outside rather than written
     * by a conversation's own LLM ({@code DirectPlanSubmission_260830_oo01}). Nobody is blocked on
     * the result, so it is written to the conversation's log when it arrives, and no wait is
     * recorded with the {@link CallWatchdog} (there is no waiter to deadlock).
     *
     * @param system     the actor system
     * @param watchdog   the shared {@link CallWatchdog}, for the plan's own {@code askChat} steps
     * @param myChatName the owning conversation's qualified name
     * @param yaml       the plan, as Turing-workflow YAML text
     * @param onResult   called with the plan's result once it finishes
     * @return {@code null} on success, or an {@code error: ...} string if it could not be started
     */
    public static String submit(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                 String myChatName, String yaml,
                                 java.util.function.Consumer<String> onResult) {
        if (yaml == null || yaml.isBlank()) return "error: yaml is required";
        String planName = myChatName + ".plan";
        PlanRunnerIIAR planIIAR = runnerFor(system, watchdog, myChatName, planName);
        if (planIIAR == null) return "error: " + planName + " exists but is not a plan runner";
        start(planIIAR, planName, yaml).whenComplete((result, error) -> {
            if (error != null) {
                LOG.log(Level.WARNING, "plan " + planName + " ended in error", error);
                onResult.accept("(plan failed: " + error + ")");
            } else {
                onResult.accept(result);
            }
        });
        return null;
    }

    /**
     * Returns the conversation's plan runner, creating it on first use. One per conversation, always
     * named {@code <conversation>.plan} ({@code PlanRunnerLifecycleManagement_260829_oo01}).
     *
     * @return the runner, or {@code null} if that name is taken by something that is not one
     */
    private static PlanRunnerIIAR runnerFor(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                                             String myChatName, String planName) {
        IIActorRef<?> existing = system.getIIActor(planName);
        if (existing instanceof PlanRunnerIIAR found) return found;
        if (existing != null) return null;
        PlanRunnerIIAR planIIAR =
                new PlanRunnerIIAR(planName, new PlanRunner(planName, system, watchdog), system);
        planIIAR.setParentName(myChatName);
        // The owning conversation is a plain ConversationTab actor, registered among `actors`, not
        // `iiActors` — looking it up with getIIActor finds nothing, and the plan would run correctly
        // but never appear under its conversation in the Actors tree.
        ActorRef<?> owner = system.getActor(myChatName);
        if (owner != null) owner.getNamesOfChildren().add(planName);
        system.addIIActor(planIIAR);
        return planIIAR;
    }

    /**
     * Resets the runner, loads {@code yaml} into it and runs it — all as one ordinary {@code tell},
     * so a plan still running simply queues ahead of this one
     * ({@code PlanRunnerLifecycleManagement_260829_oo01}).
     *
     * @return a future completed with the plan's outcome
     */
    private static CompletableFuture<String> start(PlanRunnerIIAR planIIAR, String planName, String yaml) {
        CompletableFuture<String> done = new CompletableFuture<>();
        planIIAR.tell(interp -> {
            PlanRunner runner = (PlanRunner) interp;
            runner.setDone(done);
            try {
                runner.clearStop();
                runner.reset();
                runner.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
                var result = runner.runUntilEnd();
                // Reaching "end" through finish/reportFailure already completed `done`; this covers
                // a plan that stopped some other way, so nobody is left waiting on a runner that is
                // no longer running.
                String outcome;
                if (runner.isStopRequested()) {
                    // runUntilEnd's loop condition includes !stopRequested, so a stop falls out of
                    // the loop and returns "Maximum iterations exceeded" — the wrong thing to tell
                    // whoever asked for the stop.
                    outcome = "(plan stopped by request after " + runner.getCurrentState() + ")";
                } else if (result.isSuccess()) {
                    outcome = runner.lastReply() != null ? runner.lastReply() : "(plan finished with no result)";
                } else {
                    outcome = "(plan did not finish: " + result.getResult() + ")";
                }
                runner.complete(outcome);
            } catch (Throwable t) {
                // The actor's message loop only catches Exception, so an Error thrown here would kill
                // this runner's thread silently, leaving whoever waits to wait out their full
                // timeout with nothing logged. Catch everything, report it, and release them.
                LOG.log(Level.WARNING, "plan " + planName + " failed", t);
                runner.complete("(plan failed: " + t + ")");
            }
        });
        return done;
    }
}
