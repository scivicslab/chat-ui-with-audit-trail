package com.scivicslab.chatui.core.actor;

/**
 * Groups the actors that exist once for the whole system regardless of what work is being done —
 * the call watchdog, the collaboration graph, the skill registry and the system-wide log
 * multiplexer ({@code NestedConversationTree_260830_oo01}).
 *
 * <p>A behaviourless POJO, like {@link Project}: it exists to be a parent. Grouping is for reading
 * the Actors pane — with these four at the top level, a tree whose branches are supposed to show
 * the shape of the work begins with four actors that have nothing to do with it. The registry is
 * flat, so being under this branch changes no actor's name and no lookup.</p>
 */
public class Housekeeper {
}
