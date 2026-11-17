package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project records its own name and working directory as its {@code settings} entry, with the label and keys the
 * earlier code wrote, and puts them back from the last one ({@code EachActorRecordsItsOwnState_261118_oo01}, step 4;
 * {@code ProjectProperty_260929_oo01}).
 */
class ProjectRecordTest {

    @TempDir Path dir;
    private IoLogStore log;
    private IoLogView view;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("project").toString());
        view = IoLogView.over(log);
    }

    @AfterEach
    void close() throws Exception {
        log.store().close();
    }

    private SessionEntries entries() {
        return SessionEntries.ofProject(log, view, "project1");
    }

    private Project recording() {
        Project p = new Project();
        p.recordsTo(entries());
        return p;
    }

    private int settingsEntries() {
        long session = log.findProjectSession("project1");
        return session < 0 ? 0 : view.settingsHistory(session).size();
    }

    @Test
    void theNameAndTheWorkingDirectoryAreRecordedTogetherAndRestored() throws Exception {
        Path work = Files.createDirectory(dir.resolve("work"));
        Files.writeString(work.resolve(Project.AGENTS_FILE), "Follow the house rules.");
        Project first = recording();
        first.setName("Bench");
        first.setWorkingDir(work);

        org.json.JSONObject entry = new org.json.JSONObject(entries().latest(IoLogView.SETTINGS_LABEL));
        assertEquals("Bench", entry.getString("name"));
        assertEquals(work.toString(), entry.getString("workingDir"));

        Project second = recording();
        second.restore();
        assertEquals("Bench", second.getName());
        assertEquals(work, second.getWorkingDir());
        assertEquals("Follow the house rules.", second.getInstructions(), "the instructions are read again from the directory");
    }

    @Test
    void onlyAChangeIsRecorded() throws Exception {
        Path work = Files.createDirectory(dir.resolve("work"));
        Project p = recording();
        p.setName("A");
        p.setWorkingDir(work);
        assertEquals(2, settingsEntries());
        p.setName("A");
        p.setWorkingDir(work);
        p.reloadInstructions();
        assertEquals(2, settingsEntries(), "the same values, or reading the instructions again, write nothing");
        p.setName("  ");
        assertEquals(3, settingsEntries());
        assertTrue(new org.json.JSONObject(entries().latest(IoLogView.SETTINGS_LABEL)).isNull("name"),
                "a cleared name is recorded as cleared");
    }

    @Test
    void restoringWritesNothing() throws Exception {
        Project first = recording();
        first.setName("Kept");
        Project second = recording();
        second.restore();
        second.restore();
        assertEquals(1, settingsEntries(), "a restart does not append an entry repeating the last one");
    }

    @Test
    void anEntryOfTheEarlierCodeIsReadAsItWas() {
        // Exactly the JSON ChatUiActorSystem.recordProjectProperties wrote before, organization and roles included.
        entries().write(IoLogView.SETTINGS_LABEL, "{\"name\":\"old\",\"workingDir\":null,"
                + "\"organization\":{\"superiors\":{\"03\":\"02\"},\"liaison\":\"01\",\"maxSubordinates\":4},"
                + "\"roles\":[{\"from\":\"project1/chat-02\",\"role\":\"reviewer\",\"to\":\"project1/chat-03\"}]}");
        Project p = recording();
        p.restore();
        assertEquals("old", p.getName());
        assertNull(p.getWorkingDir());
    }

    @Test
    void nothingRecordedLeavesTheProjectAsItIs() {
        Project p = recording();
        p.restore();
        assertNull(p.getName());
        assertNull(p.getWorkingDir());
        assertEquals(0, settingsEntries());
    }

    @Test
    void aProjectThatRecordsNowhereStillWorks() {
        Project p = new Project();
        p.setName("x");
        p.restore();
        assertEquals("x", p.getName());
    }
}
