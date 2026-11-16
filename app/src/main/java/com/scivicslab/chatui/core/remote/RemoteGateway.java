package com.scivicslab.chatui.core.remote;

import org.json.JSONObject;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * What another chat-ui instance's messages do here ({@code ConversationSquadsAcrossInstances_261115_oo01}). Each
 * message is a JSON object; what it does to this instance's conversations is the same as the local ask_chat path,
 * written in {@link AskChatTool}.
 */
public class RemoteGateway {

    private final IIActorSystem local;
    private final RemoteNodes nodes;

    RemoteGateway(IIActorSystem local, RemoteNodes nodes) {
        this.local = local;
        this.nodes = nodes;
    }

    /** A request for one of this instance's conversations. */
    public ActionResult enqueueRequest(String args) {
        return AskChatTool.remoteRequestArrived(local, nodes, new JSONObject(args));
    }

    /** The reply to a request one of this instance's conversations sent. */
    public ActionResult enqueueReply(String args) {
        return AskChatTool.remoteReplyArrived(local, new JSONObject(args));
    }

    /** A follow-up to a request one of this instance's conversations sent. */
    public ActionResult enqueueFollowUp(String args) {
        return AskChatTool.remoteFollowUpArrived(local, new JSONObject(args));
    }

    /** The sender takes back a request it sent to one of this instance's conversations. */
    public ActionResult withdrawRequest(String args) {
        return AskChatTool.remoteWithdraw(local, nodes, new JSONObject(args));
    }

    /** What became of a request one of this instance's conversations took back. */
    public ActionResult enqueueCancelResult(String args) {
        return AskChatTool.remoteCancelResult(local, new JSONObject(args));
    }

    /** A report one of this instance's conversations promised, to be asked for later. */
    public ActionResult registerPromise(String args) {
        return AskChatTool.remoteRegisterPromise(local, new JSONObject(args));
    }

    /** Whether one of this instance's conversations is running a turn, and since when. */
    public ActionResult readTurnState(String args) {
        String target = new JSONObject(args).optString("target", "");
        IIActorRef<?> t = local.getIIActor(target + ".chat");
        if (!(t instanceof ChatSessionIIAR c)) return new ActionResult(false, "no conversation " + target);
        Long since = c.runningSinceDirect();
        return new ActionResult(true, since == null ? "idle" : "running:" + since);
    }
}
