package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.util.ArrayList;
import java.util.List;
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
     * How a plan changes which provider kind a conversation talks to, when it is given one.
     *
     * <p>Changing the kind builds a provider from a plugin the instance was started with, which is
     * {@code ChatUiActorSystem}'s work and not something reachable from an actor reference. A plan
     * started without this can still name a model ({@code ChooseTheLlmPerRole_260915_oo01}).</p>
     */
    public interface ProviderChanger {
        /**
         * @param chatName the conversation's full actor name
         * @param kind     the provider kind, e.g. {@code claude}
         * @param tools    the tool set, or {@code ""} for the kind's own default
         * @return what went wrong, or {@code null} when the change was made
         */
        String change(String chatName, String kind, String tools);
    }

    private ProviderChanger providerChanger;

    /** @param changer how to change a conversation's provider kind; {@code null} to refuse to */
    public void setProviderChanger(ProviderChanger changer) {
        this.providerChanger = changer;
    }

    /**
     * Plan step: puts one role's conversation on a named model.
     *
     * <p>Which model judged a document is part of what the run was. Named in the job's parameters
     * rather than left on the conversation, the run says it itself, and the change is written to
     * that conversation's settings record as any other would be
     * ({@code ChooseTheLlmPerRole_260915_oo01}).</p>
     *
     * @param chatName the conversation's full actor name, e.g. {@code project1/chat-02}
     * @param model    the model to run on; blank leaves the conversation as it was
     * @return {@link ActionResult} with {@code success=true} iff that conversation was there
     */
    public ActionResult setChatModel(String chatName, String model) {
        if (system == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        if (model == null || model.isBlank()) {
            return new ActionResult(true, "no model named; " + chatName + " stays as it was");
        }
        ActorRef<LlmProvider> providerRef = system.getActor(chatName + ".chat.provider");
        if (providerRef == null) return new ActionResult(false, "chat not found: " + chatName);
        providerRef.tell(p -> p.setModel(model)).join();
        return new ActionResult(true, chatName + " runs on " + model);
    }

    /**
     * Plan step: puts a conversation on another agent loop, which also decides how each of its
     * prompts is built ({@code AgentLoopDecidesThePrompt_261020_oo01}).
     *
     * @param chatName the conversation's qualified name
     * @param loopFile an agent loop under {@code /workflows/}, e.g. {@code plain-text-loop.yaml}; blank leaves it as it is
     * @return {@link ActionResult} with {@code success=true} iff the conversation is on that loop
     */
    public ActionResult setChatAgentLoop(String chatName, String loopFile) {
        if (system == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        if (loopFile == null || loopFile.isBlank()) {
            return new ActionResult(true, "no agent loop named; " + chatName + " stays as it was");
        }
        Object session = system.getIIActor(chatName + ".chat");
        if (!(session instanceof ChatSessionIIAR chatSessionIIAR)) {
            return new ActionResult(false, "chat not found: " + chatName);
        }
        String refused = chatSessionIIAR.setAgentLoopWorkflowFile(loopFile.strip());
        return refused == null
                ? new ActionResult(true, chatName + " runs " + loopFile.strip())
                : new ActionResult(false, refused);
    }

    /**
     * Plan step: says whether one role's conversation asks its model to skip the thinking phase
     * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
     *
     * <p>{@code askWorker} carries a prompt and nothing else, so a run that wants an answer without
     * reasoning cannot ask for it prompt by prompt. It says so once, here, as it names the model.</p>
     *
     * @param chatName the conversation's full actor name
     * @param value    {@code true} or {@code false}; blank leaves the conversation as it was
     * @return {@link ActionResult} with {@code success=true} iff that conversation was there
     */
    public ActionResult setChatNoThink(String chatName, String value) {
        if (system == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        if (value == null || value.isBlank()) {
            return new ActionResult(true, "nothing said about thinking; " + chatName + " stays as it was");
        }
        Object session = system.getIIActor(chatName + ".chat");
        if (!(session instanceof ChatSessionIIAR chatSessionIIAR)) {
            return new ActionResult(false, "chat not found: " + chatName);
        }
        boolean noThink = Boolean.parseBoolean(value.trim());
        chatSessionIIAR.tell(a -> ((ChatSession) a).setNoThink(noThink)).join();
        return new ActionResult(true, chatName + (noThink ? " answers without thinking" : " thinks as it did"));
    }

    /**
     * Plan step: says how hard one role's conversation should work on an answer, where its provider
     * has such a setting ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
     *
     * @param chatName the conversation's full actor name
     * @param effort   what the harness calls its levels; blank leaves the conversation as it was
     * @return {@link ActionResult} with {@code success=true} iff that conversation was there
     */
    public ActionResult setChatEffort(String chatName, String effort) {
        if (system == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        if (effort == null || effort.isBlank()) {
            return new ActionResult(true, "no effort named; " + chatName + " stays as it was");
        }
        ActorRef<LlmProvider> providerRef = system.getActor(chatName + ".chat.provider");
        if (providerRef == null) return new ActionResult(false, "chat not found: " + chatName);
        providerRef.tell(p -> p.setEffort(effort)).join();
        return new ActionResult(true, chatName + " works at " + effort);
    }

    /**
     * Plan step: puts one role's conversation on a named provider kind.
     *
     * @param chatName the conversation's full actor name
     * @param kind     {@code openai-compat}, {@code claude}, {@code codex}; blank leaves it as it was
     * @param tools    the tool set to give it, or blank for the kind's own default
     * @return {@link ActionResult} with {@code success=true} iff the change was made; a kind this
     *         instance was not started with is a failure, not a silent fallback
     */
    public ActionResult setChatProvider(String chatName, String kind, String tools) {
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        if (kind == null || kind.isBlank()) {
            return new ActionResult(true, "no provider named; " + chatName + " stays as it was");
        }
        if (providerChanger == null) {
            return new ActionResult(false,
                    "this job cannot change a provider kind; name a model instead, or set the"
                            + " provider on " + chatName + " before running");
        }
        String why = providerChanger.change(chatName, kind, tools == null ? "" : tools);
        return why == null
                ? new ActionResult(true, chatName + " talks to " + kind)
                : new ActionResult(false, why);
    }

    /**
     * Plan step: writes a list the plan built up as one line each, for a person to read.
     *
     * <p>{@code appendJson} makes a list, and a list read back as text answers the empty string —
     * a run's report had two empty sections under its headings for exactly that reason. An empty
     * list is written as なし, so a heading never stands over nothing.</p>
     *
     * @param listKey where the list is
     * @param intoKey where to put the lines
     * @return {@link ActionResult} with {@code success=true} always; a list that is not there is
     *         written as なし rather than treated as a fault
     */
    public ActionResult joinLines(String listKey, String intoKey) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (listKey == null || listKey.isBlank()) return new ActionResult(false, "listKey is required");
        if (intoKey == null || intoKey.isBlank()) return new ActionResult(false, "intoKey is required");
        com.fasterxml.jackson.databind.JsonNode kept = selfActorRef.json().select(listKey);
        String text;
        if (kept == null || kept.isMissingNode() || kept.isNull()) {
            text = "なし";
        } else if (kept.isArray()) {
            List<String> lines = new ArrayList<>();
            kept.forEach(one -> lines.add(one.isTextual() ? one.asText() : one.toString()));
            text = lines.isEmpty() ? "なし" : String.join("\n", lines);
        } else {
            text = kept.isTextual() ? kept.asText() : kept.toString();
        }
        selfActorRef.putJson(intoKey, text);
        return new ActionResult(true, "wrote " + text.length() + " chars to " + intoKey);
    }

    /**
     * Plan step: stops whatever a conversation is in the middle of.
     *
     * <p>A plan that has stopped waiting has to stop the work too. One run's fixer answered for
     * thirty minutes and 131,000 characters; the plan timed out, the job ended, and the
     * conversation carried on writing with the GPU to itself. The same thing the Cancel button
     * does ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}).</p>
     *
     * @param chatName the conversation's full actor name
     * @return {@link ActionResult} with {@code success=true} iff that conversation was there
     */
    public ActionResult stopChat(String chatName) {
        if (system == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        Object session = system.getIIActor(chatName + ".chat");
        if (!(session instanceof ChatSessionIIAR chatSessionIIAR)) {
            return new ActionResult(false, "chat not found: " + chatName);
        }
        chatSessionIIAR.tellNow(a -> ((ChatSession) a).cancel()).join();
        return new ActionResult(true, "told " + chatName + " to stop");
    }

    /**
     * Plan step: succeeds when this verdict says the same as the one before it.
     *
     * <p>A criterion that is not satisfied is sent back to be fixed, and the fixer usually does
     * something. When the judge answers with the same words again, the fixer's work is not the
     * problem: the two of them are reading the rule differently, and another round will produce
     * the same pair of answers ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}). Succeeding here is
     * what lets a workflow take the "they do not agree" path instead of spending its whole
     * allowance.</p>
     *
     * <p>Remembers this verdict either way, so the next round compares against it. Blank space is
     * ignored: a model that indents its answer differently is not making a new complaint.</p>
     *
     * @param key     where the verdict is
     * @param memoKey where to keep it for the next round; one per criterion
     * @return {@link ActionResult} with {@code success=true} iff this verdict matches the last
     */
    public ActionResult sameAsLast(String key, String memoKey) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (memoKey == null || memoKey.isBlank()) return new ActionResult(false, "memoKey is required");
        String now = selfActorRef.getJsonString(key);
        if (now == null) return new ActionResult(false, "nothing is kept as '" + key + "'");
        String last = selfActorRef.getJsonString(memoKey);
        selfActorRef.putJson(memoKey, now);
        boolean same = last != null && flatten(last).equals(flatten(now));
        return new ActionResult(same, same ? "the same verdict as last time" : "a different verdict");
    }

    private static String flatten(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * Plan step: says what a rewrite changed that it had no business changing.
     *
     * <p>Whether a text still means what it did is a judgement; whether its commands, identifiers
     * and numbers survived is a comparison, and a program does that exactly. A judge asked to do
     * it read 21,000 characters and answered ACCEPT for a document whose YAML block had been
     * rewritten into something that does not run ({@code WhatAProgramCanDo_260915_oo01}).</p>
     *
     * <p>Writes {@code ACCEPT} when nothing of the kind changed, and otherwise {@code REVISE:}
     * followed by what went missing or appeared — the same shape a judge's verdict has, so the
     * transitions around it are unchanged and the fixer is told in the same words.</p>
     *
     * @param beforeKey where the text as it arrived is kept
     * @param afterKey  where the text as it stands is kept
     * @param intoKey   where to put the verdict
     * @return {@link ActionResult} with {@code success=true} whenever both texts were there to
     *         compare; the verdict says whether anything changed
     */
    public ActionResult compareTexts(String beforeKey, String afterKey, String intoKey) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (beforeKey == null || beforeKey.isBlank()) return new ActionResult(false, "beforeKey is required");
        if (afterKey == null || afterKey.isBlank()) return new ActionResult(false, "afterKey is required");
        if (intoKey == null || intoKey.isBlank()) return new ActionResult(false, "intoKey is required");
        String before = selfActorRef.getJsonString(beforeKey);
        String after = selfActorRef.getJsonString(afterKey);
        if (before == null) return new ActionResult(false, "nothing is kept as '" + beforeKey + "'");
        if (after == null) return new ActionResult(false, "nothing is kept as '" + afterKey + "'");

        List<String> complaints = new ArrayList<>();
        complain(complaints, "消えたコードブロック", missing(codeBlocks(before), codeBlocks(after)));
        complain(complaints, "元の本文に無いコードブロック", missing(codeBlocks(after), codeBlocks(before)));
        complain(complaints, "消えた識別子", missing(inlineCode(before), inlineCode(after)));
        complain(complaints, "消えた名前", missing(names(before), names(after)));
        complain(complaints, "消えた数値", missing(numbers(before), numbers(after)));
        complain(complaints, "元の本文に無い数値", missing(numbers(after), numbers(before)));

        String verdict = complaints.isEmpty() ? "ACCEPT"
                : "REVISE: 元の本文にあったものが変わっています。\n" + String.join("\n", complaints);
        selfActorRef.putJson(intoKey, verdict);
        return new ActionResult(true, complaints.isEmpty() ? "nothing changed" : complaints.size() + " kind(s) changed");
    }

    /** How many of one kind are listed before the rest are summed up. */
    private static final int COMPLAINTS_SHOWN = 8;

    private static void complain(List<String> complaints, String what, List<String> found) {
        if (found.isEmpty()) return;
        List<String> shown = found.size() > COMPLAINTS_SHOWN ? found.subList(0, COMPLAINTS_SHOWN) : found;
        StringBuilder sb = new StringBuilder("- ").append(what).append(": ");
        sb.append(String.join(" / ", shown.stream().map(PlanRunner::oneLine).toList()));
        if (found.size() > shown.size()) sb.append(" ほか").append(found.size() - shown.size()).append("件");
        complaints.add(sb.toString());
    }

    /** @return what is in {@code from} and not in {@code in}, each value once, in order */
    private static List<String> missing(List<String> from, List<String> in) {
        List<String> rest = new ArrayList<>(in);
        List<String> gone = new ArrayList<>();
        for (String one : from) {
            if (!rest.remove(one) && !gone.contains(one)) gone.add(one);
        }
        return gone;
    }

    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 60 ? flat.substring(0, 60) + "…" : flat;
    }

    /** The fenced blocks of a markdown text, as their contents. */
    static List<String> codeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?ms)^```[^\\n]*\\n(.*?)^```", java.util.regex.Pattern.MULTILINE)
                .matcher(text);
        while (m.find()) blocks.add(m.group(1).strip());
        return blocks;
    }

    /** The backquoted spans of a markdown text: class names, paths, options, actor names. */
    static List<String> inlineCode(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> spans = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([^`\\n]+)`").matcher(withoutBlocks);
        while (m.find()) spans.add(m.group(1).strip());
        return spans;
    }

    /**
     * The names in a text's prose: {@code OpenAlex}, {@code ChatSession}, {@code JobQueueRegistry}.
     *
     * <p>A name written without backquotes is still a name, and one run turned {@code OpenAlex}
     * into {@code Open Alex} where nothing was quoted. Capitals inside a word are what marks one;
     * an ordinary English word is not reported, so rewriting a sentence is not a complaint.</p>
     */
    static List<String> names(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b([A-Za-z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+)\\b").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /**
     * The numbers of a text: ports, counts, sizes.
     *
     * <p>Read outside the fenced blocks, which are compared whole, and with the digits of a
     * version or a document id left out — {@code 260913_oo01} is part of a name, not a number
     * somebody measured.</p>
     */
    static List<String> numbers(String text) {
        String withoutBlocks = text.replaceAll("(?ms)^```.*?^```", "");
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<![0-9A-Za-z_])([0-9][0-9,]*)(?![0-9A-Za-z_])").matcher(withoutBlocks);
        while (m.find()) found.add(m.group(1).replace(",", ""));
        return found;
    }

    /**
     * Plan step: takes a document's front matter off, so that what is worked on is the text alone.
     *
     * <p>The {@code ---} block at the top of a document carries its {@code id}, which other
     * documents link by. Nothing in a rewrite concerns those lines, and a run that sent them to a
     * model got a document back without them. Taken off here and put back by
     * {@link #joinFrontMatter} when the result is written.</p>
     *
     * @param textKey where the whole document is in this plan's state
     * @param headKey where to put the front matter, ending with its closing line's newline;
     *                {@code ""} when the document has none
     * @param bodyKey where to put the rest; may be {@code textKey} to work on in place
     * @return {@link ActionResult} with {@code success=true} iff there was a text to split
     */
    public ActionResult takeFrontMatter(String textKey, String headKey, String bodyKey) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (textKey == null || textKey.isBlank()) return new ActionResult(false, "textKey is required");
        if (headKey == null || headKey.isBlank()) return new ActionResult(false, "headKey is required");
        if (bodyKey == null || bodyKey.isBlank()) return new ActionResult(false, "bodyKey is required");
        String text = selfActorRef.getJsonString(textKey);
        if (text == null) return new ActionResult(false, "nothing is kept as '" + textKey + "'");
        int end = frontMatterEnd(text);
        selfActorRef.putJson(headKey, end < 0 ? "" : text.substring(0, end));
        selfActorRef.putJson(bodyKey, end < 0 ? text : text.substring(end));
        return new ActionResult(true, end < 0 ? "no front matter" : "front matter is " + end + " chars");
    }

    /**
     * Where a document's front matter ends, or {@code -1} when it has none.
     *
     * <p>Only a block at the very top counts. A line of dashes further down is a horizontal rule,
     * and treating it as a closing line would cut the document in half.</p>
     *
     * @param text the whole document
     * @return the index just past the closing line's newline
     */
    private static int frontMatterEnd(String text) {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return -1;
        int from = text.indexOf('\n') + 1;
        while (from < text.length()) {
            int lineEnd = text.indexOf('\n', from);
            String line = (lineEnd < 0 ? text.substring(from) : text.substring(from, lineEnd)).strip();
            if (line.equals("---")) {
                return lineEnd < 0 ? text.length() : lineEnd + 1;
            }
            if (lineEnd < 0) return -1;
            from = lineEnd + 1;
        }
        return -1;
    }

    /**
     * Plan step: puts back what {@link #takeFrontMatter} took off.
     *
     * @param headKey where the front matter was put; an empty value adds nothing
     * @param bodyKey where the text is
     * @param intoKey where to put the two together
     * @return {@link ActionResult} with {@code success=true} iff there was a text to write
     */
    public ActionResult joinFrontMatter(String headKey, String bodyKey, String intoKey) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (headKey == null || headKey.isBlank()) return new ActionResult(false, "headKey is required");
        if (bodyKey == null || bodyKey.isBlank()) return new ActionResult(false, "bodyKey is required");
        if (intoKey == null || intoKey.isBlank()) return new ActionResult(false, "intoKey is required");
        String body = selfActorRef.getJsonString(bodyKey);
        if (body == null) return new ActionResult(false, "nothing is kept as '" + bodyKey + "'");
        String head = selfActorRef.getJsonString(headKey);
        if (head == null || head.isEmpty()) {
            selfActorRef.putJson(intoKey, body);
            return new ActionResult(true, "there was no front matter to put back");
        }
        // One blank line between the two, which is how these documents are written. What comes
        // back from a model does not always keep the one it was sent with.
        String separator = body.startsWith("\n") ? "" : "\n";
        selfActorRef.putJson(intoKey, head + separator + body);
        return new ActionResult(true, "front matter put back");
    }

    /**
     * Plan step: ends one conversation — its recorded session and the history it is holding —
     * named in full, whether or not this plan ever used it.
     *
     * <p>What {@code clearWorker} does for a slot this plan created, for a conversation that was
     * already there. Removing a conversation's actor is not enough on its own: the I/O log still
     * holds a running session for it, and {@code reopenRecordedConversationSquads} builds a ConversationSquad for every
     * running session at start-up, so the conversation would come back at the next restart. Ending
     * the session leaves every line it wrote in the database — {@code IoLogSearch} still finds
     * them — and stops it being reopened.</p>
     *
     * @param chatName the conversation's full actor name, e.g. {@code project1/chat-t1}
     * @return {@link ActionResult} with {@code success=true} iff that conversation was there
     */
    public ActionResult endConversation(String chatName) {
        if (system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        if (chatName == null || chatName.isBlank()) return new ActionResult(false, "chatName is required");
        Object session = system.getIIActor(chatName + ".chat");
        if (!(session instanceof ChatSessionIIAR chatSessionIIAR)) {
            return new ActionResult(false, "chat not found: " + chatName);
        }
        chatSessionIIAR.tell(a -> ((ChatSession) a).clearHistory()).join();
        return new ActionResult(true, "ended the conversation " + chatName);
    }

    /**
     * Plan step: empties the conversation behind one worker slot, so the next prompt starts from
     * nothing ({@code SelfContainedPromptsPerCriterion_260913_oo01}).
     *
     * <p>What a checklist run needs. Each prompt carries the rule and the whole text, so the
     * conversation's history adds nothing and costs the context it takes up. Left to accumulate, a
     * run over six criteria pushes the model past its window and the conversation answers with
     * nothing, which reaches the plan as {@code no result from chat}.</p>
     *
     * @param workerId the slot's short id, as {@link #addWorker} was given it
     * @return {@link ActionResult} with {@code success=true} iff that slot's conversation was found
     */
    public ActionResult clearWorker(String workerId) {
        if (selfActorRef == null || system == null) {
            return new ActionResult(false, "plan runner is not wired to an actor system");
        }
        if (workerId == null || workerId.isBlank()) return new ActionResult(false, "workerId is required");
        String workerName = myName + ".worker-" + workerId;
        Object slot = system.getIIActor(workerName);
        if (!(slot instanceof PlanWorkerIIAR workerIIAR)) {
            return new ActionResult(false, "no worker slot named " + workerName);
        }
        String chatName = workerIIAR.worker().targetChatName();
        Object session = system.getIIActor(chatName + ".chat");
        if (!(session instanceof ChatSessionIIAR chatSessionIIAR)) {
            return new ActionResult(false, "chat not found: " + chatName);
        }
        chatSessionIIAR.tell(a -> ((ChatSession) a).clearHistory()).join();
        return new ActionResult(true, "cleared the history of " + chatName);
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

    /** @param fileScope the range of the file system the frame steps may touch */
    public void setFileScope(com.scivicslab.chatui.agent.FileAccessScope fileScope) {
        this.fileScope = fileScope;
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
                ? null : keptUnder(key);
        String result = kept != null && !kept.isBlank() ? kept : lastReply;
        complete(result != null ? result : "(plan finished with no result)");
        return new ActionResult(true, "finished");
    }

    /**
     * What the plan stored under {@code key}, as the text to hand back.
     *
     * <p>A plan that collects things as it goes keeps them as a list, which is what
     * {@code appendJson} writes. Read as text a list answers the empty string, so a job that had
     * removed two actors reported that it had finished with no result. Anything that is not a
     * piece of text is handed back as its JSON.</p>
     *
     * @param key where in this plan's state to look
     * @return the text, or {@code null} when nothing is stored there
     */
    private String keptUnder(String key) {
        com.fasterxml.jackson.databind.JsonNode kept = selfActorRef.json().select(key);
        if (kept == null || kept.isMissingNode() || kept.isNull()) {
            return null;
        }
        return kept.isTextual() ? kept.asText() : kept.toString();
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

    // ---- Frame steps (WorkflowFromFrame_261020_oo01): thin; FrameFiller does the work on text ----

    /**
     * Plan step: opens one sub-section of a book, to be judged against the samples in the directory
     * kept under {@code samplesKey} and made into a new sample when its structure is new
     * ({@code FramesAndSamplesFromBooks_261021_oo01}).
     *
     * @param fileKey    where the path of the book's markdown file is kept
     * @param headingKey where the sub-section's title is kept, as the file writes it
     * @param nameKey    where the sample's name is kept; blank lets the writer name a new structure
     * @param samplesKey where the samples' directory is kept
     * @return {@link ActionResult} with {@code success=true} iff the file could be read and has that heading
     */
    public ActionResult bookOpen(String fileKey, String headingKey, String nameKey, String samplesKey) {
        String path = kept(fileKey);
        String heading = kept(headingKey);
        String name = kept(nameKey);
        String samples = kept(samplesKey);
        if (path == null || path.isBlank() || heading == null || heading.isBlank() || samples == null || samples.isBlank()) {
            return new ActionResult(false, "the book's file, the sub-section's heading and the samples' directory must be kept first");
        }
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path real = java.nio.file.Path.of(path.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(real)) return new ActionResult(false, "outside the readable range: " + real);
            java.util.Map<String, String> known = new java.util.TreeMap<>();
            java.nio.file.Path dir = java.nio.file.Path.of(samples.strip()).toAbsolutePath().normalize();
            if (java.nio.file.Files.isDirectory(dir)) {
                try (var files = java.nio.file.Files.list(dir)) {
                    for (java.nio.file.Path f : files.filter(f -> f.toString().endsWith(".lisp")).toList()) {
                        known.put(f.getFileName().toString().replaceFirst("\\.lisp$", ""), java.nio.file.Files.readString(f));
                    }
                }
            }
            book = new com.scivicslab.turingworkflow.plugins.rstframe.BookToRules(
                    java.nio.file.Files.readString(real), real.toString(), heading.strip(),
                    name == null || name.isBlank() ? null : name.strip(),
                    com.scivicslab.turingworkflow.plugins.rstframe.SampleCatalog.entries(known));
            questioner = book;
            return new ActionResult(true, "opened \"" + heading.strip() + "\" of " + real + ", against " + known.size() + " sample(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the sub-section: " + e.getMessage());
        }
    }

    /** The concept tree being built, while a concept-tree job runs ({@code FrameMakingWorkflow_261022_oo01}, step 2). */
    private com.scivicslab.turingworkflow.plugins.rstframe.ConceptTree conceptTree;

    /**
     * Opens a book for {@code concept-tree.yaml}: every rule file under the book's directory that has its markdown beside
     * it, in path order, becomes the material the questions walk.
     *
     * @param bookKey  where the book's directory is kept (an absolute path)
     * @param nameKey  where the book's name is kept, the root of the tree
     * @param givenKey where a file of given concepts is kept, one per line (a textbook's headings); empty for none,
     *                 and with given concepts no new concept is accepted
     */
    public ActionResult conceptOpen(String bookKey, String nameKey, String givenKey) {
        String dir = kept(bookKey);
        String name = kept(nameKey);
        String given = kept(givenKey);
        if (dir == null || dir.isBlank() || name == null || name.isBlank()) {
            return new ActionResult(false, "the book's directory and its name must be kept first");
        }
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path root = java.nio.file.Path.of(dir.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(root)) return new ActionResult(false, "outside the readable range: " + root);
            java.util.Map<String, String> files = ruleFiles(root);
            if (files.isEmpty()) return new ActionResult(false, "no rule file with its markdown beside it under " + root);
            List<String> concepts = new java.util.ArrayList<>();
            if (given != null && !given.isBlank()) {
                java.nio.file.Path g = java.nio.file.Path.of(given.strip()).toAbsolutePath().normalize();
                if (!fileScope.canRead(g)) return new ActionResult(false, "outside the readable range: " + g);
                for (String l : java.nio.file.Files.readAllLines(g)) if (!l.isBlank()) concepts.add(l.strip());
            }
            conceptTree = new com.scivicslab.turingworkflow.plugins.rstframe.ConceptTree(name.strip(), files, concepts, concepts.isEmpty());
            questioner = conceptTree;
            return new ActionResult(true, "opened " + files.size() + " rule file(s), " + conceptTree.units().size()
                    + " paragraph(s), " + concepts.size() + " given concept(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the book: " + e.getMessage());
        }
    }

    /** Writes the concept tree built so far to the file kept under {@code outKey}; an existing file is not overwritten. */
    public ActionResult conceptWrite(String outKey, String bookKey) {
        if (conceptTree == null) return new ActionResult(false, "no concept tree is being built");
        String out = kept(outKey);
        if (out == null || out.isBlank()) return new ActionResult(false, "the output file must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not write files");
        try {
            java.nio.file.Path f = java.nio.file.Path.of(out.strip()).toAbsolutePath().normalize();
            if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
            if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; a concept tree is not overwritten");
            if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
            String book = kept(bookKey);
            java.nio.file.Files.writeString(f, conceptTree.subjectsFile("the body of the rule files under " + (book == null ? "?" : book.strip())));
            selfActorRef.putJson("outcome", f.toString());
            return new ActionResult(true, "wrote " + conceptTree.concepts().size() + " concept(s) to " + f
                    + (conceptTree.notes().isEmpty() ? "" : ", " + conceptTree.notes().size() + " note(s)"));
        } catch (Exception e) {
            return new ActionResult(false, "could not write the concept tree: " + e.getMessage());
        }
    }

    /** @return every rule file under {@code root} that has its markdown beside it, path to text, in path order */
    private static java.util.Map<String, String> ruleFiles(java.nio.file.Path root) throws java.io.IOException {
        java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : walk.filter(p -> p.toString().endsWith(".lisp")).sorted().toList()) {
                java.nio.file.Path md = p.resolveSibling(p.getFileName().toString().replaceFirst("\\.lisp$", ".md"));
                if (java.nio.file.Files.isRegularFile(md)) files.put(p.toString(), java.nio.file.Files.readString(p));
            }
        }
        return files;
    }

    /** The paragraphs being assigned to concept classes, while an abstract-roles job runs ({@code ConceptClasses_261022_oo01}). */
    private com.scivicslab.turingworkflow.plugins.rstframe.SlotAssignment slotAssignment;

    /**
     * Opens a book, its concept tree and the concept classes for {@code abstract-roles.yaml}
     * ({@code FrameMakingWorkflow_261022_oo01}, step 3).
     *
     * @param bookKey    where the book's directory is kept (an absolute path)
     * @param nameKey    where the book's name is kept
     * @param treeKey    where the concept tree's file is kept, as {@code concept-tree.yaml} wrote it
     * @param classesKey where the directory of concept class files is kept ({@code rst-on-lisp/concepts}); empty for none
     */
    public ActionResult slotsOpen(String bookKey, String nameKey, String treeKey, String classesKey) {
        String dir = kept(bookKey);
        String name = kept(nameKey);
        String tree = kept(treeKey);
        String classes = kept(classesKey);
        if (dir == null || dir.isBlank() || name == null || name.isBlank() || tree == null || tree.isBlank()) {
            return new ActionResult(false, "the book's directory, its name and its concept tree must be kept first");
        }
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path root = java.nio.file.Path.of(dir.strip()).toAbsolutePath().toRealPath();
            java.nio.file.Path t = java.nio.file.Path.of(tree.strip()).toAbsolutePath().normalize();
            if (!fileScope.canRead(root)) return new ActionResult(false, "outside the readable range: " + root);
            if (!fileScope.canRead(t)) return new ActionResult(false, "outside the readable range: " + t);
            java.util.Map<String, String> files = ruleFiles(root);
            if (files.isEmpty()) return new ActionResult(false, "no rule file with its markdown beside it under " + root);
            java.util.Map<String, String> classFiles = new java.util.LinkedHashMap<>();
            if (classes != null && !classes.isBlank()) {
                java.nio.file.Path c = java.nio.file.Path.of(classes.strip()).toAbsolutePath().normalize();
                if (!fileScope.canRead(c)) return new ActionResult(false, "outside the readable range: " + c);
                try (var walk = java.nio.file.Files.walk(c)) {
                    for (java.nio.file.Path p : walk.filter(p -> p.toString().endsWith(".lisp")).sorted().toList()) {
                        classFiles.put(p.toString(), java.nio.file.Files.readString(p));
                    }
                }
            }
            slotAssignment = new com.scivicslab.turingworkflow.plugins.rstframe.SlotAssignment(name.strip(), files,
                    java.nio.file.Files.readString(t), new com.scivicslab.turingworkflow.plugins.rstframe.ConceptClasses(classFiles));
            questioner = slotAssignment;
            return new ActionResult(true, "opened " + files.size() + " rule file(s) and " + classFiles.size() + " class file(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the book: " + e.getMessage());
        }
    }

    /** Writes the classes grown and the assignments to the file kept under {@code outKey}; an existing file is not overwritten. */
    public ActionResult slotsWrite(String outKey, String treeKey) {
        if (slotAssignment == null) return new ActionResult(false, "no paragraphs are being assigned");
        String out = kept(outKey);
        if (out == null || out.isBlank()) return new ActionResult(false, "the output file must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not write files");
        try {
            java.nio.file.Path f = java.nio.file.Path.of(out.strip()).toAbsolutePath().normalize();
            if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
            if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; assignments are not overwritten");
            if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
            String tree = kept(treeKey);
            java.nio.file.Files.writeString(f, slotAssignment.resultFile(tree == null ? "?" : tree.strip()));
            selfActorRef.putJson("outcome", f.toString());
            return new ActionResult(true, "wrote " + slotAssignment.placed().size() + " concept(s), "
                    + slotAssignment.added().size() + " class(es) added, " + slotAssignment.grown().size() + " grown to " + f
                    + (slotAssignment.notes().isEmpty() ? "" : ", " + slotAssignment.notes().size() + " note(s)"));
        } catch (Exception e) {
            return new ActionResult(false, "could not write the assignments: " + e.getMessage());
        }
    }

    /**
     * Plan step: keeps the next question of the open work ({@code bookOpen} or {@code sampleOpen})
     * for the writer under {@code questionKey}, and under {@code doneKey} {@code "true"} when there is
     * none left and {@code "false"} otherwise.
     */
    public ActionResult questionNext(String questionKey, String doneKey) {
        if (questioner == null) return new ActionResult(false, "nothing is open");
        String q = questioner.nextQuestion();
        selfActorRef.putJson(questionKey, q == null ? "" : q);
        selfActorRef.putJson(doneKey, q == null ? "true" : "false");
        return new ActionResult(true, q == null ? "no question is left" : "a question of " + q.length() + " chars");
    }

    /** Plan step: hands the writer's reply kept under {@code replyKey} to the open work. */
    public ActionResult questionAnswer(String replyKey) {
        if (questioner == null) return new ActionResult(false, "nothing is open");
        String reply = kept(replyKey);
        if (reply == null) return new ActionResult(false, "nothing is kept as '" + replyKey + "'");
        questioner.answer(reply);
        return new ActionResult(true, "answered");
    }

    /**
     * Plan step: opens the sample named under {@code nameKey} in the directory kept under
     * {@code samplesKey}, with its code files ({@code <name>.<block>.txt}), to be made into a frame
     * ({@code FramesAndSamplesFromBooks_261021_oo01}).
     *
     * @return {@link ActionResult} with {@code success=true} iff the sample could be read
     */
    public ActionResult sampleOpen(String samplesKey, String nameKey) {
        String samples = kept(samplesKey);
        String name = kept(nameKey);
        if (samples == null || samples.isBlank() || name == null || name.isBlank()) {
            return new ActionResult(false, "the samples' directory and the sample's name must be kept first");
        }
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(samples.strip()).toAbsolutePath().toRealPath();
            java.nio.file.Path f = dir.resolve(name.strip() + ".lisp");
            if (!fileScope.canRead(f)) return new ActionResult(false, "outside the readable range: " + f);
            java.util.Map<String, String> code = new java.util.TreeMap<>();
            String prefix = name.strip() + ".";
            try (var files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path c : files.toList()) {
                    String n = c.getFileName().toString();
                    if (n.startsWith(prefix) && n.endsWith(".txt")) code.put(n, java.nio.file.Files.readString(c));
                }
            }
            frame = new com.scivicslab.turingworkflow.plugins.rstframe.SampleToFrame(java.nio.file.Files.readString(f), name.strip(), code);
            questioner = frame;
            return new ActionResult(true, "opened " + f + " with " + code.size() + " code file(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the sample: " + e.getMessage());
        }
    }

    /**
     * Plan step: writes the frame made from the open sample as {@code <name>.lisp} in the directory
     * kept under {@code dirKey}, never over a file already there; the file's path is kept under
     * {@code writtenKey}.
     */
    public ActionResult frameWrite(String dirKey, String writtenKey) {
        if (frame == null) return new ActionResult(false, "no sample is open");
        String dir = kept(dirKey);
        if (dir == null || dir.isBlank()) return new ActionResult(false, "the frames' directory must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not write files");
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir.strip()).toAbsolutePath().normalize();
            java.nio.file.Path f = d.resolve(frame.name() + ".lisp");
            if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
            if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; a frame is not overwritten");
            java.nio.file.Files.createDirectories(d);
            String text = frame.frameFile();
            java.nio.file.Files.writeString(f, text.endsWith("\n") ? text : text + "\n");
            selfActorRef.putJson(writtenKey, f.toString());
            return new ActionResult(true, "wrote " + f);
        } catch (Exception e) {
            return new ActionResult(false, "could not write the frame: " + e.getMessage());
        }
    }

    /**
     * Plan step: acts on the judgement of the open sub-section, in the samples' directory kept under
     * {@code dirKey}: a new structure is written as {@code <name>.lisp} with its code blocks' files
     * (never over a file already there); the same structure as a sample adds one also-seen line to
     * it; a structure not worth keeping leaves nothing. What was done is kept under
     * {@code outcomeKey}, e.g. {@code new explain-term-from-code}.
     */
    public ActionResult bookWrite(String dirKey, String outcomeKey) {
        if (book == null) return new ActionResult(false, "no book is open");
        if (book.outcome() == null) return new ActionResult(false, "the sub-section has not been judged yet");
        String dir = kept(dirKey);
        if (dir == null || dir.isBlank()) return new ActionResult(false, "the samples' directory must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not write files");
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir.strip()).toAbsolutePath().normalize();
            switch (book.outcome()) {
                case SKIP -> {
                    selfActorRef.putJson(outcomeKey, "skip: " + book.outcomeNote());
                    return new ActionResult(true, "nothing written: " + book.outcomeNote());
                }
                case SAME -> {
                    java.nio.file.Path f = d.resolve(book.outcomeName() + ".lisp");
                    if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
                    java.nio.file.Files.writeString(f, com.scivicslab.turingworkflow.plugins.rstframe.SampleCatalog
                            .withAlsoSeen(java.nio.file.Files.readString(f), book.alsoSeen()));
                    selfActorRef.putJson(outcomeKey, "same " + book.outcomeName());
                    return new ActionResult(true, "added an also-seen line to " + f);
                }
                default -> {
                    java.util.Map<java.nio.file.Path, String> out = new java.util.LinkedHashMap<>();
                    out.put(d.resolve(book.outcomeName() + ".lisp"), book.sampleFile());
                    for (java.util.Map.Entry<String, String> e : book.codeFiles().entrySet()) out.put(d.resolve(e.getKey()), e.getValue());
                    for (java.nio.file.Path f : out.keySet()) {
                        if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
                        if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; a sample is not overwritten");
                    }
                    java.nio.file.Files.createDirectories(d);
                    for (java.util.Map.Entry<java.nio.file.Path, String> e : out.entrySet()) {
                        String text = e.getValue().endsWith("\n") ? e.getValue() : e.getValue() + "\n";
                        java.nio.file.Files.writeString(e.getKey(), text);
                    }
                    selfActorRef.putJson(outcomeKey, "new " + book.outcomeName());
                    return new ActionResult(true, "wrote " + d.resolve(book.outcomeName() + ".lisp") + " and "
                            + book.codeFiles().size() + " code file(s)");
                }
            }
        } catch (Exception e) {
            return new ActionResult(false, "could not write the sample: " + e.getMessage());
        }
    }

    /** The tree of concept classes being made smaller by {@code concept-consolidate.yaml}, or {@code null}. */
    private com.scivicslab.turingworkflow.plugins.rstframe.ConceptConsolidation consolidation;
    private com.scivicslab.turingworkflow.plugins.rstframe.ConceptClasses consolidating;

    /**
     * Opens the concept classes for {@code concept-consolidate.yaml}: the class files under the directory kept under
     * {@code classesKey}, with the result of step 3 kept under {@code inputKey} read on top of them (empty for none).
     */
    public ActionResult consolidateOpen(String classesKey, String inputKey) {
        String classes = kept(classesKey);
        String input = kept(inputKey);
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
            if (classes != null && !classes.isBlank()) {
                java.nio.file.Path c = java.nio.file.Path.of(classes.strip()).toAbsolutePath().normalize();
                if (!fileScope.canRead(c)) return new ActionResult(false, "outside the readable range: " + c);
                try (var walk = java.nio.file.Files.walk(c)) {
                    for (java.nio.file.Path p : walk.filter(p -> p.toString().endsWith(".lisp")).sorted().toList()) files.put(p.toString(), java.nio.file.Files.readString(p));
                }
            }
            String result = "";
            if (input != null && !input.isBlank()) {
                java.nio.file.Path in = java.nio.file.Path.of(input.strip()).toAbsolutePath().normalize();
                if (!fileScope.canRead(in)) return new ActionResult(false, "outside the readable range: " + in);
                result = java.nio.file.Files.readString(in);
                files.put(in.toString(), result);
            }
            if (files.isEmpty()) return new ActionResult(false, "no class file and no result of step 3 to make smaller");
            if (consolidating != null) consolidating.close();
            consolidating = new com.scivicslab.turingworkflow.plugins.rstframe.ConceptClasses(files);
            int before = consolidating.names().size();
            consolidation = new com.scivicslab.turingworkflow.plugins.rstframe.ConceptConsolidation(consolidating, result);
            questioner = consolidation;
            return new ActionResult(true, "opened " + before + " class(es) from " + files.size() + " file(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the classes: " + e.getMessage());
        }
    }

    /** Writes the smaller tree, standing alone, to the file kept under {@code outKey}; an existing file is not overwritten. */
    public ActionResult consolidateWrite(String outKey, String inputKey) {
        if (consolidation == null) return new ActionResult(false, "no tree is being made smaller");
        String out = kept(outKey);
        if (out == null || out.isBlank()) return new ActionResult(false, "the output file must be kept first");
        try {
            java.nio.file.Path f = java.nio.file.Path.of(out.strip()).toAbsolutePath().normalize();
            if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
            if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; it is not overwritten");
            if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
            String input = kept(inputKey);
            java.nio.file.Files.writeString(f, consolidation.resultFile(input == null || input.isBlank() ? "the class files" : input.strip()));
            selfActorRef.putJson("outcome", f.toString());
            return new ActionResult(true, "wrote " + consolidating.names().size() + " class(es) and " + consolidating.instances().size()
                    + " value(s) to " + f + "; " + consolidation.done().size() + " change(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not write the smaller tree: " + e.getMessage());
        }
    }

    /** The book's file being made into a rule file by {@code book-to-rules.yaml}, or {@code null}. */
    private com.scivicslab.turingworkflow.plugins.rstframe.BookFileToRules bookFile;
    private java.nio.file.Path bookFilePath;

    /**
     * Plan step for {@code book-to-rules.yaml}: keeps under {@code fileKey} the first markdown file under the directory
     * kept under {@code dirKey}, in path order, that has no rule file beside it yet, and under {@code doneKey}
     * {@code "true"} when there is none left.
     */
    public ActionResult bookFileNext(String dirKey, String fileKey, String doneKey) {
        String dir = kept(dirKey);
        if (dir == null || dir.isBlank()) return new ActionResult(false, "the book's directory must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path root = java.nio.file.Path.of(dir.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(root)) return new ActionResult(false, "outside the readable range: " + root);
            try (var walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path md : walk.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    if (java.nio.file.Files.exists(ruleFileOf(md))) continue;
                    selfActorRef.putJson(fileKey, md.toString());
                    selfActorRef.putJson(doneKey, "false");
                    return new ActionResult(true, md.toString());
                }
            }
            selfActorRef.putJson(doneKey, "true");
            return new ActionResult(true, "every markdown file under " + root + " has its rule file");
        } catch (Exception e) {
            return new ActionResult(false, "could not look through the book: " + e.getMessage());
        }
    }

    /** Opens the markdown file kept under {@code fileKey} to be made into a rule file, one question at a time. */
    public ActionResult bookFileOpen(String fileKey) {
        String path = kept(fileKey);
        if (path == null || path.isBlank()) return new ActionResult(false, "the book's file must be kept first");
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        try {
            java.nio.file.Path md = java.nio.file.Path.of(path.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(md)) return new ActionResult(false, "outside the readable range: " + md);
            String id = "Book_" + md.getFileName().toString().replaceFirst("\\.md$", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "");
            bookFile = new com.scivicslab.turingworkflow.plugins.rstframe.BookFileToRules(java.nio.file.Files.readString(md), md.toString(), id);
            bookFilePath = md;
            questioner = bookFile;
            return new ActionResult(true, "opened " + md + ": " + bookFile.paragraphs() + " paragraph(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not open the book's file: " + e.getMessage());
        }
    }

    /** Writes the open file's rule file beside it, with its code blocks' files; never over a file already there. */
    public ActionResult bookFileWrite() {
        if (bookFile == null) return new ActionResult(false, "no book's file is open");
        try {
            java.util.Map<java.nio.file.Path, String> out = new java.util.LinkedHashMap<>();
            out.put(ruleFileOf(bookFilePath), bookFile.ruleFile());
            for (java.util.Map.Entry<String, String> e : bookFile.codeFiles().entrySet()) out.put(bookFilePath.resolveSibling(e.getKey()), e.getValue());
            for (java.nio.file.Path f : out.keySet()) {
                if (!fileScope.canWrite(f)) return new ActionResult(false, "outside the writable range: " + f);
                if (java.nio.file.Files.exists(f)) return new ActionResult(false, f + " already exists; it is not overwritten");
            }
            for (java.util.Map.Entry<java.nio.file.Path, String> e : out.entrySet()) java.nio.file.Files.writeString(e.getKey(), e.getValue());
            selfActorRef.putJson("outcome", ruleFileOf(bookFilePath).toString());
            return new ActionResult(true, "wrote " + ruleFileOf(bookFilePath) + " and " + bookFile.codeFiles().size() + " code file(s)");
        } catch (Exception e) {
            return new ActionResult(false, "could not write the rule file: " + e.getMessage());
        }
    }

    private static java.nio.file.Path ruleFileOf(java.nio.file.Path md) {
        return md.resolveSibling(md.getFileName().toString().replaceFirst("\\.md$", ".lisp"));
    }

    /** The sub-section being made into a sample by this job, or {@code null} when none is open. */
    private com.scivicslab.turingworkflow.plugins.rstframe.BookToRules book;

    /** The sample being made into a frame by this job, or {@code null} when none is open. */
    private com.scivicslab.turingworkflow.plugins.rstframe.SampleToFrame frame;

    /** Whichever of {@link #book} and {@link #frame} was opened last: the one the questions come from. */
    private com.scivicslab.turingworkflow.plugins.rstframe.Questioner questioner;

    private final com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller frameFiller = new com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller();

    private String kept(String key) {
        return selfActorRef == null ? null : selfActorRef.getJsonString(key);
    }

    /**
     * Plan step: takes the next blank of the rule file kept under {@code fileKey} and puts what is
     * known about it into this plan's state, as {@code <blankKey>-name}, {@code -instruction},
     * {@code -sample}, {@code -frame}, {@code -include} and {@code -kind} ({@code include}, {@code frame},
     * {@code code}, {@code items} or {@code sentence}).
     *
     * @return {@link ActionResult} with {@code success=true} iff a blank was left
     */
    /** The frame library, read once per frames directory: every rule file under it, by its path under it. */
    private com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples frameExamples;
    private java.nio.file.Path frameExamplesRoot;

    /**
     * Keeps, for the blank taken by {@code frameNext}, the paragraphs of the source books that play the same part
     * ({@code FrameExamples}, reading the frames under {@code framesRoot} and the edge lists in the {@code links/}
     * beside it): one line each, its source, a tab, its text. None is kept when the frame is not found
     * in the library or names no such part; the step still succeeds, and the blank is asked with its sample alone.
     *
     * @param requestPath the frame being filled, as an absolute path
     * @param framesRoot  the library's {@code frames/} directory; empty takes the parent of {@code frameDir} when that
     *                    directory is named {@code frames} ({@code rst-on-lisp/frames/explanation}), and else none
     * @param frameDir    the directory of the sub-frames
     * @param worksDir    the directory the books' paths are under; empty takes {@code ~/works}
     * @param max         how many examples at most
     */
    public ActionResult frameExamples(String requestPath, String framesRoot, String frameDir, String worksDir,
                                      String blankKey, String examplesKey, String max) {
        selfActorRef.putJson(examplesKey, "");
        String blankName = kept(blankKey + "-name");
        if (blankName == null || blankName.isBlank()) return new ActionResult(false, "no blank is kept as '" + blankKey + "'");
        java.nio.file.Path root;
        if (framesRoot != null && !framesRoot.isBlank()) root = java.nio.file.Path.of(framesRoot);
        else if (frameDir != null && !frameDir.isBlank() && java.nio.file.Path.of(frameDir).getParent() != null
                && "frames".equals(String.valueOf(java.nio.file.Path.of(frameDir).getParent().getFileName())))
            root = java.nio.file.Path.of(frameDir).getParent();
        else return new ActionResult(true, "no frame library to take examples from");
        root = root.toAbsolutePath().normalize();
        java.nio.file.Path works = (worksDir == null || worksDir.isBlank()
                ? java.nio.file.Path.of(System.getProperty("user.home"), "works") : java.nio.file.Path.of(worksDir)).toAbsolutePath().normalize();
        java.util.Map<String, String> frames = new java.util.LinkedHashMap<>();
        if (frameExamples == null || !root.equals(frameExamplesRoot)) {
            if (!java.nio.file.Files.isDirectory(root)) return new ActionResult(true, "no frame library at " + root);
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path p : walk.filter(p -> p.toString().endsWith(".lisp")).sorted().toList()) {
                    frames.put(root.relativize(p).toString().replace('\\', '/'), java.nio.file.Files.readString(p));
                }
            } catch (java.io.IOException e) {
                return new ActionResult(false, "could not read the frame library: " + e.getMessage());
            }
            // the edge lists sit beside frames/ in the library (FrameLinks_261022_oo01)
            List<String> links = new java.util.ArrayList<>();
            java.nio.file.Path linksDir = root.getParent() == null ? null : root.getParent().resolve("links");
            if (linksDir != null && java.nio.file.Files.isDirectory(linksDir)) {
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(linksDir)) {
                    for (java.nio.file.Path p : walk.filter(p -> p.toString().endsWith(".tsv")).sorted().toList()) {
                        links.add(java.nio.file.Files.readString(p));
                    }
                } catch (java.io.IOException e) {
                    return new ActionResult(false, "could not read the edge lists: " + e.getMessage());
                }
            }
            final java.nio.file.Path base = works;
            frameExamples = new com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples(frames, links, rel -> {
                java.nio.file.Path p = base.resolve(rel).normalize();
                try {
                    return p.startsWith(base) && java.nio.file.Files.isRegularFile(p) ? java.nio.file.Files.readString(p) : null;
                } catch (java.io.IOException e) {
                    return null;
                }
            });
            frameExamplesRoot = root;
        }
        java.nio.file.Path request = java.nio.file.Path.of(requestPath).toAbsolutePath().normalize();
        String frame = request.startsWith(root) ? root.relativize(request).toString().replace('\\', '/') : request.getFileName().toString();
        int n;
        try {
            n = Integer.parseInt(max == null || max.isBlank() ? "3" : max.strip());
        } catch (NumberFormatException e) {
            return new ActionResult(false, "max is not a number: " + max);
        }
        StringBuilder sb = new StringBuilder();
        List<com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples.Example> found = frameExamples.forBlank(frame, blankName, n);
        for (var e : found) {
            sb.append(e.source().replaceAll("[\\t\\r\\n]+", " ")).append('\t').append(e.text().replaceAll("[\\t\\r\\n]+", " ")).append('\n');
        }
        selfActorRef.putJson(examplesKey, sb.toString());
        return new ActionResult(true, found.size() + " example(s) for " + blankName);
    }

    private List<com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples.Example> examplesKept(String key) {
        String text = key == null || key.isBlank() ? null : kept(key);
        List<com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples.Example> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.add(new com.scivicslab.turingworkflow.plugins.rstframe.FrameExamples.Example(line.substring(0, tab), line.substring(tab + 1)));
        }
        return out;
    }

    public ActionResult frameNext(String fileKey, String blankKey) {
        String file = kept(fileKey);
        if (file == null) return new ActionResult(false, "nothing is kept as '" + fileKey + "'");
        com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller.Blank b = frameFiller.next(file);
        if (b == null) return new ActionResult(false, "no blank is left");
        selfActorRef.putJson(blankKey + "-name", b.name());
        selfActorRef.putJson(blankKey + "-instruction", b.instruction());
        selfActorRef.putJson(blankKey + "-sample", b.sample());
        selfActorRef.putJson(blankKey + "-frame", b.frame());
        selfActorRef.putJson(blankKey + "-code-kind", b.kind());
        selfActorRef.putJson(blankKey + "-include", b.include());
        selfActorRef.putJson(blankKey + "-kind", b.isInclude() ? "include" : b.isFrame() ? "frame" : b.isCode() ? "code" : b.isList() ? "items" : "sentence");
        return new ActionResult(true, "next blank: " + b.name());
    }

    private com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller.Blank blankKept(String blankKey) {
        String name = kept(blankKey + "-name");
        if (name == null) return null;
        return new com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller.Blank(name,
                orEmpty(kept(blankKey + "-instruction")), orEmpty(kept(blankKey + "-sample")),
                orEmpty(kept(blankKey + "-frame")), orEmpty(kept(blankKey + "-code-kind")), orEmpty(kept(blankKey + "-include")));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Plan step: writes the code in a reply into the code blank taken by {@code frameNext}. The rule
     * file kept under {@code fileKey} gets a rule that names a file beside {@code out}; the code and
     * that file's path are kept under {@code codeKey} and {@code pathKey}, for {@code writeFile} to
     * write next ({@code WorkflowFromFrame_261020_oo01}).
     *
     * @param outKey where the path of the finished rule file is kept; the code's file goes in the same directory
     * @return {@link ActionResult} with {@code success=true} iff the reply held code and the rule was written
     */
    public ActionResult frameFillCode(String fileKey, String blankKey, String replyKey, String outKey,
                                      String codeKey, String pathKey) {
        String file = kept(fileKey);
        String reply = kept(replyKey);
        String name = kept(blankKey + "-name");
        String out = kept(outKey);
        if (file == null || reply == null || name == null || out == null) {
            return new ActionResult(false, "the rule file, a reply, a blank and the output path must all be kept first");
        }
        String code = frameFiller.codeOf(reply);
        if (code.isBlank()) return new ActionResult(false, "the reply held no code");
        String fileName = frameFiller.codeFileName(name);
        java.nio.file.Path dir = java.nio.file.Path.of(out.strip()).toAbsolutePath().getParent();
        try {
            selfActorRef.putJson(fileKey, frameFiller.fillWithFile(file, name, fileName));
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        }
        selfActorRef.putJson(codeKey, code);
        selfActorRef.putJson(pathKey, (dir == null ? java.nio.file.Path.of(fileName) : dir.resolve(fileName)).toString());
        return new ActionResult(true, "filled " + name + " with " + fileName);
    }

    /**
     * Plan step: builds what the writer is asked for the blank taken by {@code frameNext}, and puts
     * it into this plan's state under {@code promptKey}. A frame blank is asked for the list of
     * items; any other blank for one piece of text (about the item, when it is a leaf of a copy of
     * a sub-frame).
     *
     * @param maxItems how many items a list may have at most
     * @return {@link ActionResult} with {@code success=true} iff the prompt was built
     */
    public ActionResult framePrompt(String fileKey, String blankKey, String subjectKey, String namesKey,
                                    String promptKey, String language, String maxItems) {
        return framePrompt(fileKey, blankKey, subjectKey, namesKey, promptKey, language, maxItems, "");
    }

    /**
     * As {@link #framePrompt(String, String, String, String, String, String, String)}, with the examples
     * {@link #frameExamples} kept under {@code examplesKey} shown to a sentence blank; an empty key shows none.
     */
    public ActionResult framePrompt(String fileKey, String blankKey, String subjectKey, String namesKey,
                                    String promptKey, String language, String maxItems, String examplesKey) {
        String file = kept(fileKey);
        String subject = kept(subjectKey);
        com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller.Blank blank = blankKept(blankKey);
        if (file == null || subject == null || blank == null) {
            return new ActionResult(false, "the rule file, the subject and the blank must all be kept first");
        }
        String lang = language == null || language.isBlank() ? "Japanese" : language.strip();
        String prompt;
        if (blank.isFrame()) {
            int max;
            try {
                max = Integer.parseInt(maxItems == null || maxItems.isBlank() ? "5" : maxItems.strip());
            } catch (NumberFormatException e) {
                return new ActionResult(false, "maxItems is not a number: " + maxItems);
            }
            prompt = frameFiller.listPrompt(blank, subject, max, lang);
        } else if (blank.isCode()) {
            prompt = frameFiller.codePrompt(file, blank, subject);
        } else if (blank.isList()) {
            prompt = frameFiller.itemsPrompt(file, blank, subject, lang);
        } else {
            String names = kept(namesKey);
            prompt = frameFiller.sentencePrompt(file, blank, subject,
                    names == null || names.isBlank() ? List.of() : List.of(names.split("\\R")), lang,
                    examplesKept(examplesKey));
        }
        selfActorRef.putJson(promptKey, prompt);
        return new ActionResult(true, "built a prompt of " + prompt.length() + " chars");
    }

    /**
     * Plan step: writes the reply into the blank taken by {@code frameNext}, in the rule file kept
     * under {@code fileKey}, which is replaced by the result.
     *
     * @return {@link ActionResult} with {@code success=true} iff the blank was filled
     */
    public ActionResult frameFill(String fileKey, String blankKey, String replyKey) {
        String file = kept(fileKey);
        String reply = kept(replyKey);
        String name = kept(blankKey + "-name");
        if (file == null || reply == null || name == null || reply.isBlank()) {
            return new ActionResult(false, "the rule file, a reply and a blank must all be kept first");
        }
        try {
            selfActorRef.putJson(fileKey, frameFiller.fill(file, name, reply));
            return new ActionResult(true, "filled " + name);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    /**
     * Plan step: replaces the include blank taken by {@code frameNext} with one copy of the child frame
     * kept under {@code frameTextKey}, in the rule file kept under {@code fileKey}. Nothing is asked.
     *
     * @return {@link ActionResult} with {@code success=true} iff the blank was replaced
     */
    public ActionResult frameInclude(String fileKey, String blankKey, String frameTextKey) {
        String file = kept(fileKey);
        String frameText = kept(frameTextKey);
        String name = kept(blankKey + "-name");
        if (file == null || frameText == null || name == null) {
            return new ActionResult(false, "the rule file, the child frame and a blank must all be kept first");
        }
        try {
            selfActorRef.putJson(fileKey, frameFiller.include(file, name, frameText));
            return new ActionResult(true, "included the child frame at " + name);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    /**
     * Plan step: writes the items in a reply, one per line, into the bulleted or numbered list blank
     * taken by {@code frameNext}, in the rule file kept under {@code fileKey}, which is replaced by the result.
     *
     * @return {@link ActionResult} with {@code success=true} iff the reply held items and the blank was filled
     */
    public ActionResult frameFillItems(String fileKey, String blankKey, String replyKey) {
        String file = kept(fileKey);
        String reply = kept(replyKey);
        String name = kept(blankKey + "-name");
        if (file == null || reply == null || name == null) {
            return new ActionResult(false, "the rule file, a reply and a blank must all be kept first");
        }
        List<String> items = frameFiller.parseItems(reply);
        try {
            selfActorRef.putJson(fileKey, frameFiller.fillList(file, name, items));
            return new ActionResult(true, "filled " + name + " with " + items.size() + " item(s)");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        }
    }

    /**
     * Plan step: turns the frame blank taken by {@code frameNext} into one copy of its sub-frame per
     * item in the reply, in the rule file kept under {@code fileKey}. The item names are kept under
     * {@code namesKey}, one per line, for the prompts of the copies' blanks.
     *
     * @param frameTextKey where the sub-frame's text is kept
     * @param maxItems     how many items at most
     * @return {@link ActionResult} with {@code success=true} iff the blank was expanded
     */
    public ActionResult frameExpand(String fileKey, String blankKey, String frameTextKey, String replyKey,
                                    String namesKey, String maxItems) {
        String file = kept(fileKey);
        String frameText = kept(frameTextKey);
        String reply = kept(replyKey);
        String name = kept(blankKey + "-name");
        if (file == null || frameText == null || reply == null || name == null) {
            return new ActionResult(false, "the rule file, the sub-frame, a reply and a blank must all be kept first");
        }
        int max;
        try {
            max = Integer.parseInt(maxItems == null || maxItems.isBlank() ? "5" : maxItems.strip());
        } catch (NumberFormatException e) {
            return new ActionResult(false, "maxItems is not a number: " + maxItems);
        }
        List<String> names = frameFiller.parseNames(reply, max);
        if (names.isEmpty()) return new ActionResult(false, "the reply named no item");
        try {
            selfActorRef.putJson(fileKey, frameFiller.expand(file, name, frameText, names));
            selfActorRef.putJson(namesKey, String.join("\n", names));
            return new ActionResult(true, "expanded " + name + " into " + names.size() + " copies");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, e.getMessage());
        }
    }
}
