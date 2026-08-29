package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;

/**
 * {@code IIActorRef} adapter that lets a plan's workflow call its own {@link PlanRunner} by
 * {@code actor: this} — the same bridge {@link ChatSessionIIAR} provides for a conversation.
 */
public class PlanRunnerIIAR extends InterpreterIIAR {

    public PlanRunnerIIAR(String actorName, PlanRunner runner, IIActorSystem system) {
        super(actorName, runner, system);
        // Without this, "actor: this" falls through Interpreter.action()'s selfActorRef==null path
        // and looks for an actor literally named "this" (see ChatSessionIIAR's own note).
        runner.setSelfActorRef(this);
    }

    private PlanRunner runner() { return (PlanRunner) object; }

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        if (actionName.equals("askChat")) {
            org.json.JSONArray a = arguments(arg);
            String target = a.length() > 0 ? a.getString(0) : "";
            String prompt = a.length() > 1 ? a.getString(1) : "";
            return runner().askChat(target, prompt);
        } else if (actionName.equals("finish")) {
            return runner().finish();
        } else if (actionName.equals("reportFailure")) {
            return runner().reportFailure();
        }
        // reset / readYaml / runUntilEnd / requestStop etc. come from InterpreterIIAR itself.
        return super.callByActionName(actionName, arg);
    }

    /** An action's {@code arguments} as a JSON array, empty if it has none. */
    private static org.json.JSONArray arguments(String arg) {
        if (arg == null || arg.isBlank()) return new org.json.JSONArray();
        try {
            return new org.json.JSONArray(arg);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }
}
