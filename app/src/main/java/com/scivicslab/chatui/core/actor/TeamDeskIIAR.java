package com.scivicslab.chatui.core.actor;

import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * The shared actor {@value ChatUiActorSystem#WORKFLOW_CONVERSATIONS}: a {@link TeamDesk} the plugin code of a job
 * finds by name ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 *
 * <p>It declares no workflow actions, so no workflow can call it; only code that looks it up in the actor system and
 * asks for its object can. What it holds is the narrow {@link TeamDesk}, never the whole actor system.</p>
 */
public class TeamDeskIIAR extends IIActorRef<TeamDesk> {

    public TeamDeskIIAR(String actorName, TeamDesk desk, IIActorSystem system) {
        super(actorName, desk, system);
    }
}
