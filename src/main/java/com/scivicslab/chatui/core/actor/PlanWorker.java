package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * One worker slot of a plan: forwards a prompt to the conversation assigned to it and keeps the
 * reply ({@code ParallelWorkerPool_260829_oo01}).
 *
 * <p>Exists because {@code Interpreter.apply} — the fan-out this pool is built on — only reaches
 * actors that are both children of the caller and registered as {@code IIActorRef}s. Conversations
 * are neither: they hang off their project, and their tab actor is a plain {@code ActorRef}. A
 * worker slot is the plan's own child and an {@code IIActorRef}, so {@code apply} can drive several
 * at once, each standing in for one conversation.</p>
 */
public class PlanWorker {

    private final IIActorSystem system;
    private final ActorRef<CallWatchdog> watchdog;
    /** The plan this slot belongs to — the waiter id its calls are recorded under. */
    private final String ownerName;
    /** The conversation this slot forwards to, e.g. {@code project1/chat-03}. */
    private final String targetChatName;

    private volatile String lastReply;

    public PlanWorker(IIActorSystem system, ActorRef<CallWatchdog> watchdog,
                       String ownerName, String targetChatName) {
        this.system = system;
        this.watchdog = watchdog;
        this.ownerName = ownerName;
        this.targetChatName = targetChatName;
    }

    /** @return the conversation this slot forwards to */
    public String targetChatName() { return targetChatName; }

    /** @return this slot's latest reply, or {@code null} if it has not been asked yet */
    public String lastReply() { return lastReply; }

    /**
     * Sends {@code prompt} to this slot's conversation and waits for the reply.
     *
     * @param prompt what to ask
     * @return {@link ActionResult} with {@code success=true} iff the conversation replied
     */
    public ActionResult ask(String prompt) {
        String reply = AskChatTool.askQualified(system, watchdog, ownerName, targetChatName, prompt, null);
        lastReply = reply;
        return (reply == null || reply.startsWith("error:"))
                ? new ActionResult(false, reply != null ? reply : "no reply from " + targetChatName)
                : new ActionResult(true, "reply received");
    }
}
