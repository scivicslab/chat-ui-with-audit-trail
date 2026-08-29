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

        PlanRunnerIIAR planIIAR;
        IIActorRef<?> existing = system.getIIActor(planName);
        if (existing instanceof PlanRunnerIIAR found) {
            planIIAR = found;
        } else if (existing != null) {
            return "error: " + planName + " exists but is not a plan runner";
        } else {
            planIIAR = new PlanRunnerIIAR(planName, new PlanRunner(planName, system, watchdog), system);
            planIIAR.setParentName(myChatName);
            // The owning conversation is a plain ConversationTab actor, registered among `actors`,
            // not `iiActors` — looking it up with getIIActor finds nothing, and the plan would run
            // correctly but never appear under its conversation in the Actors tree.
            ActorRef<?> owner = system.getActor(myChatName);
            if (owner != null) owner.getNamesOfChildren().add(planName);
            system.addIIActor(planIIAR);
        }

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
            CompletableFuture<String> done = new CompletableFuture<>();
            planIIAR.tell(interp -> {
                PlanRunner runner = (PlanRunner) interp;
                runner.setDone(done);
                try {
                    runner.clearStop();
                    runner.reset();
                    runner.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
                    var result = runner.runUntilEnd();
                    // Reaching "end" through finish/reportFailure already completed `done`; this
                    // covers a plan that stopped some other way, so the caller is never left
                    // waiting on a runner that is no longer running.
                    String outcome;
                    if (runner.isStopRequested()) {
                        // runUntilEnd's loop condition includes !stopRequested, so a stop falls out
                        // of the loop and returns "Maximum iterations exceeded" — the wrong thing to
                        // tell whoever asked for the stop.
                        outcome = "(plan stopped by request after " + runner.getCurrentState() + ")";
                    } else if (result.isSuccess()) {
                        outcome = runner.lastReply() != null ? runner.lastReply() : "(plan finished with no result)";
                    } else {
                        outcome = "(plan did not finish: " + result.getResult() + ")";
                    }
                    runner.complete(outcome);
                } catch (Throwable t) {
                    // The actor's message loop only catches Exception, so an Error thrown here would
                    // kill this runner's thread silently and leave the caller waiting for its full
                    // timeout with nothing logged. Catch everything, report it, and release the
                    // caller.
                    LOG.log(Level.WARNING, "run_plan: " + planName + " failed", t);
                    runner.complete("(plan failed: " + t + ")");
                }
            });
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
}
