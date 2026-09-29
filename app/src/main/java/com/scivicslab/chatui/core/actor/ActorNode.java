package com.scivicslab.chatui.core.actor;

import java.util.List;

/**
 * One node of the actor tree returned by {@code GET /api/actors} for the right-pane Actors tab.
 * The tree is rendered by console.js as {@code {name, type, alive, children[]}}.
 *
 * @param name     the actor's registered name
 * @param type     the simple class name of the actor's held object
 * @param note     what the workflow that created this actor said it was doing, or {@code null}
 *                 for actors Java created ({@code ActorPurposeFromWorkflowNote_260831_oo01})
 * @param displayName the name a person gave this actor, or {@code null} when it has none. Set for
 *                 Project actors, which a person may name ({@code ProjectProperty_260929_oo01}).
 *                 Kept apart from {@code note}: that one says what a workflow step was for, and
 *                 one field carrying both meanings could not be rendered differently.
 * @param alive    whether the actor is currently alive
 * @param children the child actor nodes
 */
public record ActorNode(String name, String type, String note, String displayName, boolean alive,
                        List<ActorNode> children) {}
