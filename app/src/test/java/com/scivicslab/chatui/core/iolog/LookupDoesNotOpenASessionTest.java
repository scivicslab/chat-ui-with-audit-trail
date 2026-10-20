package com.scivicslab.chatui.core.iolog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Asking for a session that was not opened must not open it ({@code LookupThatCreates_260914_oo01}). */
@DisplayName("セッションを引く操作は、セッションを作らない")
class LookupDoesNotOpenASessionTest {

    @TempDir Path dir;
    private IoLogStore log;

    @BeforeEach
    void setUp() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("lookup-test").toString());
    }

    @AfterEach
    void tearDown() {
        log.shutdown();
    }

    @Test
    @DisplayName("開いていない会話・job を引くと -1 で、セッションは増えない")
    void lookingUpWhatWasNotOpened_answersMinusOneAndOpensNothing() {
        assertEquals(-1, log.sessionIdOf("project1/chat-01"));
        assertEquals(-1, log.jobSessionIdOf("project1/job-01"));

        assertEquals(0, log.store().listSessions(10).size(), "asking opened a session");
    }

    @Test
    @DisplayName("開いたあとは、同じ番号を引ける")
    void afterItWasOpened_theLookupFindsTheSameId() {
        long conversation = log.ensureSession("project1/chat-01");
        long job = log.ensureJobSession("project1/job-01");

        assertTrue(conversation >= 0 && job >= 0);
        assertEquals(conversation, log.sessionIdOf("project1/chat-01"));
        assertEquals(job, log.jobSessionIdOf("project1/job-01"));
        assertEquals(2, log.store().listSessions(10).size());
    }
}
