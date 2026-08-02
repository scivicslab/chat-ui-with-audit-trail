package com.scivicslab.chatui.core.actor;

import java.util.List;

/**
 * One node of the actor tree returned by {@code GET /api/actors} for the right-pane Actors tab.
 * The tree is rendered by console.js as {@code {name, type, alive, children[]}}.
 *
 * @param name     the actor's registered name
 * @param type     the simple class name of the actor's held object
 * @param alive    whether the actor is currently alive
 * @param children the child actor nodes
 */
public record ActorNode(String name, String type, boolean alive, List<ActorNode> children) {}
