package com.scivicslab.chatui.core.iolog;

import com.scivicslab.chatui.core.actor.Project;
import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;
import com.scivicslab.turingworkflow.plugins.logdb.SessionSummary;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a job did, kept where it can be found again ({@code JobRunsInTheLog_260915_oo01}).
 *
 * <p>A job's lines went to a ring buffer for the Job Log tab and to another for the system log,
 * both in memory: a restart lost them, and a long run pushed its own beginning out. The record
 * that survives is the H2 log, which is where conversations write and where the log search reads.
 * A job opens one of those too, so that the criteria a run could not settle can be looked up days
 * later instead of being read off a screen while it runs.</p>
 */
@DisplayName("Project — a job's own record")
class JobInTheLogTest {

    @TempDir
    Path tempDir;

    private IoLogStore ioLog;

    @BeforeEach
    void setUp() {
        ioLog = new IoLogStore();
        ioLog.dbPath = tempDir.resolve("job-log-test").toString();
        ioLog.httpPort = 28097;
        ioLog.compress = false;
    }

    @AfterEach
    void tearDown() {
        ioLog.shutdown();
    }

    /**
     * The log's writer runs on a thread of its own, so a read straight after a write may not see
     * it yet. Waits for what was written rather than assuming the moment it lands.
     */
    private List<LogEntry> entriesOf(long sessionId, int expected) {
        long until = System.currentTimeMillis() + 5_000;
        List<LogEntry> entries = List.of();
        while (System.currentTimeMillis() < until) {
            entries = ioLog.store().getLogsByLevel(sessionId, LogLevel.DEBUG);
            if (entries.size() >= expected) return entries;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return entries;
    }

    @Test
    void aJobOpensOneOfItsOwnAndItsLinesGoIn() {
        long sessionId = ioLog.ensureJobSession("project1/job-01");
        assertTrue(sessionId >= 0, "a job has a place to write");

        ioLog.record(sessionId, "project1/job-01", Project.JOB_STEP_LABEL, "step 3 take-next-file ok");
        ioLog.record(sessionId, "project1/job-01", Project.NO_AGREEMENT_LABEL,
                "010.md | 観点5 oneaxis | REVISE: 軸が混ざっています");

        List<LogEntry> entries = entriesOf(sessionId, 2);
        assertEquals(2, entries.size(), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> Project.NO_AGREEMENT_LABEL.equals(e.getLabel())),
                "the ones worth looking up later carry their own label");
    }

    /** The name says it is a job, so the conversation views do not pick it up as a ConversationSquad. */
    @Test
    void aJobsRecordIsNotMistakenForAConversation() {
        ioLog.ensureJobSession("project1/job-01");

        SessionSummary session = ioLog.store().listSessions(10).get(0);
        assertTrue(session.getWorkflowName().contains("job"), session.getWorkflowName());
        assertFalse(ioLog.resumableConversationSquads().contains("project1/job-01"),
                "a restart does not build a ConversationSquad out of a job: " + ioLog.resumableConversationSquads());
    }

    @Test
    void oneJobKeepsOnePlaceHoweverManyLinesItWrites() {
        long first = ioLog.ensureJobSession("project1/job-02");
        long again = ioLog.ensureJobSession("project1/job-02");

        assertEquals(first, again);
    }
}
