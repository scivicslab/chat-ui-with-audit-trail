package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.RunPlanTool;
import com.scivicslab.chatui.logging.ForwardingAccumulator;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.plugins.logoutput.MultiplexerAccumulatorActor;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Top-level grouping actor for one project — a {@code createChild} parent, exactly like
 * {@link CallWatchdog}/{@link CollaborationGraph}, that additionally carries the project's
 * working directory and the instructions found there.
 *
 * <p>Everything one project's execution needs — its conversation tabs ({@code chat-...}) — lives
 * as a descendant of one {@code Project} instance. {@code outputMultiplexer}, {@code callWatchdog}
 * and {@code collaborationGraph} deliberately stay outside this tree — see
 * {@code ProjectScopedActorTree_260829_oo01}.</p>
 *
 * <p>The working directory is what gives a conversation a position in the file tree, which
 * {@code AGENTS.md} needs and this system otherwise lacks: an agent editing a file resolves
 * "the nearest {@code AGENTS.md}" from the file it is editing, and a conversation has no such
 * file. The project's directory stands in for it, so every conversation in one project receives
 * that directory's instructions ({@code SkillAndAgentsFile_260830_oo01}).</p>
 *
 * <p>The project also runs its workflows as batch jobs ({@code ProjectPerspective_260911_oo01}).
 * One job is one workflow run as one {@link PlanRunner} that is this project's child, named
 * {@code <projectId>/job-NN}; the jobs that exist are the project's job list. They are the
 * project's rather than a conversation's because a plan drives conversations — {@code addWorker}
 * hands slots to {@code chat-02}, {@code chat-03} — and a thing that spans conversations belongs
 * to what contains them. The conversation-scoped runner ({@code <chat>.plan}, {@link RunPlanTool})
 * stays as the entry point a conversation's own LLM uses.</p>
 *
 * <p>A plain object held by one actor: the job table is an ordinary map, changed only through
 * that actor's mailbox. A job's end arrives as a message ({@link #jobFinished}), and a
 * {@link Scheduler} tick against the actor itself ({@link #tick}) notices a runner that died
 * without saying so, the way {@code ActivityWatcher} renews its answer.</p>
 */
public class Project {

    /** The standard file name, per the AGENTS.md open format. */
    public static final String AGENTS_FILE = "AGENTS.md";
    /** Read only when there is no {@code AGENTS.md}; every repository here still carries this one. */
    public static final String CLAUDE_FILE = "CLAUDE.md";

    private Path workingDir;
    private Path instructionsFile;
    private String instructions;

    /**
     * Points this project at a directory and reads that directory's instructions, preferring
     * {@code AGENTS.md} and falling back to {@code CLAUDE.md}. A project with no working directory,
     * or a directory with neither file, simply has no instructions — that is the normal state, not
     * an error.
     *
     * @param dir the project's working directory, or {@code null} to clear it
     * @return a one-line account of what was loaded, or an {@code error: ...} string
     */
    public String setWorkingDir(Path dir) {
        workingDir = dir;
        instructionsFile = null;
        instructions = null;
        if (dir == null) return "ok: working directory cleared";
        if (!Files.isDirectory(dir)) return "error: not a directory: " + dir;
        Path candidate = dir.resolve(AGENTS_FILE);
        if (!Files.isRegularFile(candidate)) {
            candidate = dir.resolve(CLAUDE_FILE);
            if (!Files.isRegularFile(candidate)) {
                return "ok: working directory set to " + dir + " (no " + AGENTS_FILE
                        + " and no " + CLAUDE_FILE + " there)";
            }
        }
        try {
            instructions = Files.readString(candidate).strip();
            instructionsFile = candidate;
        } catch (IOException e) {
            return "error: cannot read " + candidate + ": " + e.getMessage();
        }
        return "ok: working directory set to " + dir + ", instructions read from "
                + candidate.getFileName() + " (" + instructions.length() + " characters)";
    }

    /** Re-reads the instructions from the current working directory. @return the same account as
     *  {@link #setWorkingDir(Path)} */
    public String reloadInstructions() {
        return setWorkingDir(workingDir);
    }

    /** @return this project's working directory, or {@code null} if it has none */
    public Path getWorkingDir() {
        return workingDir;
    }

    /** @return the file the instructions were read from, or {@code null} if there are none */
    public Path getInstructionsFile() {
        return instructionsFile;
    }

    /** @return this project's instructions, or {@code null} if it has none */
    public String getInstructions() {
        return instructions;
    }

    // ── Batch jobs (ProjectPerspective_260911_oo01) ──────────────────────────────────────────

    private static final Logger LOG = Logger.getLogger(Project.class.getName());

    /** A job's state. */
    public static final String RUNNING = "RUNNING";
    /** A job's state: the workflow reached its end. */
    public static final String FINISHED = "FINISHED";
    /** A job's state: the workflow did not reach its end, or its runner died. */
    public static final String FAILED = "FAILED";
    /** A job's state: stopped by request. */
    public static final String STOPPED = "STOPPED";

    /** How often {@link #tick} looks at the running jobs. Cheap: a lookup per job, no I/O. */
    private static final Duration TICK = Duration.ofSeconds(5);
    /** How many of a job's log lines are kept. */
    private static final int JOB_LOG_CAPACITY = 500;

    /**
     * One job as it stands.
     *
     * @param jobId      {@code job-NN}, unique within this project
     * @param actorName  the runner's actor name, {@code <projectId>/job-NN}
     * @param workflow   the workflow's name, as the catalog lists it
     * @param state      one of {@link #RUNNING}, {@link #FINISHED}, {@link #FAILED}, {@link #STOPPED}
     * @param startedAt  when it was started
     * @param finishedAt when it ended, or {@code null} while running
     * @param result     what the run reported, or {@code null} while running
     */
    public record JobView(String jobId, String actorName, String workflow, String state,
                          Instant startedAt, Instant finishedAt, String result) {
        JobView ended(String state, String result) {
            return new JobView(jobId, actorName, workflow, state, startedAt, Instant.now(), result);
        }
    }

    private String projectId;
    private IIActorSystem system;
    private ActorRef<Project> self;
    private ActorRef<CallWatchdog> watchdog;
    private String systemLogActorName;
    /** What a job's file steps may touch; {@code null} means none. */
    private com.scivicslab.chatui.agent.FileAccessScope fileScope;
    private Scheduler scheduler;
    private final Map<String, JobView> jobs = new LinkedHashMap<>();
    private final Map<String, RecentEntriesAccumulator> jobLogs = new HashMap<>();
    private int jobCounter;

    /**
     * Binds what starting a job needs. Must run before {@link #startJob} and {@link #startWatching}.
     *
     * @param projectId          this project's id, the prefix of its jobs' names
     * @param system             the actor system the runners are registered in
     * @param self               this actor's own reference, the runners' parent
     * @param watchdog           the shared {@link CallWatchdog} a plan's {@code askChat} steps use
     * @param systemLogActorName the system-wide log actor a job's log forwards to
     */
    public void bind(String projectId, IIActorSystem system, ActorRef<Project> self,
                     ActorRef<CallWatchdog> watchdog, String systemLogActorName) {
        bind(projectId, system, self, watchdog, systemLogActorName, null);
    }

    /**
     * The same, with the range of the file system a job may read and write
     * ({@code UnattendedFileRuns_260913_oo01}). A job started without one can call no file step.
     *
     * @param fileScope what {@code readFile} and {@code writeFile} are allowed to touch
     */
    public void bind(String projectId, IIActorSystem system, ActorRef<Project> self,
                     ActorRef<CallWatchdog> watchdog, String systemLogActorName,
                     com.scivicslab.chatui.agent.FileAccessScope fileScope) {
        this.fileScope = fileScope;
        this.projectId = projectId;
        this.system = system;
        this.self = self;
        this.watchdog = watchdog;
        this.systemLogActorName = systemLogActorName;
    }

    /** Starts the periodic look at running jobs, scheduled against this actor's own mailbox. */
    public void startWatching() {
        scheduler = new Scheduler(1);
        scheduler.scheduleWithFixedDelay("jobs", self, Project::tick,
                TICK.toSeconds(), TICK.toSeconds(), TimeUnit.SECONDS);
    }

    /** Stops the schedule. Called when the actor system is torn down. */
    public void stopWatching() {
        if (scheduler != null) scheduler.close();
    }

    /**
     * Starts a workflow as a new job of this project.
     *
     * <p>Creates the runner as this project's child, {@code <projectId>/job-NN}, gives it a log
     * actor of its own wired like a conversation's ({@code <job>.log}, forwarding to the
     * system-wide log), and launches the run the way {@link RunPlanTool} does. The run's end
     * comes back as a message to this actor ({@link #jobFinished}).</p>
     *
     * @param workflow the workflow's name, for the job list
     * @param yaml     the workflow text
     * @return the new job, in state {@link #RUNNING}
     * @throws IllegalStateException when {@link #bind} has not run
     */
    public JobView startJob(String workflow, String yaml) {
        return startJob(workflow, yaml, Map.of());
    }

    /**
     * Starts one workflow as a job, with the values its <code>${key}</code> stand for.
     *
     * <p>The values are put into the runner's own JSON state before the run starts, which is where
     * <code>${key}</code> is read from — the same thing {@code RunCLI} does with {@code -P}
     * ({@code JobParameterForm_260913_oo01}). The workflow's text is left as written, so what runs
     * is what the catalog shows and the log line below says what it ran with.</p>
     *
     * @param workflow   the name the catalog lists it under
     * @param yaml       its text
     * @param parameters the values, keyed by the name the workflow refers to; may be empty
     * @return the job, as it is at the moment it starts
     */
    public JobView startJob(String workflow, String yaml, Map<String, String> parameters) {
        if (self == null || system == null) {
            throw new IllegalStateException("project " + projectId + " is not bound to an actor system");
        }
        String jobId = String.format("job-%02d", ++jobCounter);
        String name = projectId + "/" + jobId;

        PlanRunner runner = new PlanRunner(name, system, watchdog);
        runner.setFileScope(fileScope);
        PlanRunnerIIAR runnerRef = new PlanRunnerIIAR(name, runner, system);
        runnerRef.setParentName(projectId);
        self.getNamesOfChildren().add(name);
        system.addIIActor(runnerRef);

        RecentEntriesAccumulator buffer = new RecentEntriesAccumulator(JOB_LOG_CAPACITY);
        MultiplexerAccumulator mux = new MultiplexerAccumulator();
        mux.addTarget(buffer);
        if (systemLogActorName != null) {
            mux.addTarget(new ForwardingAccumulator(system, systemLogActorName, name));
        }
        MultiplexerAccumulatorActor logActor = new MultiplexerAccumulatorActor(name + ".log", mux, system);
        logActor.setParentName(name);
        runnerRef.getNamesOfChildren().add(logActor.getName());
        system.addIIActor(logActor);
        jobLogs.put(jobId, buffer);

        // Every transition the runner leaves is one line in the job's log.
        runner.setStepListener(line -> log(name, "INFO", line));

        seedParameters(runnerRef, parameters);

        JobView view = new JobView(jobId, name, workflow, RUNNING, Instant.now(), null, null);
        jobs.put(jobId, view);
        log(name, "INFO", "started workflow " + workflow + describe(parameters));

        ActorRef<Project> me = self;
        CompletableFuture<String> done = RunPlanTool.start(runnerRef, name, yaml);
        // `done` is completed from inside the last transition (finish), before that transition's
        // own log line is written. Queue an empty message behind the run on the runner's mailbox
        // and record the end only once that has been processed, so the job's log reads in order:
        // start, every step, then the end.
        done.whenComplete((result, error) ->
                runnerRef.tell(interp -> { })
                         .whenComplete((v, ignored) -> me.tell(p -> p.jobFinished(jobId, result, error))));
        return view;
    }

    /**
     * Puts the run's values where <code>${key}</code> is read from: the runner's own JSON state,
     * through the {@code putJson} action every {@code IIActorRef} answers. Called before the run
     * is queued, so nothing else is on that mailbox yet.
     */
    private static void seedParameters(PlanRunnerIIAR runner, Map<String, String> parameters) {
        if (parameters == null) return;
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
            String args = new JSONObject()
                    .put("path", e.getKey())
                    .put("value", e.getValue())
                    .toString();
            runner.callByActionName("putJson", args);
        }
    }

    /** @return {@code " with query=…, perPage=…"}, or {@code ""} when the run was given nothing */
    private static String describe(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" with ");
        boolean first = true;
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * On the mailbox: records how a job ended.
     *
     * @param jobId  the job
     * @param result what the run reported, or {@code null} when it threw
     * @param error  what it threw, or {@code null}
     */
    void jobFinished(String jobId, String result, Throwable error) {
        JobView job = jobs.get(jobId);
        if (job == null || !RUNNING.equals(job.state())) return;
        String state;
        String outcome;
        if (error != null) {
            state = FAILED;
            outcome = "(job failed: " + error + ")";
        } else {
            outcome = result == null ? "" : result;
            if (outcome.startsWith("(plan stopped")) state = STOPPED;
            else if (outcome.startsWith("(plan failed") || outcome.startsWith("(plan did not finish")) state = FAILED;
            else state = FINISHED;
        }
        jobs.put(jobId, job.ended(state, outcome));
        log(job.actorName(), FAILED.equals(state) ? "WARNING" : "INFO", state.toLowerCase() + ": " + outcome);
    }

    /**
     * Asks a running job to stop between transitions.
     *
     * @param jobId the job
     * @return whether there was such a job and it was running
     */
    public boolean stopJob(String jobId) {
        JobView job = jobs.get(jobId);
        if (job == null || !RUNNING.equals(job.state())) return false;
        IIActorRef<?> ref = system.getIIActor(job.actorName());
        if (!(ref instanceof PlanRunnerIIAR runnerRef)) return false;
        // tellNow, as ChatUiActorSystem.stopPlan does: the runner's thread is inside runUntilEnd,
        // so a queued message would arrive only after the run it was meant to stop.
        runnerRef.tellNow(interp -> interp.requestStop());
        log(job.actorName(), "INFO", "stop requested");
        return true;
    }

    /**
     * On the mailbox, on a schedule: a running job whose runner is gone is recorded as failed.
     * A runner that ends normally says so through {@link #jobFinished}; this covers the one that
     * died without saying so.
     */
    void tick() {
        for (JobView job : new ArrayList<>(jobs.values())) {
            if (!RUNNING.equals(job.state())) continue;
            IIActorRef<?> ref = system.getIIActor(job.actorName());
            if (ref == null || !ref.isAlive()) {
                jobs.put(job.jobId(), job.ended(FAILED, "(runner is gone)"));
                log(job.actorName(), "WARNING", "failed: runner is gone");
            }
        }
    }

    /** @return this project's jobs, newest first */
    public List<JobView> jobs() {
        List<JobView> out = new ArrayList<>(jobs.values());
        Collections.reverse(out);
        return List.copyOf(out);
    }

    /** @return one job, or {@code null} */
    public JobView job(String jobId) {
        return jobs.get(jobId);
    }

    /** @return a job's log lines, oldest first, or {@code null} when there is no such job */
    public List<RecentEntriesAccumulator.Entry> jobLog(String jobId) {
        RecentEntriesAccumulator buffer = jobLogs.get(jobId);
        return buffer == null ? null : buffer.recent();
    }

    /** Writes one line to a job's log actor, through its mailbox, as ChatUiActorSystem.submitPlan does. */
    private void log(String jobActorName, String type, String data) {
        IIActorRef<?> logRef = system.getIIActor(jobActorName + ".log");
        if (logRef == null) return;
        try {
            org.json.JSONObject args = new org.json.JSONObject();
            args.put("source", "job");
            args.put("type", type);
            args.put("data", data);
            logRef.callByActionName("add", args.toString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not log for " + jobActorName, e);
        }
    }
}
