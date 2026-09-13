package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Runs one plan — a workflow a conversation wrote — as an actor of its own, separate from the
 * conversation that asked for it ({@code BabysitterRealisticE2eScenario_260828_oo01}
 * "chat-01のworkflowは、chat-01自身のagent loopとしてではなく、別アクター（PlanRunner）として動かす").
 *
 * <p>A conversation cannot install a plan on itself: {@code set_workflow} asks the target actor and
 * waits for the answer, so aimed at itself the message would sit in its own mailbox while its only
 * thread is blocked waiting for it. Running the plan in a separate actor avoids that, and — because
 * it is an actor rather than a plain object — leaves it reachable while it runs, so
 * {@code requestStop()} (inherited from {@link Interpreter}, safe to send via {@code tellNow}) can
 * stop it between transitions.</p>
 *
 * <p>Holds no {@code ActorRef} to the conversation that owns it; it only knows that conversation's
 * name, which it uses as the waiter id when it waits on someone
 * ({@code ChatSessionPorting_260823_oo01}'s "look up by name at the point of use").</p>
 */
public class PlanRunner extends Interpreter {

    private static final Logger LOG = Logger.getLogger(PlanRunner.class.getName());

    /** Long, for the same reason {@code ChatSession}'s babysitter phases use one: the conversation
     *  this asks runs its own multi-step turn inside the call. */
    private static final int ASK_TIMEOUT_SECONDS = 1800;

    private final ActorRef<CallWatchdog> watchdog;
    /** This runner's own actor name, e.g. {@code project1/chat-01.plan} — its waiter id. */
    private final String myName;

    /** The last reply a plan step received, and the plan's result when it finishes. */
    private String lastReply;
    /** Completed by {@link #finish()} (or by the caller if the plan never reaches it). */
    private CompletableFuture<String> done;

    /**
     * @param myName   this runner's own actor name
     * @param system   the actor system, used to reach the conversations a plan step talks to
     * @param watchdog the shared {@link CallWatchdog}
     */
    public PlanRunner(String myName, IIActorSystem system, ActorRef<CallWatchdog> watchdog) {
        this.myName = myName;
        this.watchdog = watchdog;
        // Assigns Interpreter's own inherited field — do not redeclare it here. A private field of
        // the same name would shadow it, leaving the one Interpreter.action() reads null, and the
        // plan would die on its first step with an NPE.
        this.system = system;
    }

    /** @param done completed with the plan's result once it finishes */
    public void setDone(CompletableFuture<String> done) { this.done = done; }

    /** Told one line about every transition this plan leaves; {@code null} means nobody listens. */
    private java.util.function.Consumer<String> stepListener;

    /**
     * @param listener told one line per transition left, e.g. for a job's log
     *                 ({@code ProjectPerspective_260911_oo01}); {@code null} to tell nobody
     */
    public void setStepListener(java.util.function.Consumer<String> listener) {
        this.stepListener = listener;
    }

    /**
     * Reports the transition just left to {@link #setStepListener the listener}, then does what
     * {@code Interpreter} does.
     */
    @Override
    protected void onExitTransition(com.scivicslab.turingworkflow.workflow.Transition transition,
                                    boolean success, ActionResult result) {
        java.util.function.Consumer<String> listener = stepListener;
        if (listener != null && transition != null) {
            String label = transition.getLabel();
            String states = transition.getStates() == null ? "" : String.join(" -> ", transition.getStates());
            String line = "step " + currentTransitionIndex
                    + (label == null || label.isBlank() ? "" : " " + label)
                    + (states.isEmpty() ? "" : " [" + states + "]")
                    + (success ? " ok" : " failed")
                    + (result == null || result.getResult() == null || result.getResult().isBlank()
                        ? "" : ": " + result.getResult());
            try {
                listener.accept(line);
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.FINE, "step listener failed", e);
            }
        }
        super.onExitTransition(transition, success, result);
    }

    /** @return the reply the last plan step received, or {@code null} */
    public String lastReply() { return lastReply; }

    /**
     * Plan step: sends {@code prompt} to a conversation and waits for its reply. The target is a
     * qualified conversation name ({@code project1/chat-02}), since a plan is written knowing which
     * conversations it drives.
     *
     * @param target the target conversation's qualified name
     * @param prompt what to ask it to do
     * @return {@link ActionResult} with {@code success=true} iff the conversation replied
     */
    public ActionResult askChat(String target, String prompt) {
        if (target == null || target.isBlank()) {
            return new ActionResult(false, "askChat: target is required");
        }
        String reply = AskChatTool.askQualified(system, watchdog, myName, target, prompt,
                ASK_TIMEOUT_SECONDS);
        if (reply == null || reply.startsWith("error:")) {
            lastReply = reply != null ? reply : "no reply from " + target;
            return new ActionResult(false, lastReply);
        }
        lastReply = reply;
        return new ActionResult(true, "reply received");
    }

    /**
     * Plan step: gives this plan a worker slot standing in for one conversation, as its own child
     * named {@code <plan>.worker-<id>}. Several slots can then be driven at once with {@code apply}
     * and a pattern such as {@code *.worker-*} ({@code ParallelWorkerPool_260829_oo01}).
     *
     * @param workerId       short id for this slot, e.g. {@code "a"}
     * @param targetChatName the conversation it forwards to, e.g. {@code project1/chat-03}
     * @return {@link ActionResult} with {@code success=true} iff the slot was created
     */
    /**
     * Runs one transition, with its {@code note} available to anything the transition creates
     * ({@code ActorPurposeFromWorkflowNote_260831_oo01}). {@code code} and
     * {@code currentTransitionIndex} are {@code Interpreter}'s own protected fields, so a subclass
     * can read the transition it is about to execute without changing the library.
     *
     * @return whatever the transition's actions returned
     */
    @Override
    public ActionResult action() {
        String note = null;
        try {
            note = code.getTransitions().get(currentTransitionIndex).getNote();
        } catch (Exception e) {
            // No transition to read: fall through with no note.
        }
        ActorNotes.enterTransition(note);
        try {
            return super.action();
        } finally {
            ActorNotes.leaveTransition();
        }
    }

    public ActionResult addWorker(String workerId, String targetChatName) {
        if (workerId == null || workerId.isBlank()) return new ActionResult(false, "workerId is required");
        if (targetChatName == null || targetChatName.isBlank()) {
            return new ActionResult(false, "targetChatName is required");
        }
        if (selfActorRef == null || system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        String workerName = myName + ".worker-" + workerId;
        if (system.getIIActor(workerName) == null) {
            PlanWorkerIIAR workerIIAR = new PlanWorkerIIAR(workerName,
                    new PlanWorker(system, watchdog, myName, targetChatName), system);
            workerIIAR.setParentName(myName);
            system.addIIActor(workerIIAR);
            ActorNotes.record(workerName);
        }
        // apply() matches against the caller's own child names, so the slot has to be recorded here.
        selfActorRef.getNamesOfChildren().add(workerName);
        return new ActionResult(true, "worker " + workerName + " -> " + targetChatName);
    }

    /**
     * Plan step: hands {@code prompt} to one worker slot and waits for its reply
     * ({@code ChainedRolesInAPlan_260913_oo01}).
     *
     * <p>The way to give a conversation a prompt this plan computed. {@code apply} cannot: its
     * inner {@code arguments} sit inside the outer action's arguments, and the interpreter
     * evaluates {@code jexl:} only at the top level, so a computed prompt reaches the conversation
     * as the text {@code jexl:…}. This method's arguments are top-level, so
     * <code>jexl:'…' + state.getString('draft')</code> is evaluated before it is called.
     * {@code apply} remains what fans one fixed prompt out to several slots at once.</p>
     *
     * @param workerId the slot's short id, as {@link #addWorker} was given it
     * @param prompt   what to ask the conversation behind it
     * @return {@link ActionResult} with {@code success=true} iff that slot exists and replied
     */
    public ActionResult askWorker(String workerId, String prompt) {
        if (selfActorRef == null || system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        if (workerId == null || workerId.isBlank()) return new ActionResult(false, "workerId is required");
        if (prompt == null || prompt.isBlank()) return new ActionResult(false, "prompt is required");
        String workerName = myName + ".worker-" + workerId;
        Object child = system.getIIActor(workerName);
        if (!(child instanceof PlanWorkerIIAR workerIIAR)) {
            return new ActionResult(false, "no worker slot named " + workerName);
        }
        ActionResult asked = workerIIAR.worker().ask(prompt);
        if (asked.isSuccess()) lastReply = workerIIAR.worker().lastReply();
        return asked;
    }

    /**
     * Plan step: puts what one worker slot last replied into this plan's own state, under
     * {@code key}, so a later step can hand it to somebody else with
     * <code>jexl:state.getString('key')</code> ({@code ChainedRolesInAPlan_260913_oo01}).
     *
     * <p>What a plan needs to pass a draft from a writer to a reviewer, and the reviewer's
     * criticism back to the writer. {@code collectWorkerReplies} gathers every slot at once and is
     * the end of a fan-out; this takes one slot and is the middle of a chain. An expression cannot
     * read the slot itself: JEXL may only call methods on the engine's own packages, so
     * {@code actors.get(…).lastReply()} answers {@code null} for a conversation's slot.</p>
     *
     * @param workerId the slot's short id, as {@link #addWorker} was given it
     * @param key      where to put the reply in this plan's state
     * @return {@link ActionResult} with {@code success=true} iff that slot exists and has replied
     */
    public ActionResult keepWorkerReply(String workerId, String key) {
        if (selfActorRef == null || system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        if (workerId == null || workerId.isBlank()) return new ActionResult(false, "workerId is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        String workerName = myName + ".worker-" + workerId;
        Object child = system.getIIActor(workerName);
        if (!(child instanceof PlanWorkerIIAR workerIIAR)) {
            return new ActionResult(false, "no worker slot named " + workerName);
        }
        String reply = workerIIAR.worker().lastReply();
        if (reply == null) return new ActionResult(false, workerName + " has not replied yet");
        selfActorRef.putJson(key, reply);
        return new ActionResult(true, "kept " + reply.length() + " chars of " + workerName
                + " as '" + key + "'");
    }

    /** What this plan's file steps may touch; {@code null} means it has none. */
    private com.scivicslab.chatui.agent.FileAccessScope fileScope;

    /** @param fileScope the range of the file system {@code readFile} and {@code writeFile} may touch */
    public void setFileScope(com.scivicslab.chatui.agent.FileAccessScope fileScope) {
        this.fileScope = fileScope;
    }

    /**
     * Plan step: reads a file into this plan's state, so the work that follows needs no person to
     * paste it in ({@code UnattendedFileRuns_260913_oo01}).
     *
     * @param path where to read from; within the instance's read roots
     * @param key  where to put the text in this plan's state
     * @return {@link ActionResult} with {@code success=true} iff the file was read
     */
    public ActionResult readFile(String path, String key) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (path == null || path.isBlank()) return new ActionResult(false, "path is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path file = java.nio.file.Path.of(path.strip()).toAbsolutePath();
            java.nio.file.Path real = file.toRealPath();
            if (!fileScope.canRead(real)) {
                return new ActionResult(false, "outside the readable range: " + real);
            }
            String text = java.nio.file.Files.readString(real);
            selfActorRef.putJson(key, text);
            return new ActionResult(true, "read " + text.length() + " chars of " + real + " as '" + key + "'");
        } catch (Exception e) {
            return new ActionResult(false, "could not read " + path + ": " + e.getMessage());
        }
    }

    /**
     * Plan step: writes what the plan kept under {@code key} to a file, creating the directories
     * above it ({@code UnattendedFileRuns_260913_oo01}).
     *
     * @param path where to write; under the instance's write root
     * @param key  which value in this plan's state to write
     * @return {@link ActionResult} with {@code success=true} iff the file was written
     */
    public ActionResult writeFile(String path, String key) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (path == null || path.isBlank()) return new ActionResult(false, "path is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (fileScope == null) return new ActionResult(false, "this job may not write files");
        String text = selfActorRef.getJsonString(key);
        if (text == null) return new ActionResult(false, "nothing is kept as '" + key + "'");
        try {
            java.nio.file.Path file = java.nio.file.Path.of(path.strip()).toAbsolutePath().normalize();
            if (!fileScope.canWrite(file)) {
                return new ActionResult(false, "outside the writable range: " + file);
            }
            if (file.getParent() != null) java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, text);
            return new ActionResult(true, "wrote " + text.length() + " chars to " + file);
        } catch (Exception e) {
            return new ActionResult(false, "could not write " + path + ": " + e.getMessage());
        }
    }

    /**
     * Plan step: puts the files of one directory into this plan's state, one path per line, so a
     * job can work through them without a person naming each ({@code UnattendedFileRuns_260913_oo01}).
     *
     * @param dir    the directory to list; not walked into its subdirectories
     * @param suffix what a name must end with, e.g. {@code .md}; every file when blank
     * @param key    where to put the list
     * @return {@link ActionResult} with {@code success=true} iff at least one file matched
     */
    public ActionResult listFiles(String dir, String suffix, String key) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (dir == null || dir.isBlank()) return new ActionResult(false, "dir is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        String ending = suffix == null ? "" : suffix.strip();
        try {
            java.nio.file.Path root = java.nio.file.Path.of(dir.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(root)) return new ActionResult(false, "outside the readable range: " + root);
            java.util.List<String> found = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(root)) {
                files.filter(java.nio.file.Files::isRegularFile)
                     .filter(f -> ending.isEmpty() || f.getFileName().toString().endsWith(ending))
                     .sorted()
                     .forEach(f -> found.add(f.toString()));
            }
            if (found.isEmpty()) {
                return new ActionResult(false, "no file ending in '" + ending + "' under " + root);
            }
            selfActorRef.putJson(key, String.join("\n", found));
            return new ActionResult(true, "found " + found.size() + " file(s) under " + root);
        } catch (Exception e) {
            return new ActionResult(false, "could not list " + dir + ": " + e.getMessage());
        }
    }

    /**
     * Plan step: moves the first line of the list at {@code listKey} into {@code key} and shortens
     * the list, succeeding while there was one ({@code UnattendedFileRuns_260913_oo01}).
     *
     * <p>What makes a loop over a list: the transition written after this one is taken when the
     * list is empty, so a plan works through the files and then leaves.</p>
     *
     * @param listKey where the remaining lines are
     * @param key     where to put the line taken
     * @return {@link ActionResult} with {@code success=true} iff a line was taken
     */
    public ActionResult takeNext(String listKey, String key) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (listKey == null || listKey.isBlank()) return new ActionResult(false, "listKey is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        String remaining = selfActorRef.getJsonString(listKey);
        if (remaining == null || remaining.isBlank()) return new ActionResult(false, "nothing left in '" + listKey + "'");
        int newline = remaining.indexOf('\n');
        String head = newline < 0 ? remaining : remaining.substring(0, newline);
        String tail = newline < 0 ? "" : remaining.substring(newline + 1);
        selfActorRef.putJson(key, head);
        selfActorRef.putJson(listKey, tail);
        long left = tail.isBlank() ? 0 : tail.lines().count();
        return new ActionResult(true, "took " + head + " (" + left + " left)");
    }

    /**
     * Plan step: copies one value of this plan's state to another key
     * ({@code KeepTheFactsWhileCutting_260913_oo01}).
     *
     * <p>What lets a judge compare before with after: the text is copied aside before the step that
     * rewrites it, and the judge is given both.</p>
     *
     * @param from which value to copy
     * @param to   where to put the copy
     * @return {@link ActionResult} with {@code success=true} iff there was something to copy
     */
    public ActionResult copyState(String from, String to) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (from == null || from.isBlank()) return new ActionResult(false, "from is required");
        if (to == null || to.isBlank()) return new ActionResult(false, "to is required");
        String value = selfActorRef.getJsonString(from);
        if (value == null) return new ActionResult(false, "nothing is kept as '" + from + "'");
        selfActorRef.putJson(to, value);
        return new ActionResult(true, "copied " + value.length() + " chars of '" + from + "' to '" + to + "'");
    }

    /**
     * Plan step: succeeds when what the plan kept under {@code key} begins with {@code expected}
     * ({@code OneCriterionPerTurn_260913_oo01}).
     *
     * <p>How a plan branches on a judgement. A conversation asked to judge answers with words; the
     * state machine moves on success and failure. This turns the one into the other, so
     * {@code ACCEPT} takes the transition to the next thing to fix and anything else falls through
     * to the redo transition written after it.</p>
     *
     * @param key      where the judgement is in this plan's state
     * @param expected what the value must begin with, compared without case or surrounding space
     * @return {@link ActionResult} with {@code success=true} iff it does
     */
    public ActionResult checkState(String key, String expected) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (expected == null || expected.isBlank()) return new ActionResult(false, "expected is required");
        String value = selfActorRef.getJsonString(key);
        String head = value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
        boolean matches = head.startsWith(expected.strip().toUpperCase(java.util.Locale.ROOT));
        String first = head.isEmpty() ? "(nothing)" : head.substring(0, Math.min(60, head.length()));
        return new ActionResult(matches, (matches ? "accepted: " : "not yet: ") + first);
    }

    /**
     * Plan step: counts one attempt at {@code key} and succeeds while the budget holds
     * ({@code OneCriterionPerTurn_260913_oo01}).
     *
     * <p>What bounds a redo loop. The count lives in the plan's state, so each thing being fixed
     * can have its own key and the loop for one does not spend another's budget.</p>
     *
     * @param key   where the count is kept
     * @param limit how many attempts are allowed
     * @return {@link ActionResult} with {@code success=true} iff this attempt is within the limit
     */
    public ActionResult countUp(String key, String limit) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        int allowed;
        try {
            allowed = Integer.parseInt(limit == null ? "" : limit.strip());
        } catch (NumberFormatException e) {
            return new ActionResult(false, "limit must be a whole number, not '" + limit + "'");
        }
        int used = 0;
        String current = selfActorRef.getJsonString(key);
        if (current != null && !current.isBlank()) {
            try {
                used = Integer.parseInt(current.strip());
            } catch (NumberFormatException e) {
                used = 0;
            }
        }
        used++;
        selfActorRef.putJson(key, String.valueOf(used));
        return new ActionResult(used <= allowed, "attempt " + used + " of " + allowed);
    }

    /**
     * Plan step: gathers what every worker slot replied into this plan's result, in slot-name order
     * so the output does not depend on which slot happened to finish first.
     *
     * @return {@link ActionResult} with {@code success=true} iff at least one slot had a reply
     */
    public ActionResult collectWorkerReplies() {
        if (selfActorRef == null || system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        StringBuilder joined = new StringBuilder();
        int found = 0;
        for (String childName : new java.util.TreeSet<>(selfActorRef.getNamesOfChildren())) {
            // Object, not IIActorRef<?>: getIIActor's element type and PlanWorkerIIAR's differ, so
            // the compiler rejects the pattern match on the narrower static type.
            Object child = system.getIIActor(childName);
            if (!(child instanceof PlanWorkerIIAR workerIIAR)) continue;
            PlanWorker worker = workerIIAR.worker();
            if (worker.lastReply() == null) continue;
            found++;
            joined.append("## ").append(worker.targetChatName()).append("\n")
                  .append(worker.lastReply()).append("\n\n");
        }
        if (found == 0) return new ActionResult(false, "no worker replies to collect");
        lastReply = joined.toString().stripTrailing();
        return new ActionResult(true, "collected " + found + " worker reply/replies");
    }

    /**
     * Terminal step: hands the last reply back to whoever is waiting on this plan.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult finish() {
        return finish(null);
    }

    /**
     * Terminal step: hands back what this plan's state holds under {@code key}, or its last reply
     * when no key is given ({@code ChainedRolesInAPlan_260913_oo01}). A chain that kept each role's
     * answer with {@link #keepWorkerReply} names the one that is the plan's product, rather than
     * ending with whatever the last step happened to answer.
     *
     * @param key where the result is in this plan's state, or {@code null} for the last reply
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult finish(String key) {
        String kept = (key == null || key.isBlank() || selfActorRef == null)
                ? null : selfActorRef.getJsonString(key);
        String result = kept != null && !kept.isBlank() ? kept : lastReply;
        complete(result != null ? result : "(plan finished with no result)");
        return new ActionResult(true, "finished");
    }

    /**
     * Fallback step, for the same reason the babysitter workflows have one: a failed
     * {@link #askChat} would otherwise leave its state with no transition left to try, and whoever
     * is waiting would wait until their own timeout instead of being told what happened.
     *
     * @return {@link ActionResult} with {@code success=true} always
     */
    public ActionResult reportFailure() {
        complete("(plan stopped: " + lastReply + ")");
        return new ActionResult(true, "reported failure");
    }

    /**
     * Completes the waiting caller, unless the plan already did. Called by {@link #finish()}/
     * {@link #reportFailure()}, and by the code that started the plan if it ended without reaching
     * either — so nobody is left waiting on a plan that has stopped running.
     *
     * @param result what to hand back
     */
    public void complete(String result) {
        if (done != null && !done.isDone()) {
            done.complete(result);
        } else if (done == null) {
            LOG.warning("PlanRunner " + myName + " completed with no waiter: " + result);
        }
    }
}
