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
     * holds a running session for it, and {@code reopenRecordedTabs} builds a tab for every
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
            // A text file ends with a newline. What a model answers with does not always have one,
            // and without it git reports the last line as changed on every run.
            String whole = text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
            java.nio.file.Files.writeString(file, whole);
            return new ActionResult(true, "wrote " + whole.length() + " chars to " + file);
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
        return listFiles(dir, suffix, key, null);
    }

    /**
     * The same, walking into subdirectories to the given depth.
     *
     * @param dir    the directory to list
     * @param suffix what a name must end with; every file when blank
     * @param key    where to put the list
     * @param depth  how many levels to walk; {@code 1} (this directory alone) when absent. A corpus
     *               that keeps one document per directory needs {@code 2}
     */
    public ActionResult listFiles(String dir, String suffix, String key, String depth) {
        if (selfActorRef == null) return new ActionResult(false, "plan runner is not wired to an actor system");
        if (dir == null || dir.isBlank()) return new ActionResult(false, "dir is required");
        if (key == null || key.isBlank()) return new ActionResult(false, "key is required");
        if (fileScope == null) return new ActionResult(false, "this job may not read files");
        String ending = suffix == null ? "" : suffix.strip();
        try {
            java.nio.file.Path root = java.nio.file.Path.of(dir.strip()).toAbsolutePath().toRealPath();
            if (!fileScope.canRead(root)) return new ActionResult(false, "outside the readable range: " + root);
            int levels = 1;
            if (depth != null && !depth.isBlank()) {
                try {
                    levels = Math.max(1, Integer.parseInt(depth.strip()));
                } catch (NumberFormatException e) {
                    return new ActionResult(false, "depth must be a whole number, not '" + depth + "'");
                }
            }
            java.util.List<String> found = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root, levels)) {
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
}
