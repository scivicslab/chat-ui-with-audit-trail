package com.scivicslab.chatui.core.actor;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

/**
 * What a conversation was created with and the agent loop put on it, as the I/O log keeps them across a restart
 * ({@code ConversationRestoreOnRestart_260904_oo01}). Which conversations a project has is the project's own list
 * ({@code EachActorRecordsItsOwnState_261118_oo01}); what is left here moves to the conversation's own actors next.
 *
 * <p>A conversation comes back when the log holds a running session for it. Before, a conversation got a session
 * only when it ran a turn or one of its settings was recorded, so one made by {@code create_chat} or REST and given
 * only a superior and a role came back as nothing, and both lines with it. {@link #created} opens its session at
 * once, with the settings it was created with.</p>
 *
 * <p>A plain object. The actor system holds one as the actor {@code housekeeper.conversationRegistry}, and the
 * rest of the application reaches it only by {@code tell}/{@code ask} on that actor. It writes to and reads from
 * the same log every other setting uses; it keeps nothing of its own.</p>
 */
public class ConversationRegistry {

    private static final Logger LOG = Logger.getLogger(ConversationRegistry.class.getName());

    /** Prefix of an agent-loop record that names a workflow file under {@code /workflows/}. */
    public static final String AGENT_LOOP_FILE = "file:";
    /** Prefix of an agent-loop record that holds the YAML itself, as {@code set_agent_loop} gave it. */
    public static final String AGENT_LOOP_YAML = "yaml:\n";

    private final IoLogStore log;
    private final IoLogView view;

    /**
     * @param log  the I/O log, or {@code null} when logging is off (then nothing is kept across a restart)
     * @param view what reads the log back, or {@code null} likewise
     */
    public ConversationRegistry(IoLogStore log, IoLogView view) {
        this.log = log;
        this.view = view;
    }

    /**
     * Records a conversation that has just been created: opens its session and writes the settings it was created
     * with, in the same {@code settings} entry a later provider or model change writes. A conversation the log
     * already holds a running session for is being brought back, and is left as recorded: writing here would put
     * its provider's default model over the one it was on.
     *
     * @param name     the conversation's qualified name, e.g. {@code "project1/chat-02"}
     * @param provider its provider kind
     * @param tools    its tool set's id
     * @param model    its model, or {@code null} when none is settled yet
     */
    public void created(String name, String provider, String tools, String model) {
        if (log == null || log.findResumableSession(name) >= 0) return;
        long session = log.ensureSession(name);
        if (session < 0) return;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("provider", provider == null ? org.json.JSONObject.NULL : provider);
        o.put("tools", tools == null ? org.json.JSONObject.NULL : tools);
        o.put("model", model == null ? org.json.JSONObject.NULL : model);
        log.record(session, "agent", IoLogView.SETTINGS_LABEL, o.toString());
    }

    /**
     * Records the agent loop put on a conversation; the last one recorded is the one a restart puts back.
     *
     * @param name  the conversation's qualified name
     * @param entry {@link #AGENT_LOOP_FILE} and a file name, or {@link #AGENT_LOOP_YAML} and the YAML
     */
    public void agentLoopSet(String name, String entry) {
        if (log == null) return;
        long session = log.ensureSession(name);
        if (session >= 0) log.record(session, "agent", IoLogView.AGENT_LOOP_LABEL, entry);
    }

    /** @return the last agent loop recorded for the conversation, or {@code null} when none was */
    public String agentLoopOf(String name) {
        if (log == null || view == null) return null;
        long session = log.findResumableSession(name);
        if (session < 0) return null;
        try {
            return view.latestAgentLoop(session);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the agent loop of " + name, e);
            return null;
        }
    }
}
