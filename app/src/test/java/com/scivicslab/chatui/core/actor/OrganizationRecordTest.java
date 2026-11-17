package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's organization records itself ({@code EachActorRecordsItsOwnState_261118_oo01}, step 3): each change
 * writes the project's {@code organization} entry, and {@link Organization#restore} reads the last one back, or the
 * organization a {@code settings} entry of the earlier code carried when there is no {@code organization} entry yet.
 */
class OrganizationRecordTest {

    private static final List<String> ALL = List.of("01", "02", "03", "04");

    @TempDir Path dir;
    private IoLogStore log;
    private IoLogView view;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("organization").toString());
        view = IoLogView.over(log);
    }

    @AfterEach
    void close() throws Exception {
        log.store().close();
    }

    private SessionEntries project() {
        return SessionEntries.ofProject(log, view, "project1");
    }

    @Test
    void everyChangeIsRecordedAndAnotherOrganizationRestoresIt() {
        Organization first = new Organization(project());
        assertNull(first.setSuperior("02", "01"));
        assertNull(first.setSuperior("03", "02"));
        assertNull(first.setSuperior("04", "02"));
        assertNull(first.setMaxSubordinates(3));
        assertNull(first.setLiaison("04"));
        first.remove("03");

        Organization second = new Organization(project());
        second.restore(ALL);
        assertEquals(first.toJson().toString(), second.toJson().toString());
        assertEquals("04", second.liaison());
        assertNull(second.superiorOf("04"), "the liaison lost its superior");
        assertNull(second.superiorOf("03"), "the removal was recorded");
        assertEquals(3, second.maxSubordinates());
    }

    @Test
    void aRefusedChangeWritesNothing() {
        Organization first = new Organization(project());
        assertNull(first.setSuperior("02", "01"));
        String before = project().latest(Organization.ORGANIZATION_LABEL);
        assertNotNull(first.setSuperior("01", "02"), "the liaison has no superior");
        assertNotNull(first.setMaxSubordinates(0));
        assertEquals(before, project().latest(Organization.ORGANIZATION_LABEL));
    }

    @Test
    void anEarlierLogIsReadFromTheSettingsEntryAndThenWrittenAsTheOrganizationsOwn() {
        // Exactly the shape ProjectSettings wrote, followed by a later settings entry without the organization.
        project().write(IoLogView.SETTINGS_LABEL, "{\"name\":\"n\",\"workingDir\":null,"
                + "\"organization\":{\"superiors\":{\"02\":\"01\",\"03\":\"02\"},\"liaison\":\"01\",\"maxSubordinates\":4},"
                + "\"roles\":[]}");
        project().write(IoLogView.SETTINGS_LABEL, "{\"name\":\"renamed\",\"workingDir\":null}");

        Organization o = new Organization(project());
        o.restore(ALL);
        assertEquals("02", o.superiorOf("03"));
        assertEquals(4, o.maxSubordinates());
        assertNotNull(project().latest(Organization.ORGANIZATION_LABEL), "written as its own entry at once");

        project().write(IoLogView.SETTINGS_LABEL, "{\"organization\":{\"superiors\":{},\"liaison\":\"03\"}}");
        Organization again = new Organization(project());
        again.restore(ALL);
        assertEquals(o.toJson().toString(), again.toJson().toString(), "the earlier form is not read again");
    }

    @Test
    void restoringForgetsConversationsThatDidNotComeBackAndRecordsThat() {
        Organization first = new Organization(project());
        first.setSuperior("02", "01");
        first.setSuperior("03", "02");
        first.setSuperior("04", "01");

        Organization second = new Organization(project());
        second.restore(List.of("01", "02", "04"));
        assertNull(second.superiorOf("03"));
        assertEquals("01", second.superiorOf("04"));

        Organization third = new Organization(project());
        third.restore(ALL);
        assertNull(third.superiorOf("03"), "the drop was recorded");
    }

    @Test
    void nothingRecordedLeavesTheDefaults() {
        Organization o = new Organization(project());
        o.restore(ALL);
        assertEquals(Organization.DEFAULT_LIAISON, o.liaison());
        assertEquals(Organization.DEFAULT_MAX_SUBORDINATES, o.maxSubordinates());
        assertNull(project().latest(Organization.ORGANIZATION_LABEL), "restoring nothing writes nothing");
    }
}
