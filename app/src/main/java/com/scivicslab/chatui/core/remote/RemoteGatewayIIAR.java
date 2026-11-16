package com.scivicslab.chatui.core.remote;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The actor other chat-ui instances call ({@link RemoteNodes#GATEWAY}). Each action takes one JSON object and hands
 * it to {@link RemoteGateway}; only the actions of the allow-list reach it.
 */
public class RemoteGatewayIIAR extends IIActorRef<RemoteGateway> {

    public RemoteGatewayIIAR(String actorName, RemoteGateway gateway, IIActorSystem system) {
        super(actorName, gateway, system);
    }

    @Action("enqueueRequest")
    public ActionResult enqueueRequest(String args) { return object.enqueueRequest(args); }

    @Action("enqueueReply")
    public ActionResult enqueueReply(String args) { return object.enqueueReply(args); }

    @Action("enqueueFollowUp")
    public ActionResult enqueueFollowUp(String args) { return object.enqueueFollowUp(args); }

    @Action("withdrawRequest")
    public ActionResult withdrawRequest(String args) { return object.withdrawRequest(args); }

    @Action("enqueueCancelResult")
    public ActionResult enqueueCancelResult(String args) { return object.enqueueCancelResult(args); }

    @Action("registerPromise")
    public ActionResult registerPromise(String args) { return object.registerPromise(args); }

    @Action("readTurnState")
    public ActionResult readTurnState(String args) { return object.readTurnState(args); }
}
