package com.scivicslab.chatui.core.actor;

/**
 * Top-level grouping actor for one project — a plain, behaviorless POJO whose only purpose is to
 * be a {@code createChild} parent, exactly like {@link CallWatchdog}/{@link CollaborationGraph}.
 *
 * <p>Everything one project's execution needs — its conversation tabs ({@code chat-...}) and its
 * own {@link CallWatchdog}/{@link CollaborationGraph} — lives as a descendant of one {@code
 * Project} instance, so {@code ask_chat}/{@code set_collaborator} can never reach across into a
 * different project ({@code ProjectScopedActorTree_260829_oo01}). {@code outputMultiplexer}
 * deliberately stays outside this tree — see that document's "なぜoutputMultiplexerはプロジェクト
 * ごとに分離しないか".</p>
 */
public class Project {
}
