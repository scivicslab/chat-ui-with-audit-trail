package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

/**
 * A project's recorded settings, as the I/O log keeps them across a restart: its name, its working directory, its
 * organization (who is whose superior, {@code ConversationReportingLines_261108_oo01}) and the role edges whose
 * source is one of its conversations ({@code CollaborationGraph_260828_oo01}). One job: write them as one entry
 * of the project's session, and read the last such entry back ({@code ProjectProperty_260929_oo01}).
 *
 * <p>All four travel in one entry because the last entry of the session is the current value: an entry carrying
 * only some of them would read back as a project whose other settings had been cleared. The values themselves
 * live in their own actors (the {@code Project}, its {@code Organization}, the {@code CollaborationGraph}); this
 * object only writes and reads what they hold, in the entry shape the earlier code wrote, so logs written before
 * it existed read the same.</p>
 *
 * <p>A plain object. The actor system holds one as the actor {@code housekeeper.projectSettings}, and the rest of
 * the application reaches it only by {@code tell}/{@code ask} on that actor.</p>
 */
public class ProjectSettings {

    private static final Logger LOG = Logger.getLogger(ProjectSettings.class.getName());

    /**
     * One project's settings as one entry holds them.
     *
     * @param name         the name a person gave the project, or {@code null}
     * @param workingDir   its working directory, or {@code null}
     * @param organization the organization as {@code Organization.toJson} gives it, or {@code null} when the entry
     *                     holds none (an entry written before the organization was kept)
     * @param roles        the role edges whose source is in the project, or {@code null} when the entry holds none
     *                     (an entry written before role edges were kept)
     */
    public record Snapshot(String name, String workingDir, org.json.JSONObject organization,
                           List<CollaborationGraph.Edge> roles) {}

    private final IoLogStore log;
    private final IoLogView view;

    /**
     * @param log  the I/O log, or {@code null} when logging is off (then nothing is kept across a restart)
     * @param view what reads the log back, or {@code null} likewise
     */
    public ProjectSettings(IoLogStore log, IoLogView view) {
        this.log = log;
        this.view = view;
    }

    /** Writes the project's settings as one entry; the next {@link #read} returns them. */
    public void record(String projectId, Snapshot s) {
        if (log == null) return;
        long session = log.ensureProjectSession(projectId);
        if (session < 0) return;
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("name", s.name() == null ? org.json.JSONObject.NULL : s.name());
        o.put("workingDir", s.workingDir() == null ? org.json.JSONObject.NULL : s.workingDir());
        o.put("organization", s.organization() == null ? new org.json.JSONObject() : s.organization());
        org.json.JSONArray roles = new org.json.JSONArray();
        for (CollaborationGraph.Edge e : s.roles() == null ? List.<CollaborationGraph.Edge>of() : s.roles()) {
            roles.put(new org.json.JSONObject().put("from", e.from()).put("role", e.role()).put("to", e.to()));
        }
        o.put("roles", roles);
        log.record(session, "agent", IoLogView.SETTINGS_LABEL, o.toString());
    }

    /** @return the project's last recorded settings, or {@code null} when nothing was recorded for it */
    public Snapshot read(String projectId) {
        if (log == null || view == null) return null;
        long session = log.findProjectSession(projectId);
        if (session < 0) return null;
        String json;
        try {
            json = view.latestSettingsJson(session);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the settings of " + projectId, e);
            return null;
        }
        if (json == null) return null;
        org.json.JSONObject o = new org.json.JSONObject(json);
        String name = o.isNull("name") ? null : o.optString("name", null);
        String dir = o.isNull("workingDir") ? null : o.optString("workingDir", null);
        List<CollaborationGraph.Edge> roles = null;
        org.json.JSONArray saved = o.optJSONArray("roles");
        if (saved != null) {
            roles = new ArrayList<>();
            for (int i = 0; i < saved.length(); i++) {
                org.json.JSONObject e = saved.optJSONObject(i);
                if (e == null) continue;
                String from = e.optString("from", null), role = e.optString("role", null), to = e.optString("to", null);
                if (from != null && role != null && to != null) roles.add(new CollaborationGraph.Edge(from, role, to));
            }
        }
        return new Snapshot(name, dir, o.optJSONObject("organization"), roles);
    }
}
