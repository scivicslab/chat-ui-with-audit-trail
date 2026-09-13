package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the values a job is started with.
 *
 * <p>Exercises the load-bearing path with a real actor system and a real runner: a workflow whose
 * argument is <code>${key}</code> must see the value the run was given, and the job's first log
 * line must say what it ran with.</p>
 */
@Tag("JobParameterForm_260913_oo01")
class ProjectJobParametersTest {

    /** Writes what its argument expanded to into the runner's own state, then ends. */
    private static final String ECHOING = """
            name: echoing
            params:
              who:
                description: "whose name to write"
            steps:
              - states: ["0", "1"]
                label: write-it
                actions:
                  - actor: this
                    method: putJson
                    arguments:
                      path: seen
                      value: "jexl:state.get('who')"
                    execution: direct
              - states: ["1", "2"]
                label: say-done
                actions:
                  - actor: this
                    method: finish
                    arguments: []
                    execution: direct
            """;

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

    private static String logOf(ActorRef<Project> ref, String jobId) {
        List<com.scivicslab.chatui.logging.RecentEntriesAccumulator.Entry> log =
                ref.ask(p -> p.jobLog(jobId)).join();
        return String.join("\n", log.stream().map(e -> e.data()).toList());
    }

    @Test
    void aJobsPlaceholders_areTheValuesItWasStartedWith() throws Exception {
        IIActorSystem system = new IIActorSystem("job-parameters-test");
        try {
            ActorRef<Project> ref = boundProject(system);

            ref.ask(p -> p.startJob("echoing", ECHOING, Map.of("who", "Ada"))).join();
            Project.JobView ended = await(ref, "job-01", Project.FINISHED);
            assertEquals(Project.FINISHED, ended.state(), "result was: " + ended.result());

            var runner = system.getIIActor("project1/job-01");
            String seen = runner.callByActionName("getJson", "[\"seen\"]").getResult();
            assertTrue(seen.contains("Ada"), "the workflow saw the value, not the placeholder: " + seen);
            assertFalse(seen.contains("${who}"), seen);
        } finally {
            system.terminate();
        }
    }

    @Test
    void theFirstLogLine_saysWhatTheRunWasGiven() throws Exception {
        IIActorSystem system = new IIActorSystem("job-parameters-test");
        try {
            ActorRef<Project> ref = boundProject(system);

            ref.ask(p -> p.startJob("echoing", ECHOING, Map.of("who", "Ada"))).join();
            await(ref, "job-01", Project.FINISHED);

            String text = logOf(ref, "job-01");
            assertTrue(text.contains("started workflow echoing with who=Ada"), text);
        } finally {
            system.terminate();
        }
    }

    @Test
    void withoutValues_theJobStartsAsItDidBefore() throws Exception {
        IIActorSystem system = new IIActorSystem("job-parameters-test");
        try {
            ActorRef<Project> ref = boundProject(system);

            ref.ask(p -> p.startJob("echoing", ECHOING)).join();
            await(ref, "job-01", Project.FINISHED);

            String text = logOf(ref, "job-01");
            assertTrue(text.contains("started workflow echoing"), text);
            assertFalse(text.contains("with who="), "nothing was given, so nothing is claimed: " + text);
        } finally {
            system.terminate();
        }
    }
}
