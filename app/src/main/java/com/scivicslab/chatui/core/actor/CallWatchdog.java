package com.scivicslab.chatui.core.actor;

import java.util.HashMap;
import java.util.Map;

/**
 * Refuses {@code ask_chat} calls that would create a circular wait (chat A waits on chat B, which
 * is itself already waiting — directly or transitively — on chat A). One instance for the whole
 * actor system, reached via {@link com.scivicslab.pojoactor.core.ActorRef#ask}/{@code tell} like
 * any other POJO-actor.
 *
 * <p>Each conversation tab's own agent loop processes one tool call at a time, so a tab is never
 * waiting on more than one target at once — {@link #waitingOn} is a simple map, not a multimap.
 * {@code beginWait} checks for a cycle by following existing wait edges from the new call's target
 * back toward the caller; if it ever reaches the caller, completing the call would form a cycle,
 * so it's refused before any waiting starts (see {@code AskChatToolAndWatchdog_260827_oo01} "なぜ
 * タイムアウトだけでなく事前拒否も要るか").</p>
 */
public class CallWatchdog {

    private final Map<String, String> waitingOn = new HashMap<>();

    /**
     * Records that {@code waiterId} is about to wait on {@code targetId}, unless doing so would
     * create a cycle.
     *
     * @param waiterId  the tab about to start waiting (the caller of {@code ask_chat})
     * @param targetId  the tab it would wait on
     * @return {@code true} if the wait was recorded and may proceed; {@code false} if it would
     *         create a cycle and must be refused
     */
    public boolean beginWait(String waiterId, String targetId) {
        String cur = targetId;
        while (cur != null) {
            if (cur.equals(waiterId)) {
                return false;
            }
            cur = waitingOn.get(cur);
        }
        waitingOn.put(waiterId, targetId);
        return true;
    }

    /**
     * Clears {@code waiterId}'s recorded wait — called once its {@code ask_chat} call finishes,
     * whether it succeeded, timed out, or failed.
     *
     * @param waiterId the tab whose wait has ended
     */
    public void endWait(String waiterId) {
        waitingOn.remove(waiterId);
    }
}
