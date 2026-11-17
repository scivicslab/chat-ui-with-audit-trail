package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project keeps the list of its conversations, each with the conversation it is nested under, as its own
 * {@code conversations} entry, and says at start-up which to build again, in order, under which parent
 * ({@code EachActorRecordsItsOwnState_261118_oo01}, step 5).
 */
class ProjectConversationsTest {

    @TempDir Path dir;
    private IoLogStore log;
    private IoLogView view;

    /** What the builder was asked to build, as "id<parent" or "id". */
    private final List<String> built = new ArrayList<>();

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("conversations").toString());
        view = IoLogView.over(log);
    }

    @AfterEach
    void close() throws Exception {
        log.store().close();
    }

    private Project project() {
        Project p = new Project();
        p.bind("project1", null, null, null, log);
        p.recordsTo(SessionEntries.ofProject(log, view, "project1"));
        return p;
    }

    /** What ChatUiActorSystem does at start-up: asks what to build, builds it, and says what was built. */
    private void restore(Project p, List<String> running) {
        List<String> ok = new ArrayList<>();
        for (Project.Member m : p.conversationsToBuild(running)) {
            if ("bad".equals(m.chatId())) continue;
            if (m.parentChatId() != null && !ok.contains(m.parentChatId())) continue;
            built.add(m.parentChatId() == null ? m.chatId() : m.chatId() + "<" + m.parentChatId());
            ok.add(m.chatId());
        }
        p.conversationsBuilt(ok);
    }

    @Test
    void theListWithItsNestingIsRecordedAndBuiltAgainInOrder() {
        Project first = project();
        first.conversationAdded("01", null);
        first.conversationAdded("02", null);
        first.conversationAdded("03", "02");
        first.conversationAdded("03", "02");

        Project second = project();
        restore(second, List.of());
        assertEquals(List.of("01", "02", "03<02"), built, "parents before the conversations under them, each once");
        assertEquals(List.of(new Project.Member("01", null), new Project.Member("02", null), new Project.Member("03", "02")),
                second.conversations());
    }

    @Test
    void aRemovedConversationIsLeftOutAndItsSessionEnded() {
        log.ensureSession("project1/chat-02");
        Project first = project();
        first.conversationAdded("01", null);
        first.conversationAdded("02", null);
        first.conversationRemoved("02");
        assertTrue(log.findResumableSession("project1/chat-02") < 0, "its session was ended, its name stays taken");
        assertTrue(log.recordedConversationSquads().contains("project1/chat-02"));

        restore(project(), List.of());
        assertEquals(List.of("01"), built);
    }

    @Test
    void withNoListTheRunningSessionsAreBuiltAndThenRecordedAsTheList() {
        Project first = project();
        restore(first, List.of("01", "02", "05"));
        assertEquals(List.of("01", "02", "05"), built, "the earlier way: every conversation with a running session, flat");

        built.clear();
        restore(project(), List.of("01", "09"));
        assertEquals(List.of("01", "02", "05"), built, "the list was recorded, so the running sessions are not read again");
    }

    @Test
    void oneThatCannotBeBuiltIsDroppedAndThatRecorded() {
        Project first = project();
        first.conversationAdded("01", null);
        first.conversationAdded("bad", null);
        first.conversationAdded("03", null);

        restore(project(), List.of());
        assertEquals(List.of("01", "03"), built);
        built.clear();
        restore(project(), List.of());
        assertEquals(List.of("01", "03"), built, "the drop was recorded");
    }

    @Test
    void endingEveryConversationEndsTheirSessionsAndEmptiesTheList() {
        log.ensureSession("project1/chat-01");
        log.ensureSession("project1/chat-02");
        Project first = project();
        first.conversationAdded("01", null);
        first.conversationAdded("02", "01");
        first.endEveryConversation();
        assertTrue(log.findResumableSession("project1/chat-01") < 0 && log.findResumableSession("project1/chat-02") < 0);
        restore(project(), List.of());
        assertEquals(List.of(), built);
    }
}
