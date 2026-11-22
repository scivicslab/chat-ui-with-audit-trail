package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.StalledTurnRecovery;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@code list_stalled_chats} and {@code recover_stalled_chats} tools: the conversations of this instance whose turn
 * is stalled, and the way to bring them back ({@link StalledTurnRecovery}, {@code tasks/stalled-turn-recovery.md}).
 */
public final class StalledChatsTool {

    private StalledChatsTool() {}

    private static ActorRef<StalledTurnRecovery> recovery(IIActorSystem system) {
        return system == null ? null : system.getActor(StalledTurnRecovery.ACTOR_NAME);
    }

    /** @return one line per stalled conversation, or a line saying none is */
    public static String list(IIActorSystem system) {
        ActorRef<StalledTurnRecovery> r = recovery(system);
        if (r == null) return "error: this instance has no stalled-turn recovery";
        try {
            return r.ask(rec -> describe(rec.stalled(), rec.thresholdMillis())).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "error: could not list the stalled conversations: " + e.getMessage();
        }
    }

    static String describe(List<StalledTurnRecovery.Stalled> stalled, long thresholdMillis) {
        if (stalled.isEmpty()) {
            return "no conversation is stalled (a turn is stalled when its model call has produced nothing for "
                    + thresholdMillis / 60_000 + " min)";
        }
        StringBuilder sb = new StringBuilder();
        for (StalledTurnRecovery.Stalled s : stalled) {
            sb.append(s.projectId()).append("/chat-").append(s.chatId()).append(": ");
            if ("usage-limit".equals(s.reason())) {
                sb.append("a turn was refused for the usage limit ").append(s.silentMillis() / 60_000)
                        .append(" min ago; its request is back at the front and its queue is held");
            } else {
                sb.append("its model call has produced nothing for ").append(s.silentMillis() / 60_000).append(" min");
            }
            sb.append(" (since ").append(java.time.Instant.ofEpochMilli(s.callStartedAt())).append("); ")
                    .append(s.queued()).append(" prompt(s) waiting in its queue\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * @param myProjectId the calling conversation's project
     * @param target      {@code null} or blank for every stalled conversation; else {@code "chat-02"} in the caller's
     *                    project or {@code "project1/chat-57"}
     * @return one line per conversation recovered, or an {@code error: } line
     */
    public static String recover(IIActorSystem system, String myProjectId, String target) {
        ActorRef<StalledTurnRecovery> r = recovery(system);
        if (r == null) return "error: this instance has no stalled-turn recovery";
        try {
            List<StalledTurnRecovery.Outcome> outcomes;
            if (target == null || target.isBlank()) {
                outcomes = r.ask(StalledTurnRecovery::recoverAll).get(10, TimeUnit.MINUTES);
                if (outcomes.isEmpty()) return "no conversation is stalled; nothing was recovered";
            } else {
                String name = ChatUiActorSystem.resolveChatName(myProjectId, target.strip());
                int slash = name.indexOf('/');
                String project = name.substring(0, slash);
                String chat = name.substring(slash + 1);
                outcomes = List.of(r.ask(rec -> rec.recover(project, chat)).get(2, TimeUnit.MINUTES));
            }
            StringBuilder sb = new StringBuilder();
            for (StalledTurnRecovery.Outcome o : outcomes) {
                sb.append(o.projectId()).append("/chat-").append(o.chatId()).append(": ").append(o.outcome());
                switch (o.outcome()) {
                    case "recovered" -> sb.append(switch (o.how()) {
                        case "release" -> " (its queue, held after a usage-limit refusal, runs again from the refused"
                                + " request; if the limit still holds, that request is refused and the queue held again)";
                        case "kill" -> " (the cancel did not end the turn; its process was killed and starts again on"
                                + " the same session); the request it was working on is back at the front of its queue"
                                + " and runs again";
                        default -> " (the cancel ended the turn); the request it was working on is back at the front of"
                                + " its queue and runs again";
                    });
                    case "not-running" -> sb.append(" (no turn was running; its queue runs as it was)");
                    case "not-found" -> sb.append(" (no such conversation on this instance)");
                    default -> sb.append(" (the turn did not end even after its process was killed; its request goes"
                            + " back to the front of the queue when it does)");
                }
                sb.append("; ").append(o.queued()).append(" prompt(s) waiting\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "error: could not recover: " + e.getMessage();
        }
    }
}
