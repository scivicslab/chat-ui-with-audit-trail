package com.scivicslab.chatui.core.remote;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Tells the AI-workspace that runs this chat-ui instance that the instance is a node: its name, its kind
 * ({@value #KIND}), its actor port and its HTTP port, with {@code announceNode} on {@value #WORKSPACE_GATEWAY}
 * ({@code ActorNodesForWorkspaceAndPortal_261118_oo01}, decision 4, question 11). The AI-workspace is the one named in
 * {@code AI_WORKSPACE_NODE}; it is reached as the tool node its own {@code /api/nodes} lists, so this registers no
 * node of its own.
 *
 * <p>The AI-workspace takes an announcement only from an instance it sees READY, which may be a little after this
 * instance opened its HTTP port. So a refused announcement, or one that did not arrive, is sent again after
 * {@link #RETRY_MILLIS}; an accepted one, after {@link #REANNOUNCE_MILLIS}, which brings it back within a minute after
 * the AI-workspace restarts. Nothing is recorded.</p>
 *
 * <p>{@link #tick} runs as a message to this object's own actor, so its fields are read and written by one thread at a
 * time. That actor is not on the node's allow-list.</p>
 */
final class WorkspaceAnnouncer {

    private static final Logger LOG = Logger.getLogger(WorkspaceAnnouncer.class.getName());

    /** The environment variable AI-workspace sets for every tool it launches: {@code <node>@<host>:<actor port>}. */
    static final String ENV = "AI_WORKSPACE_NODE";
    /** The AI-workspace's gateway actor. */
    static final String WORKSPACE_GATEWAY = "ai-workspace-remote";
    /** This node's kind, as the node list shows it. */
    static final String KIND = "chat-ui";
    /** The wait before sending again an announcement that was refused or did not arrive. */
    static final long RETRY_MILLIS = 5_000;
    /** The wait before sending again an announcement that was accepted. */
    static final long REANNOUNCE_MILLIS = 60_000;
    /** How long one announcement may wait for its answer. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** This object's actor name in the node's actor system. */
    static final String ACTOR = "chat-ui-announcer";

    private static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9._-]+)@([^:@\\s]+):(\\d{1,5})");

    private final RemoteNodes remote;
    private final String workspace;
    private final String announcement;
    private final LongSupplier clockMillis;

    private boolean sentOnce;
    private long lastSent;
    private boolean accepted;
    private String lastOutcome = "";

    /**
     * @param remote      this instance's node
     * @param workspace   the AI-workspace's node name, from {@link #parse}
     * @param httpPort    this instance's HTTP port: the AI-workspace finds the instance by it
     * @param clockMillis the time now, in milliseconds
     */
    WorkspaceAnnouncer(RemoteNodes remote, String workspace, int httpPort, LongSupplier clockMillis) {
        this.remote = remote;
        this.workspace = workspace;
        this.clockMillis = clockMillis;
        this.announcement = new JSONObject().put("name", remote.myName()).put("kind", KIND)
                .put("actorPort", remote.actorPort()).put("httpPort", httpPort).toString();
    }

    /** @return the AI-workspace's node name in {@code <node>@<host>:<actor port>}, or empty when there is none or it is not that form */
    static Optional<String> parse(String aiWorkspaceNode) {
        if (aiWorkspaceNode == null) return Optional.empty();
        Matcher m = ADDRESS.matcher(aiWorkspaceNode.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * Announces this instance if it is time to: at the first call, {@link #RETRY_MILLIS} after a refusal or a failure,
     * and {@link #REANNOUNCE_MILLIS} after an acceptance. Never throws.
     *
     * @return whether it sent the announcement
     */
    boolean tick() {
        long now = clockMillis.getAsLong();
        if (sentOnce && now - lastSent < (accepted ? REANNOUNCE_MILLIS : RETRY_MILLIS)) return false;
        sentOnce = true;
        lastSent = now;
        String outcome;
        try {
            ActionResult r = remote.callTool(workspace, WORKSPACE_GATEWAY, "announceNode", announcement, TIMEOUT);
            JSONObject answer = r.isSuccess() ? new JSONObject(r.getResult()) : null;
            accepted = answer != null && answer.optBoolean("accepted", false);
            outcome = accepted ? "accepted" : "refused: " + (answer != null ? answer.optString("reason", "") : r.getResult());
        } catch (Exception e) {
            accepted = false;
            outcome = "not delivered: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        // One line each time the outcome changes, not one a minute.
        if (!outcome.equals(lastOutcome)) {
            LOG.info("Announcement to " + workspace + " " + outcome);
            lastOutcome = outcome;
        }
        return true;
    }

    /** @return whether the last announcement was accepted */
    boolean accepted() {
        return accepted;
    }
}
