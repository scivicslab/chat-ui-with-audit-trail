package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One job at a time per project, the rest waiting in order ({@code ProjectJobQueue_261014_oo01}),
 * with a real actor system and real runners.
 *
 * <p>Jobs that must be seen waiting are submitted inside one message to the project actor: a job's
 * end reaches the project as a later message, so nothing can start the next one in between.</p>
 */
@Tag("ProjectJobQueue_261014_oo01")
@DisplayName("project のジョブの待ち行列")
class ProjectJobQueueTest {

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

    private static ActorRef<Project> boundProject(IIActorSystem system, String projectId) {
        ActorRef<CallWatchdog> watchdog = system.getActor("callWatchdog") != null
                ? system.getActor("callWatchdog") : system.actorOf("callWatchdog", new CallWatchdog());
        ActorRef<Project> ref = system.actorOf(projectId, new Project());
        ref.tell(p -> p.bind(projectId, system, ref, null)).join();
        return ref;
    }

    @Test
    @DisplayName("2つ続けて登録すると、2つ目は QUEUED で待ち、runner はまだ無い")
    void secondJob_waits_withNoRunnerYet() {
        IIActorSystem system = new IIActorSystem("queue-test");
        try {
            ActorRef<Project> ref = boundProject(system, "project1");

            List<Object> seen = ref.ask(p -> {
                Project.JobView first = p.submitJob("finishing", FINISHING);
                Project.JobView second = p.submitJob("finishing", FINISHING);
                return List.<Object>of(first, second, system.getIIActor("project1/job-02") == null);
            }).join();

            assertEquals(Project.RUNNING, ((Project.JobView) seen.get(0)).state());
            Project.JobView second = (Project.JobView) seen.get(1);
            assertEquals(Project.QUEUED, second.state());
            assertEquals(1, second.position(), "next in line");
            assertNull(second.startedAt());
            assertTrue((Boolean) seen.get(2), "a waiting job has no runner");
            assertNotNull(system.getActor("project1/job-queue"), "the queue is the project's child");
            assertTrue(ref.getNamesOfChildren().contains("project1/job-queue"));
        } finally {
            system.terminate();
        }
    }

    @Test
    @DisplayName("前のジョブが終わると、待っていたジョブが Run を押した順に実行される")
    void waitingJobs_runInOrder_afterThePreviousEnds() throws Exception {
        IIActorSystem system = new IIActorSystem("queue-test");
        try {
            ActorRef<Project> ref = boundProject(system, "project1");
            ref.ask(p -> {
                p.submitJob("finishing", FINISHING);
                p.submitJob("finishing", FINISHING);
                return p.submitJob("finishing", FINISHING);
            }).join();

            Project.JobView first = await(ref, "job-01", Project.FINISHED);
            Project.JobView second = await(ref, "job-02", Project.FINISHED);
            Project.JobView third = await(ref, "job-03", Project.FINISHED);

            assertEquals(Project.FINISHED, third.state(), "result was: " + third.result());
            assertFalse(second.startedAt().isBefore(first.finishedAt()), "job-02 began after job-01 ended");
            assertFalse(third.startedAt().isBefore(second.finishedAt()), "job-03 began after job-02 ended");
        } finally {
            system.terminate();
        }
    }

    @Test
    @DisplayName("前のジョブが FAILED で終わっても、次のジョブが始まる")
    void aFailedJob_doesNotHoldUpTheLine() throws Exception {
        IIActorSystem system = new IIActorSystem("queue-test");
        try {
            ActorRef<Project> ref = boundProject(system, "project1");
            ref.ask(p -> {
                p.submitJob("broken", "steps: [\n  - oops");
                return p.submitJob("finishing", FINISHING);
            }).join();

            assertEquals(Project.FAILED, await(ref, "job-01", Project.FAILED).state());
            Project.JobView second = await(ref, "job-02", Project.FINISHED);
            assertEquals(Project.FINISHED, second.state(), "result was: " + second.result());
        } finally {
            system.terminate();
        }
    }

    @Test
    @DisplayName("待っているジョブを止めると、実行されないまま STOPPED になり、後ろのジョブは進む")
    void stoppingAWaitingJob_neverRunsIt() throws Exception {
        IIActorSystem system = new IIActorSystem("queue-test");
        try {
            ActorRef<Project> ref = boundProject(system, "project1");
            boolean stopped = ref.ask(p -> {
                p.submitJob("finishing", FINISHING);
                p.submitJob("finishing", FINISHING);
                p.submitJob("finishing", FINISHING);
                return p.stopJob("job-02");
            }).join();

            assertTrue(stopped);
            Project.JobView second = ref.ask(p -> p.job("job-02")).join();
            assertEquals(Project.STOPPED, second.state());
            assertNull(second.startedAt(), "it never ran");
            assertEquals(Project.FINISHED, await(ref, "job-03", Project.FINISHED).state());
            assertNull(system.getIIActor("project1/job-02"), "no runner was ever made for it");
            assertFalse(ref.ask(p -> p.stopJob("job-02")).join(), "already stopped");
        } finally {
            system.terminate();
        }
    }

    @Test
    @DisplayName("別の project のジョブは、この project の待ち行列を待たない")
    void anotherProject_doesNotWait() {
        IIActorSystem system = new IIActorSystem("queue-test");
        try {
            ActorRef<Project> one = boundProject(system, "project1");
            ActorRef<Project> two = boundProject(system, "project2");

            Project.JobView inOne = one.ask(p -> {
                p.submitJob("finishing", FINISHING);
                return p.submitJob("finishing", FINISHING);
            }).join();
            Project.JobView inTwo = two.ask(p -> p.submitJob("finishing", FINISHING)).join();

            assertEquals(Project.QUEUED, inOne.state());
            assertEquals(Project.RUNNING, inTwo.state(), "project2 has its own line");
        } finally {
            system.terminate();
        }
    }
}
