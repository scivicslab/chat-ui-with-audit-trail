package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActionResult;
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

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        if (actionName.equals("ask")) {
            org.json.JSONArray a;
            try {
                a = (arg == null || arg.isBlank()) ? new org.json.JSONArray() : new org.json.JSONArray(arg);
            } catch (Exception e) {
                a = new org.json.JSONArray();
            }
            return worker().ask(a.length() > 0 ? a.getString(0) : "");
        }
        return new ActionResult(false, "unknown action: " + actionName);
    }
}
