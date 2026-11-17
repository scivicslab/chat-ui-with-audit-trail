package com.scivicslab.chatui.core.actor;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

/**
 * A project's recorded settings, as the I/O log keeps them across a restart: its name and its working directory.
 * One job: write them as one entry of the project's session, and read the last such entry back
 * ({@code ProjectProperty_260929_oo01}). The organization and the role edges, which an entry written before also
 * carried, are recorded by the project's {@link Organization} and {@link RoleTable} themselves
 * ({@code EachActorRecordsItsOwnState_261118_oo01}).
 *
 * <p>Both travel in one entry because the last entry of the session is the current value: an entry carrying
 * only one of them would read back as a project whose other setting had been cleared. The values themselves
 * live in the {@code Project}; this
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
     */
    public record Snapshot(String name, String workingDir) {}

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
        return new Snapshot(name, dir);
    }
}
