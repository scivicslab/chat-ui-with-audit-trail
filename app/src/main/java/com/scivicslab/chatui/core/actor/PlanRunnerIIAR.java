package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;

/**
 * {@code IIActorRef} adapter that lets a plan's workflow call its own {@link PlanRunner} by
 * {@code actor: this} — the same bridge {@link ChatSessionIIAR} provides for a conversation.
 *
 * <p>Each plan step is one {@code @Action} method whose Javadoc the build writes into the action
 * manifest, so the Actions tab can show what the step does, what its arguments are and how a
 * workflow writes it ({@code ActionCatalogWithJavadoc_260930_oo01}). Every step takes its arguments
 * as a JSON array, in the order the {@code @param args} line gives; the bundled workflows write
 * them as {@code arguments: ["first", "second"]}. A {@code jexl:} text reaches the step as the value
 * the expression answers. The engine's own steps — {@code putJson}, {@code appendJson},
 * {@code onlyIf}, {@code call}, ... — come from {@link InterpreterIIAR} and are listed with these.</p>
 */
public class PlanRunnerIIAR extends InterpreterIIAR {

    public PlanRunnerIIAR(String actorName, PlanRunner runner, IIActorSystem system) {
        super(actorName, runner, system);
        // Without this, "actor: this" falls through Interpreter.action()'s selfActorRef==null path
        // and looks for an actor literally named "this" (see ChatSessionIIAR's own note).
        runner.setSelfActorRef(this);
    }

    private PlanRunner runner() { return (PlanRunner) object; }

    /**
     * Sends a prompt to a conversation and waits for its reply; the message is the reply.
     *
     * <p>The target is a qualified conversation name, since a plan is written knowing which
     * conversations it drives. Fails when the conversation did not reply. For several
     * conversations at once, give each a worker slot with {@code addWorker} and drive them with
     * {@code apply}.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: askChat
     *   arguments: ["project1/chat-02", "Summarize the file you were given."]
     * }</pre>
     *
     * @param args {@code [target, prompt]}: the conversation's qualified name, then what to ask it
     */
    @Action("askChat")
    public ActionResult askChat(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().askChat(at(a, 0), at(a, 1));
    }

    /**
     * Gives this plan a worker slot standing in for one conversation; the message names the slot.
     *
     * <p>The slot is this plan's child {@code <plan>.worker-<id>}, so several slots can be driven
     * at once with {@code apply} and the pattern {@code *.worker-*}, and one slot can be asked with
     * {@code askWorker}. Fails when the slot could not be created.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: addWorker
     *   arguments: ["a", "project1/chat-02"]
     * }</pre>
     *
     * @param args {@code [workerId, chatName]}: a short id for the slot, then the conversation it
     *             forwards to
     */
    @Action("addWorker")
    public ActionResult addWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().addWorker(at(a, 0), at(a, 1));
    }

    /**
     * Hands a prompt to one worker slot and waits for its reply; the message is the reply.
     *
     * <p>The slot is one {@code addWorker} made. The prompt is a top-level argument, so a
     * {@code jexl:} expression that builds it from the plan's state is evaluated — inside an
     * {@code apply} it would not be. Fails when the slot does not exist or did not reply.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: askWorker
     *   arguments: ["judge", 'jexl:"Does this text follow the rule?\n\n" + state.getString("text")']
     * }</pre>
     *
     * @param args {@code [workerId, prompt]}: the slot's id, then what to ask the conversation behind it
     */
    @Action("askWorker")
    public ActionResult askWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().askWorker(at(a, 0), at(a, 1));
    }

    /**
     * Puts one conversation on a named model.
     *
     * <p>The change is written to that conversation's settings record as any other would be. A
     * blank model leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatModel
     *   arguments: ['jexl:state.getString("fixer")', 'jexl:state.getString("fixerModel")']
     * }</pre>
     *
     * @param args {@code [chatName, model]}: the conversation's qualified name, then the model
     */
    @Action("setChatModel")
    public ActionResult setChatModel(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatModel(at(a, 0), at(a, 1));
    }

    /**
     * Puts a conversation on another agent loop. The loop decides how the conversation runs and how
     * each prompt it sends is built, so this is how a plan gives its writer a loop that sends the
     * turn's own text and nothing else.
     * <p>Fails when the conversation does not exist or the loop file is not bundled; then the
     * conversation keeps the loop it had. A blank loop leaves the conversation as it is.</p>
     * <pre>{@code
     * - actor: this
     *   method: setChatAgentLoop
     *   arguments: ["project1/chat-02", "plain-text-loop.yaml"]
     * }</pre>
     * @param args {@code [chatName, loopFile]}: the conversation's qualified name, then the agent loop's file
     */
    @Action("setChatAgentLoop")
    public ActionResult setChatAgentLoop(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatAgentLoop(at(a, 0), at(a, 1));
    }

    /**
     * Says whether one conversation asks its model to skip the thinking phase.
     *
     * <p>A blank value leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatNoThink
     *   arguments: ["project1/chat-02", "true"]
     * }</pre>
     *
     * @param args {@code [chatName, value]}: the conversation's qualified name, then {@code true} or {@code false}
     */
    @Action("setChatNoThink")
    public ActionResult setChatNoThink(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatNoThink(at(a, 0), at(a, 1));
    }

    /**
     * Says how hard one conversation's provider should work on an answer, where it has such a setting.
     *
     * <p>A blank effort leaves the conversation as it was. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatEffort
     *   arguments: ["project1/chat-02", "high"]
     * }</pre>
     *
     * @param args {@code [chatName, effort]}: the conversation's qualified name, then the level as
     *             its provider names it
     */
    @Action("setChatEffort")
    public ActionResult setChatEffort(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatEffort(at(a, 0), at(a, 1));
    }

    /**
     * Puts one conversation on a named provider kind, with a tool set.
     *
     * <p>A blank kind leaves the conversation as it was; a blank tool set gives the kind's own
     * default. Fails when the change could not be made, for example when no plugin provides the kind.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: setChatProvider
     *   arguments: ["project1/chat-02", "claude", ""]
     * }</pre>
     *
     * @param args {@code [chatName, kind, tools]}: the conversation's qualified name, then
     *             {@code openai-compat}, {@code claude} or {@code codex}, then the tool set
     */
    @Action("setChatProvider")
    public ActionResult setChatProvider(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().setChatProvider(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Writes a list the plan built up as one line each, for a person to read.
     *
     * <p>The list is one {@code appendJson} filled. An empty list is written as なし, so a heading
     * never stands over nothing.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: joinLines
     *   arguments: ["done", "doneText"]
     * }</pre>
     *
     * @param args {@code [listKey, intoKey]}: where the list is in this plan's state, then where to
     *             put the lines
     */
    @Action("joinLines")
    public ActionResult joinLines(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().joinLines(at(a, 0), at(a, 1));
    }

    /**
     * Stops whatever a conversation is in the middle of — the same thing the Cancel button does.
     *
     * <p>Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: stopChat
     *   arguments: ['jexl:state.getString("judge")']
     * }</pre>
     *
     * @param args {@code [chatName]}: the conversation's qualified name
     */
    @Action("stopChat")
    public ActionResult stopChat(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().stopChat(at(a, 0));
    }

    /**
     * Succeeds when this verdict says the same as the one before it.
     *
     * <p>When a judge answers with the same words twice, another round will not change them; the
     * success lets the workflow take the "they do not agree" path. Whitespace and markdown emphasis
     * are ignored. Keep one memo key per criterion.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: sameAsLast
     *   arguments: ["verdict", "last-verdict-1"]
     * }</pre>
     *
     * @param args {@code [key, memoKey]}: where the verdict is, then where to keep it for the next round
     */
    @Action("sameAsLast")
    public ActionResult sameAsLast(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().sameAsLast(at(a, 0), at(a, 1));
    }

    /**
     * Says what a rewrite changed that it had no business changing; the verdict is stored.
     *
     * <p>Compares the fenced blocks, the backquoted spans, the proper names and the numbers of two
     * texts, and writes {@code ACCEPT}, or {@code REVISE:} followed by what went missing or appeared,
     * in the shape a judge's verdict has. Fails only when a text was not there.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: compareTexts
     *   arguments: ["original", "text", "verdict"]
     * }</pre>
     *
     * @param args {@code [beforeKey, afterKey, intoKey]}: the text as it arrived, the text as it
     *             stands, then where to put the verdict
     */
    @Action("compareTexts")
    public ActionResult compareTexts(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().compareTexts(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Takes a document's front matter off, so that what is worked on is the text alone.
     *
     * <p>{@code joinFrontMatter} puts it back. The body key may be the text key, to work in place.
     * Fails when there was no text to split.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: takeFrontMatter
     *   arguments: ["text", "head", "text"]
     * }</pre>
     *
     * @param args {@code [textKey, headKey, bodyKey]}: where the whole document is, where to put
     *             the front matter, then where to put the rest
     */
    @Action("takeFrontMatter")
    public ActionResult takeFrontMatter(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().takeFrontMatter(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Puts back what {@code takeFrontMatter} took off.
     *
     * <p>An empty head adds nothing. Fails when there was no text to write.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: joinFrontMatter
     *   arguments: ["head", "text", "whole"]
     * }</pre>
     *
     * @param args {@code [headKey, bodyKey, intoKey]}: where the front matter was put, where the
     *             text is, then where to put the two together
     */
    @Action("joinFrontMatter")
    public ActionResult joinFrontMatter(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().joinFrontMatter(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Ends one conversation: its recorded session and the history it is holding.
     *
     * <p>Every line it wrote stays in the I/O log; the session is closed, so the conversation is not
     * reopened at the next restart. Fails when there is no such conversation.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: endConversation
     *   arguments: ['jexl:state.getString("name")']
     * }</pre>
     *
     * @param args {@code [chatName]}: the conversation's qualified name
     */
    @Action("endConversation")
    public ActionResult endConversation(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().endConversation(at(a, 0));
    }

    /**
     * Empties the conversation behind one worker slot, so the next prompt starts from nothing.
     *
     * <p>Between self-contained prompts the history adds nothing and costs context. Fails when the
     * slot's conversation was not found.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: clearWorker
     *   arguments: ["fixer"]
     * }</pre>
     *
     * @param args {@code [workerId]}: the slot's id, as {@code addWorker} was given it
     */
    @Action("clearWorker")
    public ActionResult clearWorker(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().clearWorker(at(a, 0));
    }

    /**
     * Puts what one worker slot last replied into this plan's state.
     *
     * <p>The middle of a chain: one role's answer, kept under a key, becomes the next role's input.
     * {@code collectWorkerReplies} is the end of a fan-out and gathers every slot at once. Fails when
     * the slot does not exist or has not replied.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: keepWorkerReply
     *   arguments: ["judge", "verdict"]
     * }</pre>
     *
     * @param args {@code [workerId, key]}: the slot's id, then where to put the reply
     */
    @Action("keepWorkerReply")
    public ActionResult keepWorkerReply(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().keepWorkerReply(at(a, 0), at(a, 1));
    }

    /**
     * Reads a file into this plan's state, so the work that follows needs no person to paste it in.
     *
     * <p>The path must be within the instance's read roots. Fails when the file could not be read.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: readFile
     *   arguments: ["/home/devteam/works/notes/rule-1.md", "rule-1"]
     * }</pre>
     *
     * @param args {@code [path, key]}: where to read from, then where to put the text
     */
    @Action("readFile")
    public ActionResult readFile(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().readFile(at(a, 0), at(a, 1));
    }

    /**
     * Writes what the plan kept under a key to a file, creating the directories above it.
     *
     * <p>The path must be under the instance's write root. Fails when the file could not be written.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: writeFile
     *   arguments: ['jexl:state.getString("orig")', "original"]
     * }</pre>
     *
     * @param args {@code [path, key]}: where to write, then which value of this plan's state to write
     */
    @Action("writeFile")
    public ActionResult writeFile(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().writeFile(at(a, 0), at(a, 1));
    }

    /**
     * Puts the files of one directory into this plan's state, one path per line.
     *
     * <p>{@code takeNext} then works through them. Fails when no file matched.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: listFiles
     *   arguments: ['jexl:state.getString("dir")', ".md", "files", "3"]
     * }</pre>
     *
     * @param args {@code [dir, suffix, key, depth]}: the directory, what a name must end with (every
     *             file when blank), where to put the list, then how many levels to walk (this
     *             directory alone when absent)
     */
    @Action("listFiles")
    public ActionResult listFiles(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().listFiles(at(a, 0), at(a, 1), at(a, 2), a.length() > 3 ? a.getString(3) : null);
    }

    /**
     * Moves the first line of a list into a key and shortens the list; fails when the list is empty.
     *
     * <p>The failure is what ends a loop over files: the transition falls through when nothing is left.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: takeNext
     *   arguments: ["files", "file"]
     * }</pre>
     *
     * @param args {@code [listKey, key]}: where the remaining lines are, then where to put the line taken
     */
    @Action("takeNext")
    public ActionResult takeNext(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().takeNext(at(a, 0), at(a, 1));
    }

    /**
     * Copies one value of this plan's state to another key.
     *
     * <p>Keeps a text as it arrived before a role rewrites it, so a judge can be given both. Fails
     * when there was nothing to copy.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: copyState
     *   arguments: ["text", "original"]
     * }</pre>
     *
     * @param args {@code [from, to]}: which value to copy, then where to put the copy
     */
    @Action("copyState")
    public ActionResult copyState(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().copyState(at(a, 0), at(a, 1));
    }

    /**
     * Succeeds when the value under a key begins with the expected text, compared without case or
     * surrounding space.
     *
     * <p>Turns a verdict kept in the state into a transition: {@code ACCEPT} takes this transition
     * and anything else falls through to the one written after it.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: checkState
     *   arguments: ["verdict", "ACCEPT"]
     * }</pre>
     *
     * @param args {@code [key, expected]}: where the value is, then what it must begin with
     */
    @Action("checkState")
    public ActionResult checkState(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().checkState(at(a, 0), at(a, 1));
    }

    /**
     * Counts one attempt at a key and succeeds while the budget holds.
     *
     * <p>Each criterion can have its own key, so one loop does not spend another's budget. Fails on
     * the attempt past the limit.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: countUp
     *   arguments: ["tries-1", 'jexl:state.getString("tries")']
     * }</pre>
     *
     * @param args {@code [key, limit]}: where the count is kept, then how many attempts are allowed
     */
    @Action("countUp")
    public ActionResult countUp(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().countUp(at(a, 0), at(a, 1));
    }

    /**
     * Gathers what every worker slot replied into this plan's result, in slot-name order.
     *
     * <p>The end of a fan-out over {@code apply}. Fails when no slot had a reply.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: collectWorkerReplies
     * }</pre>
     *
     * @param args ignored
     */
    @Action("collectWorkerReplies")
    public ActionResult collectWorkerReplies(String args) {
        return runner().collectWorkerReplies();
    }

    /**
     * Terminal step: hands the plan's product back to whoever is waiting on it.
     *
     * <p>With a key, the product is what this plan's state holds there; without one, the last reply
     * a step received. Put it on the transition into {@code end}.</p>
     *
     * <pre>{@code
     * - actor: this
     *   method: finish
     *   arguments: ["text"]
     * }</pre>
     *
     * @param args {@code [key]}: where the result is in this plan's state; empty for the last reply
     */
    @Action("finish")
    public ActionResult finish(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().finish(a.length() > 0 ? a.getString(0) : null);
    }

    /**
     * Fallback step: tells whoever is waiting that the plan failed, instead of leaving them to time out.
     *
     * <p>Put it on the transition a failed {@code askChat} or {@code askWorker} falls through to.</p>
     *
     * <pre>{@code
     * - states: ["ask", "end"]
     *   actions:
     *     - actor: this
     *       method: reportFailure
     * }</pre>
     *
     * @param args ignored
     */
    @Action("reportFailure")
    public ActionResult reportFailure(String args) {
        return runner().reportFailure();
    }

    /** An action's {@code arguments} as a JSON array, empty if it has none. */
    /**
     * Takes the next blank (a rule that carries an {@code :instruction}) of a rule file kept in this
     * plan's state, and keeps what is known about it: {@code <blank>-name}, {@code -instruction},
     * {@code -sample}, {@code -frame} and {@code -kind} (<code>frame</code> or <code>sentence</code>).
     * <p>Fails when no blank is left, which is how a loop over a frame ends.</p>
     * <pre>{@code
     * - actor: this
     *   method: frameNext
     *   arguments: ["cur", "blank"]
     * }</pre>
     * @param args {@code [fileKey, blankKey]}: where the rule file is kept, then the prefix to keep the blank under
     */
    @Action("frameNext")
    public ActionResult frameNext(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameNext(at(a, 0), at(a, 1));
    }

    /**
     * Builds what the writer is asked for the blank taken by {@code frameNext} and keeps it.
     * <p>A frame blank is asked for the list of items to explain; any other blank for one piece of
     * text, about the item when the blank is a leaf of a copy of a sub-frame. The prompt is in
     * English and asks for the answer in the given language.</p>
     * <pre>{@code
     * - actor: this
     *   method: framePrompt
     *   arguments: ["cur", "blank", "subject", "names", "prompt", "Japanese", "5", "examples"]
     * }</pre>
     * @param args {@code [fileKey, blankKey, subjectKey, namesKey, promptKey, language, maxItems, examplesKey]}:
     *             where the rule file, the blank and the material to explain are kept, where the item
     *             names are kept, where to put the prompt, the language to write in, how many
     *             items a list may have, then where {@code frameExamples} kept the examples (optional)
     */
    /**
     * Opens a book for {@code concept-tree.yaml}.
     * @param args {@code [bookKey, nameKey, givenKey]}
     */
    @Action("conceptOpen")
    public ActionResult conceptOpen(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().conceptOpen(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Writes the concept tree built by {@code concept-tree.yaml}.
     * @param args {@code [outKey, bookKey]}
     */
    @Action("conceptWrite")
    public ActionResult conceptWrite(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().conceptWrite(at(a, 0), at(a, 1));
    }

    @Action("framePrompt")
    public ActionResult framePrompt(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().framePrompt(at(a, 0), at(a, 1), at(a, 2), at(a, 3), at(a, 4), at(a, 5), at(a, 6), at(a, 7));
    }

    /**
     * Keeps the paragraphs of the source books that play the same part as the blank taken by {@code frameNext}.
     * <pre>{@code
     * - actor: this
     *   method: frameExamples
     *   arguments: ['jexl:state.getString("request")', "", 'jexl:state.getString("frameDir")', "", "blank", "examples", "3"]
     * }</pre>
     * @param args {@code [requestPath, framesRoot, frameDir, worksDir, blankKey, examplesKey, max]}; an empty
     *             {@code framesRoot} is the parent of {@code frameDir} when it is named {@code frames}, an empty {@code worksDir} is {@code ~/works}
     */
    @Action("frameExamples")
    public ActionResult frameExamples(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameExamples(at(a, 0), at(a, 1), at(a, 2), at(a, 3), at(a, 4), at(a, 5), at(a, 6));
    }

    /**
     * Writes a reply into the blank taken by {@code frameNext}; the rule file kept in this plan's
     * state becomes the file with that rule written. The rest of the file is untouched.
     * <p>Fails when the reply is empty or the rule is not in the file.</p>
     * <pre>{@code
     * - actor: this
     *   method: frameFill
     *   arguments: ["cur", "blank", "reply"]
     * }</pre>
     * @param args {@code [fileKey, blankKey, replyKey]}: where the rule file, the blank and the reply are kept
     */
    @Action("frameFill")
    public ActionResult frameFill(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameFill(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Replaces an include blank taken by {@code frameNext} with one copy of the child frame. Nothing is asked.
     * @param args {@code [fileKey, blankKey, frameTextKey]}
     */
    @Action("frameInclude")
    public ActionResult frameInclude(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameInclude(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Writes the items in a reply, one per line, into a bulleted or numbered list blank taken by {@code frameNext}.
     * @param args {@code [fileKey, blankKey, replyKey]}
     */
    @Action("frameFillItems")
    public ActionResult frameFillItems(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameFillItems(at(a, 0), at(a, 1), at(a, 2));
    }

    /**
     * Opens one sub-section of a book, to be made into a sample.
     * <pre>{@code
     * - actor: this
     *   method: bookOpen
     *   arguments: ["book", "heading", "name", "samples"]
     * }</pre>
     * @param args {@code [fileKey, headingKey, nameKey, samplesKey]}
     */
    @Action("bookOpen")
    public ActionResult bookOpen(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().bookOpen(at(a, 0), at(a, 1), at(a, 2), at(a, 3));
    }

    /**
     * Keeps the next question of the open sub-section or sample, and whether none is left.
     * @param args {@code [questionKey, doneKey]}
     */
    @Action("questionNext")
    public ActionResult questionNext(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().questionNext(at(a, 0), at(a, 1));
    }

    /**
     * Hands the writer's reply to the open sub-section or sample.
     * @param args {@code [replyKey]}
     */
    @Action("questionAnswer")
    public ActionResult questionAnswer(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().questionAnswer(at(a, 0));
    }

    /**
     * Opens one sample, with its code files, to be made into a frame.
     * <pre>{@code
     * - actor: this
     *   method: sampleOpen
     *   arguments: ["samples", "name"]
     * }</pre>
     * @param args {@code [samplesKey, nameKey]}
     */
    @Action("sampleOpen")
    public ActionResult sampleOpen(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().sampleOpen(at(a, 0), at(a, 1));
    }

    /**
     * Writes the frame made from the open sample into a directory, never over a file already there.
     * @param args {@code [dirKey, writtenKey]}
     */
    @Action("frameWrite")
    public ActionResult frameWrite(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameWrite(at(a, 0), at(a, 1));
    }

    /**
     * Acts on the judgement: writes a new sample, adds an also-seen line to the same one, or leaves nothing.
     * @param args {@code [dirKey, outcomeKey]}
     */
    @Action("bookWrite")
    public ActionResult bookWrite(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().bookWrite(at(a, 0), at(a, 1));
    }

    /**
     * Writes the code in a reply into the code blank taken by {@code frameNext}: the rule becomes
     * {@code :file "<name>.txt" :kind code-block}, and the code and the path to write it to are kept
     * for {@code writeFile}.
     * <pre>{@code
     * - actor: this
     *   method: frameFillCode
     *   arguments: ["cur", "blank", "reply", "out", "code-text", "code-path"]
     * }</pre>
     * @param args {@code [fileKey, blankKey, replyKey, outKey, codeKey, pathKey]}
     */
    @Action("frameFillCode")
    public ActionResult frameFillCode(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameFillCode(at(a, 0), at(a, 1), at(a, 2), at(a, 3), at(a, 4), at(a, 5));
    }

    /**
     * Turns the frame blank taken by {@code frameNext} into one copy of its sub-frame per item the
     * reply lists, in the rule file kept in this plan's state. The item names are kept, one per
     * line, for the prompts of the copies' blanks.
     * <p>The copies are named {@code <blank>/<n>} and {@code <blank>/<n>/<suffix>}, and keep their
     * {@code :instruction} and {@code :sample}, so the loop fills them one by one.</p>
     * <pre>{@code
     * - actor: this
     *   method: frameExpand
     *   arguments: ["cur", "blank", "frame-text", "reply", "names", "5"]
     * }</pre>
     * @param args {@code [fileKey, blankKey, frameTextKey, replyKey, namesKey, maxItems]}: where the
     *             rule file, the blank, the sub-frame's text and the reply are kept, where to keep the
     *             item names, then how many items at most
     */
    @Action("frameExpand")
    public ActionResult frameExpand(String args) {
        org.json.JSONArray a = arguments(args);
        return runner().frameExpand(at(a, 0), at(a, 1), at(a, 2), at(a, 3), at(a, 4), at(a, 5));
    }

    private static org.json.JSONArray arguments(String arg) {
        if (arg == null || arg.isBlank()) return new org.json.JSONArray();
        try {
            return new org.json.JSONArray(arg);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    /** The {@code i}th element as text, or "" when the array is shorter. */
    private static String at(org.json.JSONArray a, int i) {
        return a.length() > i ? a.getString(i) : "";
    }
}
