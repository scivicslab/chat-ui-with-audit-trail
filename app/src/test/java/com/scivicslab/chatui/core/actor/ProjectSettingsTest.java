package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's recorded settings as a plain object, on a real I/O log, without an actor system
 * ({@code ProjectProperty_260929_oo01}, {@code ConversationReportingLines_261108_oo01}).
 */
class ProjectSettingsTest {

    @TempDir Path dir;
    private IoLogStore log;
    private ProjectSettings settings;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("project-settings-test").toString());
        settings = new ProjectSettings(log, IoLogView.over(log));
    }

    @AfterEach
    void close() throws Exception {
        log.store().close();
    }

    @Test
    void whatIsRecordedIsReadBack() {
        org.json.JSONObject org = new org.json.JSONObject()
                .put("superiors", new org.json.JSONObject().put("02", "01")).put("liaison", "01").put("maxSubordinates", 5);
        settings.record("project1", new ProjectSettings.Snapshot("my project", "/tmp/work", org));

        ProjectSettings.Snapshot s = settings.read("project1");
        assertEquals("my project", s.name());
        assertEquals("/tmp/work", s.workingDir());
        assertEquals("01", s.organization().getJSONObject("superiors").getString("02"));
        assertTrue(!new org.json.JSONObject(IoLogView.over(log).latestSettingsJson(log.findProjectSession("project1"))).has("roles"),
                "the role edges are the role table's to record, not this entry's");
    }

    @Test
    void theLastRecordIsTheCurrentOne() {
        settings.record("project1", new ProjectSettings.Snapshot("first", null, null));
        settings.record("project1", new ProjectSettings.Snapshot("second", null, null));
        assertEquals("second", settings.read("project1").name());
    }

    @Test
    void anEntryWrittenByTheEarlierCodeIsReadAsItWas() {
        // Exactly the JSON ChatUiActorSystem.recordProjectProperties wrote before this actor existed.
        long session = log.ensureProjectSession("project1");
        log.record(session, "agent", IoLogView.SETTINGS_LABEL,
                "{\"name\":\"old\",\"workingDir\":null,"
                        + "\"organization\":{\"superiors\":{\"03\":\"02\"},\"liaison\":\"01\",\"maxSubordinates\":4},"
                        + "\"roles\":[{\"from\":\"project1/chat-02\",\"role\":\"reviewer\",\"to\":\"project1/chat-03\"}]}");

        ProjectSettings.Snapshot s = settings.read("project1");
        assertEquals("old", s.name());
        assertNull(s.workingDir());
        assertEquals(4, s.organization().getInt("maxSubordinates"));
        // Its role edges are read by the project's role table (RoleTableTest).
    }

    @Test
    void anOlderEntryWithoutOrganizationReadsItAsAbsent() {
        long session = log.ensureProjectSession("project1");
        log.record(session, "agent", IoLogView.SETTINGS_LABEL, "{\"name\":\"older\",\"workingDir\":\"/w\"}");

        ProjectSettings.Snapshot s = settings.read("project1");
        assertEquals("older", s.name());
        assertEquals("/w", s.workingDir());
        assertNull(s.organization(), "no organization saved: leave the one in memory as it is");
    }

    @Test
    void aProjectWithNothingRecordedReadsAsNull() {
        assertNull(settings.read("project7"));
    }
}
