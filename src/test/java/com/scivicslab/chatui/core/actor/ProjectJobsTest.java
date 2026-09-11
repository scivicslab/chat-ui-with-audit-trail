package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for a project running its workflows as jobs.
 *
 * <p>Exercises the load-bearing path with a real actor system and a real runner: a workflow that
 * reaches its end leaves a FINISHED job with the runner's own log lines; one the interpreter
 * cannot read leaves a FAILED job and never a running one; jobs are listed newest first; a job
 * that is over cannot be stopped.</p>
 */
@Tag("ProjectPerspective_260911_oo01")
class ProjectJobsTest {

    private static final String FINISHING = "name: finishing\n"
            + "steps:\n"
            + "  - states: [\"0\", \"1\"]\n"
            + "    label: say-done\n"
            + "    actions:\n"
            + "      - actor: this\n"
            + "        method: finish\n"
            + "        arguments: []\n"
            + "        execution: direct\n";

    private static Project.JobView await(ActorRef<Project> ref, String jobId, String state) throws Exception {
        for (int i = 0; i < 200; i++) {
            Project.JobView job = ref.ask(p -> p.job(jobId)).join();
            if (job != null && state.equals(job.state())) return job;
            Thread.sleep(25);
        }
        return ref.ask(p -> p.job(jobId)).join();
    }

    private static ActorRef<Project> boundProject(IIActorSystem system) {
        ActorRef<CallWatchdog> watchdog = system.actorOf("callWatchdog", new CallWatchdog());
        ActorRef<Project> ref = system.actorOf("project1", new Project());
        ref.tell(p -> p.bind("project1", system, ref, watchdog, null)).join();
        return ref;
    }

    @Test
    void startJob_runsTheWorkflowAsTheProjectsChild_andRecordsItsEnd() throws Exception {
        IIActorSystem system = new IIActorSystem("jobs-test");
        try {
            ActorRef<Project> ref = boundProject(system);

            Project.JobView started = ref.ask(p -> p.startJob("finishing", FINISHING)).join();

            assertEquals("job-01", started.jobId());
            assertEquals("project1/job-01", started.actorName(), "named as the project's child");
            assertEquals(Project.RUNNING, started.state());
            assertNotNull(system.getIIActor("project1/job-01"), "the runner is registered");
            assertNotNull(system.getIIActor("project1/job-01.log"), "with a log actor of its own");
            assertTrue(ref.getNamesOfChildren().contains("project1/job-01"), "and listed under the project");

            Project.JobView ended = await(ref, "job-01", Project.FINISHED);
            assertEquals(Project.FINISHED, ended.state(), "result was: " + ended.result());
            assertNotNull(ended.finishedAt());

            List<com.scivicslab.chatui.logging.RecentEntriesAccumulator.Entry> log =
                    ref.ask(p -> p.jobLog("job-01")).join();
            String text = String.join("\n", log.stream().map(e -> e.data()).toList());
            assertTrue(text.contains("started workflow finishing"), text);
            assertTrue(text.contains("say-done"), "the transition left is logged: " + text);
            assertTrue(text.contains("finished:"), text);
        } finally {
            system.terminate();
        }
    }

    @Test
    void startJob_ofSomethingTheInterpreterCannotRead_endsFailed_neverStaysRunning() throws Exception {
        IIActorSystem system = new IIActorSystem("jobs-test");
        try {
            ActorRef<Project> ref = boundProject(system);

            ref.ask(p -> p.startJob("broken", "steps: [\n  - oops")).join();
            Project.JobView ended = await(ref, "job-01", Project.FAILED);

            assertEquals(Project.FAILED, ended.state(), "result was: " + ended.result());
            assertTrue(ended.result().startsWith("(plan failed"), ended.result());
        } finally {
            system.terminate();
        }
    }

    @Test
    void jobs_listsNewestFirst_andAJobThatIsOverCannotBeStopped() throws Exception {
        IIActorSystem system = new IIActorSystem("jobs-test");
        try {
            ActorRef<Project> ref = boundProject(system);
            ref.ask(p -> p.startJob("finishing", FINISHING)).join();
            ref.ask(p -> p.startJob("finishing", FINISHING)).join();
            await(ref, "job-02", Project.FINISHED);

            List<Project.JobView> jobs = ref.ask(Project::jobs).join();
            assertEquals(List.of("job-02", "job-01"), jobs.stream().map(Project.JobView::jobId).toList());

            assertFalse(ref.ask(p -> p.stopJob("job-02")).join(), "finished, so nothing to stop");
            assertFalse(ref.ask(p -> p.stopJob("job-99")).join(), "no such job");
            assertNull(ref.ask(p -> p.jobLog("job-99")).join());
        } finally {
            system.terminate();
        }
    }
}
