package com.scivicslab.chatui.logging;

import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONObject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An {@link Accumulator} that forwards every entry it receives to another actor's {@code
 * MultiplexerAccumulatorActor}-style {@code add} action, prefixing {@code source} with this tab's
 * id so the upstream (system-wide) view can still show which tab an entry came from. Used as a
 * per-tab {@code MultiplexerAccumulator}'s target to implement the "tab logging actor delegates to
 * the system logging actor" hierarchy ({@code 150_TabScopedLogging_260826_oo01}).
 */
public class ForwardingAccumulator implements Accumulator {

    private static final Logger LOG = Logger.getLogger(ForwardingAccumulator.class.getName());

    private final IIActorSystem system;
    private final String targetActorName;
    private final String tabId;

    public ForwardingAccumulator(IIActorSystem system, String targetActorName, String tabId) {
        this.system = system;
        this.targetActorName = targetActorName;
        this.tabId = tabId;
    }

    @Override
    public void add(String source, String type, String data) {
        IIActorRef<?> target = system.getIIActor(targetActorName);
        if (target == null) {
            return;
        }
        try {
            JSONObject args = new JSONObject();
            args.put("source", tabId + ":" + source);
            args.put("type", type);
            args.put("data", data);
            target.callByActionName("add", args.toString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to forward log entry to " + targetActorName, e);
        }
    }

    @Override
    public String getSummary() {
        return "ForwardingAccumulator -> " + targetActorName;
    }
}
