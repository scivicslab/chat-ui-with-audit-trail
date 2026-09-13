package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.Locale;

/**
 * The {@code loader} a job's workflow calls to bring in a Turing Workflow plugin, limited to the
 * plugins the instance was started with ({@code WorkflowPluginLoader_260913_oo01}).
 *
 * <p>Turing Workflow's own loader reads any jar a workflow names. Put in this program unchanged, it
 * would undo what {@code ProviderAndToolPlugins_260912_oo01} settled: what a process can do is
 * decided by the jars given at start-up, not by what something asks for later. A workflow is not
 * always written by a person — {@code run_plan} runs YAML an LLM wrote — so an unrestricted loader
 * would let a conversation reach code the instance was deliberately started without.</p>
 *
 * <p>Only {@code loadJar} is restricted. {@code createChild} and the rest work on what has already
 * been loaded, so they add nothing this class has not already allowed.</p>
 */
public class WorkflowPluginLoader extends DynamicActorLoaderIIAR {

    /** What {@code chat-ui.workflow-plugins} named, compared case-insensitively after trimming. */
    private final List<String> allowed;

    /**
     * @param actorName the name to register under, normally {@code loader}
     * @param system    the actor system the loaded actors are created in
     * @param allowed   the Maven coordinates or jar paths this loader may read; empty allows none
     */
    public WorkflowPluginLoader(String actorName, IIActorSystem system, List<String> allowed) {
        super(actorName, system);
        this.allowed = allowed == null ? List.of()
                : allowed.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    /** @return the plugins this loader may read, as configured */
    public List<String> allowed() {
        return allowed;
    }

    /**
     * @return whether {@code what} is one of the plugins this loader was started with
     */
    public boolean allows(String what) {
        return what != null && allowed.contains(what.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public ActionResult callByActionName(String actionName, String arg) {
        if (!"loadJar".equals(actionName)) {
            return super.callByActionName(actionName, arg);
        }
        String what = firstArgument(arg);
        if (!allows(what)) {
            return new ActionResult(false, "refused to load '" + what
                    + "': not in chat-ui.workflow-plugins " + allowed);
        }
        return super.callByActionName(actionName, arg);
    }

    /**
     * @param arg the action's arguments as the interpreter passes them: a JSON array, a JSON object
     *            with one value, or a bare string
     * @return the one plugin named, or {@code ""} when nothing was
     */
    private static String firstArgument(String arg) {
        if (arg == null || arg.isBlank()) return "";
        String text = arg.strip();
        try {
            if (text.startsWith("[")) {
                org.json.JSONArray a = new org.json.JSONArray(text);
                return a.isEmpty() ? "" : String.valueOf(a.get(0));
            }
            if (text.startsWith("{")) {
                org.json.JSONObject o = new org.json.JSONObject(text);
                return o.keys().hasNext() ? String.valueOf(o.get(o.keys().next())) : "";
            }
        } catch (RuntimeException e) {
            return text;
        }
        return text;
    }
}
