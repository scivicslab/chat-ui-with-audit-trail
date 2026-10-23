package com.scivicslab.chatui.core.actor;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The workflow actions of {@link Chats}, registered at start-up as {@code chats}. A conversation is named in full; a
 * question's reply is the message, for the workflow to keep:
 *
 * <pre>{@code
 * - actor: chats
 *   method: ask
 *   arguments: {chat: 'jexl:state.getString("writer")', prompt: 'jexl:state.getString("prompt")', waiter: 'jexl:self'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: reply, value: "jexl:result"}
 * }</pre>
 *
 * <p>{@code ask} runs at once rather than in this actor's mailbox: it waits for a conversation's whole turn, and the
 * jobs of every project ask through this one actor at the same time. {@link Chats} holds nothing that changes, so
 * they do not step on each other.</p>
 */
public class ChatsIIAR extends IIActorRef<Chats> {

    public ChatsIIAR(String actorName, Chats chats, IIActorSystem system) {
        super(actorName, chats, system);
    }

    /**
     * Sends a prompt to a conversation and waits for its reply, which is the message.
     *
     * <p>Keep the reply with {@code this.putJson {path: reply, value: "jexl:result"}}. Fails when the conversation is not there or gave no reply.</p>
     *
     * <pre>{@code
     * - actor: chats
     *   method: ask
     *   arguments: {chat: 'jexl:state.getString("writer")', prompt: 'jexl:state.getString("prompt")', waiter: 'jexl:self'}
     * }</pre>
     *
     * @param args {@code {chat, prompt, waiter}}: the conversation's full name, what to ask, and who waits (the job itself, {@code jexl:self})
     */
    @Action("ask")
    public ActionResult ask(String args) {
        JSONObject a = new JSONObject(args);
        return now("no reply", c -> c.ask(opt(a, "chat"), opt(a, "prompt"), opt(a, "waiter")));
    }

    /**
     * Asks several conversations the same prompt at the same time and joins their replies, each under
     * {@code ## <conversation>}, in name order.
     *
     * <p>The whole costs about as long as the slowest ({@code ParallelWorkerPool_260829_oo01}). A
     * conversation that gave no reply is left out; it fails only when none replied.</p>
     *
     * <pre>{@code
     * - actor: chats
     *   method: askAll
     *   arguments: {chats: 'jexl:state.getString("conversations")', prompt: "Explain actors in 80 words.", waiter: 'jexl:self'}
     * }</pre>
     *
     * @param args {@code {chats, prompt, waiter}}: the conversations' full names one per line, what to ask, and who
     *             waits (the job itself, {@code jexl:self})
     */
    @Action("askAll")
    public ActionResult askAll(String args) {
        JSONObject a = new JSONObject(args);
        String prompt = opt(a, "prompt");
        String waiter = opt(a, "waiter");
        java.util.SortedMap<String, java.util.concurrent.CompletableFuture<String>> asked = new java.util.TreeMap<>();
        for (String chat : String.valueOf(opt(a, "chats")).split("\\R")) {
            if (!chat.isBlank()) asked.put(chat.strip(), askNow(c -> c.ask(chat, prompt, waiter)));
        }
        if (asked.isEmpty()) return new ActionResult(false, "chats is required");
        StringBuilder joined = new StringBuilder();
        java.util.List<String> silent = new java.util.ArrayList<>();
        for (var e : asked.entrySet()) {
            try {
                joined.append("## ").append(e.getKey()).append("\n").append(e.getValue().get()).append("\n\n");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new ActionResult(false, "interrupted");
            } catch (ExecutionException ee) {
                silent.add(e.getKey() + ": " + reason(ee));
            }
        }
        if (joined.length() == 0) return new ActionResult(false, "no conversation replied: " + String.join("; ", silent));
        return new ActionResult(true, joined.toString().stripTrailing());
    }

    /**
     * Empties a conversation's history, so the next prompt starts from nothing.
     *
     * <pre>{@code
     * - actor: chats
     *   method: clear
     *   arguments: {chat: 'jexl:state.getString("writer")'}
     * }</pre>
     *
     * @param args {@code {chat}}: the conversation's full name
     */
    @Action("clear")
    public ActionResult clear(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not clear the conversation", c -> c.clear(opt(a, "chat")));
    }

    /**
     * Ends a conversation: its recorded session and the history it holds, so it is not reopened at the next start.
     *
     * <pre>{@code
     * - actor: chats
     *   method: end
     *   arguments: {chat: 'project1/chat-t1'}
     * }</pre>
     *
     * @param args {@code {chat}}: the conversation's full name
     */
    @Action("end")
    public ActionResult end(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not end the conversation", c -> c.end(opt(a, "chat")));
    }

    /**
     * Stops whatever a conversation is in the middle of, as the Cancel button does.
     *
     * <p>Runs at once, while another job's question may be waiting on that conversation.</p>
     *
     * <pre>{@code
     * - actor: chats
     *   method: stop
     *   arguments: {chat: 'jexl:state.getString("writer")'}
     * }</pre>
     *
     * @param args {@code {chat}}: the conversation's full name
     */
    @Action("stop")
    public ActionResult stop(String args) {
        JSONObject a = new JSONObject(args);
        return now("could not stop the conversation", c -> c.stop(opt(a, "chat")));
    }

    /**
     * Puts a conversation on a model; a blank model leaves it as it was.
     *
     * <pre>{@code
     * - actor: chats
     *   method: setModel
     *   arguments: {chat: 'jexl:state.getString("writer")', model: 'jexl:state.getString("writerModel")'}
     * }</pre>
     *
     * @param args {@code {chat, model}}
     */
    @Action("setModel")
    public ActionResult setModel(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not set the model", c -> c.setModel(opt(a, "chat"), opt(a, "model")));
    }

    /**
     * Puts a conversation on another agent loop, a file under {@code /workflows/}; blank leaves it as it was.
     *
     * <pre>{@code
     * - actor: chats
     *   method: setAgentLoop
     *   arguments: {chat: 'jexl:state.getString("writer")', loop: plain-text-no-context-loop.yaml}
     * }</pre>
     *
     * @param args {@code {chat, loop}}
     */
    @Action("setAgentLoop")
    public ActionResult setAgentLoop(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not set the agent loop", c -> c.setAgentLoop(opt(a, "chat"), opt(a, "loop")));
    }

    /**
     * Says whether a conversation asks its model to skip the thinking phase; blank leaves it as it was.
     *
     * <pre>{@code
     * - actor: chats
     *   method: setNoThink
     *   arguments: {chat: 'jexl:state.getString("writer")', value: "true"}
     * }</pre>
     *
     * @param args {@code {chat, value}}: {@code true} or {@code false}
     */
    @Action("setNoThink")
    public ActionResult setNoThink(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not set thinking", c -> c.setNoThink(opt(a, "chat"), opt(a, "value")));
    }

    /**
     * Says how hard a conversation's harness works on an answer, where it has such a setting; blank leaves it as it was.
     *
     * <pre>{@code
     * - actor: chats
     *   method: setEffort
     *   arguments: {chat: 'jexl:state.getString("writer")', effort: high}
     * }</pre>
     *
     * @param args {@code {chat, effort}}
     */
    @Action("setEffort")
    public ActionResult setEffort(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not set the effort", c -> c.setEffort(opt(a, "chat"), opt(a, "effort")));
    }

    /**
     * Puts a conversation on a provider kind ({@code openai-compat}, {@code claude}, {@code codex}); blank leaves it as it was.
     *
     * <p>A kind this instance was not started with is a failure, not a silent fallback.</p>
     *
     * <pre>{@code
     * - actor: chats
     *   method: setProvider
     *   arguments: {chat: 'jexl:state.getString("writer")', kind: claude, tools: ""}
     * }</pre>
     *
     * @param args {@code {chat, kind, tools}}: tools blank for the kind's own default
     */
    @Action("setProvider")
    public ActionResult setProvider(String args) {
        JSONObject a = new JSONObject(args);
        return queued("could not set the provider", c -> c.setProvider(opt(a, "chat"), opt(a, "kind"), opt(a, "tools")));
    }

    private ActionResult queued(String failure, Function<Chats, String> f) {
        try {
            return new ActionResult(true, ask(f::apply).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, failure + ": interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, failure + ": " + reason(e));
        }
    }

    private ActionResult now(String failure, Function<Chats, String> f) {
        try {
            return new ActionResult(true, askNow(f::apply).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, failure + ": interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, failure + ": " + reason(e));
        }
    }

    private static String opt(JSONObject a, String key) {
        return a.isNull(key) ? null : a.get(key).toString();
    }

    private static String reason(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
