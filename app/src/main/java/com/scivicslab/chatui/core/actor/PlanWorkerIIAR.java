package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@code IIActorRef} wrapper for a {@link PlanWorker}, so {@code Interpreter.apply} can reach it —
 * {@code apply} resolves each matched child through {@code getIIActor}, which only finds actors
 * registered on that side ({@code ParallelWorkerPool_260829_oo01}).
 */
public class PlanWorkerIIAR extends IIActorRef<PlanWorker> {

    public PlanWorkerIIAR(String actorName, PlanWorker worker, IIActorSystem system) {
        super(actorName, worker, system);
    }

    /** @return the slot this wrapper holds; its {@code lastReply} is volatile, so safe to read directly */
    public PlanWorker worker() { return object; }

    /**
     * Sends a prompt to this slot's conversation and waits for the reply; the message is the reply.
     *
     * <p>Reached through {@code apply} on the plan's slots, so several conversations answer the same
     * prompt at once; {@code collectWorkerReplies} then gathers the replies. Fails when the
     * conversation did not reply.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: apply
     *   arguments:
     *     actor: "*.worker-*"
     *     method: ask
     *     arguments: ["Explain, in about 80 words, what makes actor-model concurrency different from locks."]
     * }</pre>
     *
     * @param args {@code [prompt]}: what to ask
     */
    @Action("ask")
    public ActionResult ask(String args) {
        org.json.JSONArray a;
        try {
            a = (args == null || args.isBlank()) ? new org.json.JSONArray() : new org.json.JSONArray(args);
        } catch (Exception e) {
            a = new org.json.JSONArray();
        }
        return worker().ask(a.length() > 0 ? a.getString(0) : "");
    }
}
