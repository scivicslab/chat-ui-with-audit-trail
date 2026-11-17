package com.scivicslab.chatui.core.iolog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The record port an owner of state is given ({@code EachActorRecordsItsOwnState_261118_oo01}, step 1): it writes one
 * entry to the owner's own session, and reads back the last entry of a label, or the last entry of a label whose JSON
 * holds a given key. Run on a real I/O log in a temporary directory, closed and opened again as a restart does.
 */
class SessionEntriesTest {

    private static IoLogStore open(Path dir) {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("entries").toString());
        return log;
    }

    @Test
    void theLastEntryOfALabelIsTheValueAndOtherLabelsDoNotDisturbIt(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            SessionEntries chat = SessionEntries.ofConversation(log, IoLogView.over(log), "project1/chat-02");
            chat.write("settings", "{\"model\":\"a\"}");
            chat.write("name", "first");
            chat.write("settings", "{\"model\":\"b\"}");
            chat.write("name", "second");
            assertEquals("{\"model\":\"b\"}", chat.latest("settings"));
            assertEquals("second", chat.latest("name"));
        } finally {
            log.store().close();
        }
    }

    @Test
    void theLastEntryHoldingAKeyIsFoundBehindLaterEntriesWithoutIt(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            SessionEntries project = SessionEntries.ofProject(log, IoLogView.over(log), "project1");
            project.write("settings", "{\"name\":\"old\",\"organization\":{\"liaison\":\"01\"}}");
            project.write("settings", "{\"name\":\"older form gone\",\"organization\":{\"liaison\":\"02\"}}");
            project.write("settings", "{\"name\":\"new\"}");
            project.write("settings", "not json");
            assertEquals("02", project.latestWithKey("settings", "organization").getJSONObject("organization").getString("liaison"),
                    "the last entry that holds the key, not the last entry");
            assertEquals("new", project.latestWithKey("settings", "name").getString("name"),
                    "an entry that is not JSON is passed over");
            assertEquals("not json", project.latest("settings"), "latest is the last entry as written");
        } finally {
            log.store().close();
        }
    }

    @Test
    void nothingWrittenReadsAsNull(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            SessionEntries never = SessionEntries.ofConversation(log, IoLogView.over(log), "project1/chat-09");
            assertNull(never.latest("settings"), "no session at all");
            assertNull(never.latestWithKey("settings", "model"));
            SessionEntries chat = SessionEntries.ofConversation(log, IoLogView.over(log), "project1/chat-02");
            chat.write("name", "only a name");
            assertNull(chat.latest("settings"), "a session without that label");
            assertNull(chat.latestWithKey("name", "x"), "an entry that is not a JSON object has no key");
            chat.write("settings", "{\"model\":\"a\"}");
            assertNull(chat.latestWithKey("settings", "effort"), "no entry holds the key");
        } finally {
            log.store().close();
        }
    }

    @Test
    void eachOwnerReadsOnlyItsOwnSession(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir);
        try {
            IoLogView view = IoLogView.over(log);
            SessionEntries project = SessionEntries.ofProject(log, view, "project1");
            SessionEntries one = SessionEntries.ofConversation(log, view, "project1/chat-01");
            SessionEntries ten = SessionEntries.ofConversation(log, view, "project1/chat-10");
            project.write("settings", "{\"name\":\"P\"}");
            one.write("settings", "{\"model\":\"m1\"}");
            ten.write("settings", "{\"model\":\"m10\"}");
            assertEquals("{\"name\":\"P\"}", project.latest("settings"));
            assertEquals("{\"model\":\"m1\"}", one.latest("settings"));
            assertEquals("{\"model\":\"m10\"}", ten.latest("settings"));
            assertNull(SessionEntries.ofProject(log, view, "project2").latest("settings"));
        } finally {
            log.store().close();
        }
    }

    @Test
    void entriesAreReadBackAfterTheLogIsOpenedAgain(@TempDir Path dir) throws Exception {
        IoLogStore first = open(dir);
        SessionEntries.ofProject(first, IoLogView.over(first), "project1").write("organization", "{\"liaison\":\"01\"}");
        SessionEntries.ofConversation(first, IoLogView.over(first), "project1/chat-02").write("agent-loop", "file:a.yaml");
        first.store().close();

        IoLogStore second = open(dir);
        try {
            IoLogView view = IoLogView.over(second);
            assertEquals("{\"liaison\":\"01\"}", SessionEntries.ofProject(second, view, "project1").latest("organization"));
            SessionEntries chat = SessionEntries.ofConversation(second, view, "project1/chat-02");
            assertEquals("file:a.yaml", chat.latest("agent-loop"));
            chat.write("agent-loop", "file:b.yaml");
            assertEquals("file:b.yaml", chat.latest("agent-loop"), "the write continues the same session");
            assertEquals(1, second.recordedConversationSquads().size(), "no second session was opened");
        } finally {
            second.store().close();
        }
    }

    @Test
    void withLoggingOffWritesAreDroppedAndReadsAreNull() {
        SessionEntries off = SessionEntries.ofConversation(null, null, "project1/chat-02");
        off.write("settings", "{}");
        assertNull(off.latest("settings"));
        assertNull(off.latestWithKey("settings", "model"));
    }
}
