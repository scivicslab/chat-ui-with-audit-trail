package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One project's role table ({@code EachActorRecordsItsOwnState_261118_oo01}, step 2): the role edges whose source is
 * one of the project's conversations, written by the table itself as the project's {@code roles} entry and read back
 * from it, or from the {@code settings} entry the earlier code wrote when there is no {@code roles} entry yet.
 */
class RoleTableTest {

    private static final Set<String> ALL = Set.of("project1/chat-01", "project1/chat-02", "project1/chat-03",
            "project2/chat-05");

    private static RoleTable offline() {
        return new RoleTable("project1", SessionEntries.ofProject(null, null, "project1"));
    }

    private static IoLogStore open(Path dir) {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("roles").toString());
        return log;
    }

    @Test
    void anEdgeIsSetLookedUpAndReassigned() {
        RoleTable t = offline();
        assertNull(t.set("project1/chat-02", "worker", "project1/chat-03"));
        assertNull(t.set("project1/chat-02", "auditor", "project2/chat-05"));
        assertEquals("project1/chat-03", t.collaborator("project1/chat-02", "worker"));
        assertNull(t.set("project1/chat-02", "worker", "project1/chat-01"));
        assertEquals("project1/chat-01", t.collaborator("project1/chat-02", "worker"), "reassigned, not added");
        assertEquals(Map.of("worker", "project1/chat-01", "auditor", "project2/chat-05"), t.collaboratorsOf("project1/chat-02"));
        assertNull(t.collaborator("project1/chat-03", "worker"));
        assertEquals(Map.of(), t.collaboratorsOf("project1/chat-03"));
    }

    @Test
    void anEdgeWhoseSourceIsInAnotherProjectIsRefused() {
        RoleTable t = offline();
        assertNotNull(t.set("project2/chat-01", "worker", "project1/chat-02"));
        assertNotNull(t.set("project10/chat-01", "worker", "project1/chat-02"), "project10 is not project1");
        assertTrue(t.edges().isEmpty());
    }

    @Test
    void theEdgesComeSortedBySourceThenRole() {
        RoleTable t = offline();
        t.set("project1/chat-02", "worker", "project1/chat-03");
        t.set("project1/chat-02", "reviewer", "project1/chat-01");
        t.set("project1/chat-01", "worker", "project1/chat-02");
        assertEquals(List.of(
                new RoleTable.Edge("project1/chat-01", "worker", "project1/chat-02"),
                new RoleTable.Edge("project1/chat-02", "reviewer", "project1/chat-01"),
                new RoleTable.Edge("project1/chat-02", "worker", "project1/chat-03")), t.edges());
    }

    @Test
    void forgettingAConversationOrAProjectDropsTheEdgesFromAndToIt() {
        RoleTable t = offline();
        t.set("project1/chat-01", "worker", "project1/chat-02");
        t.set("project1/chat-02", "helper", "project1/chat-03");
        t.set("project1/chat-03", "reviewer", "project1/chat-01");
        t.set("project1/chat-01", "auditor", "project2/chat-05");
        assertTrue(t.forgetConversation("project1/chat-03"));
        assertEquals(List.of(
                new RoleTable.Edge("project1/chat-01", "auditor", "project2/chat-05"),
                new RoleTable.Edge("project1/chat-01", "worker", "project1/chat-02")), t.edges());
        assertFalse(t.forgetConversation("project1/chat-09"), "nothing named it");
        assertTrue(t.forgetProject("project2/"));
        assertEquals(List.of(new RoleTable.Edge("project1/chat-01", "worker", "project1/chat-02")), t.edges());
        assertFalse(t.forgetProject("project20/"));
    }

    @Test
    void theTableWritesItsOwnEntryAndAnotherTableReadsItBack(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            IoLogView view = IoLogView.over(log);
            RoleTable first = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            first.set("project1/chat-02", "worker", "project1/chat-03");
            first.set("project1/chat-02", "auditor", "project2/chat-05");
            assertNotNull(SessionEntries.ofProject(log, view, "project1").latest(RoleTable.ROLES_LABEL),
                    "a set writes the project's roles entry");

            RoleTable second = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            second.restore(ALL);
            assertEquals(first.edges(), second.edges());
            assertNull(new RoleTable("project2", SessionEntries.ofProject(log, view, "project2")).collaborator(
                    "project1/chat-02", "worker"), "another project's table holds none of them");
        } finally {
            log.store().close();
        }
    }

    @Test
    void anEarlierLogIsReadFromTheSettingsEntryAndThenWrittenAsTheTablesOwn(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            IoLogView view = IoLogView.over(log);
            SessionEntries project = SessionEntries.ofProject(log, view, "project1");
            // Exactly the shape ProjectSettings wrote: the roles with the name, directory and organization,
            // followed by a later settings entry that no longer carries them.
            project.write(IoLogView.SETTINGS_LABEL, "{\"name\":\"n\",\"workingDir\":null,\"organization\":{},"
                    + "\"roles\":[{\"from\":\"project1/chat-02\",\"role\":\"worker\",\"to\":\"project1/chat-03\"}]}");
            project.write(IoLogView.SETTINGS_LABEL, "{\"name\":\"renamed\",\"workingDir\":null}");

            RoleTable t = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            t.restore(ALL);
            assertEquals(List.of(new RoleTable.Edge("project1/chat-02", "worker", "project1/chat-03")), t.edges());
            assertNotNull(project.latest(RoleTable.ROLES_LABEL), "what was read from the earlier form is written as the table's own entry");

            // The earlier form is not read again once the table's own entry exists.
            project.write(IoLogView.SETTINGS_LABEL, "{\"roles\":[{\"from\":\"project1/chat-01\",\"role\":\"x\",\"to\":\"project1/chat-02\"}]}");
            RoleTable again = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            again.restore(ALL);
            assertEquals(t.edges(), again.edges());
        } finally {
            log.store().close();
        }
    }

    @Test
    void restoringDropsEdgesToOrFromConversationsThatDidNotComeBackAndRecordsThat(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            IoLogView view = IoLogView.over(log);
            RoleTable first = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            first.set("project1/chat-02", "worker", "project1/chat-03");
            first.set("project1/chat-01", "reviewer", "project1/chat-02");
            first.set("project1/chat-01", "auditor", "project2/chat-07");

            RoleTable second = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            second.restore(Set.of("project1/chat-01", "project1/chat-02", "project2/chat-05"));
            assertEquals(List.of(new RoleTable.Edge("project1/chat-01", "reviewer", "project1/chat-02")), second.edges());

            RoleTable third = new RoleTable("project1", SessionEntries.ofProject(log, view, "project1"));
            third.restore(ALL);
            assertEquals(second.edges(), third.edges(), "the drop was recorded, so chat-03 coming back later does not revive it");
        } finally {
            log.store().close();
        }
    }
}
