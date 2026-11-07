package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.actor.ConversationControls;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_chat_timekeeper} tool: changes how long a conversation's timekeeper lets an urgent request wait
 * before pausing the running turn, and how long it lets a sent request go without a reply before saying so
 * ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}). Only for conversations of the caller's own
 * project, as {@code set_chat_model}.
 */
public final class TimekeeperTool {

    private TimekeeperTool() {}

    private static final Logger LOG = Logger.getLogger(TimekeeperTool.class.getName());

    /**
     * @param controls              the window onto the actor system
     * @param myProjectId           the calling conversation's project id
     * @param target                a bare id for this project (a qualified id of another project is refused)
     * @param urgentWaitMinutes     whole minutes, or {@code null}/blank to keep the current value
     * @param childReplyWarnMinutes whole minutes, or {@code null}/blank to keep the current value
     * @return {@code ok: ...} with the limits now in force, or an {@code error: ...} string
     */
    public static String set(ConversationControls controls, String myProjectId, String target,
                             String urgentWaitMinutes, String childReplyWarnMinutes) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (controls == null) return "error: set_chat_timekeeper is not available here";
        Long urgent;
        Long warn;
        try {
            urgent = minutes(urgentWaitMinutes);
            warn = minutes(childReplyWarnMinutes);
        } catch (NumberFormatException e) {
            return "error: a limit is a whole number of minutes";
        }
        if (urgent == null && warn == null) {
            return "error: give urgentWaitMinutes, childReplyWarnMinutes, or both";
        }
        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            return "error: refused — " + target + " belongs to another project";
        }
        String targetChatId = targetName.substring((myProjectId + "/chat-").length());
        try {
            long[] now = controls.setTimekeeperLimits(myProjectId, targetChatId, urgent, warn);
            return "ok: " + targetName + " now pauses a turn for an urgent request after " + now[0]
                    + " minute(s), and reports a request with no reply after " + now[1] + " minute(s)";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_chat_timekeeper: failed for " + target, e);
            return "error: set_chat_timekeeper failed: " + e.getMessage();
        }
    }

    private static Long minutes(String text) {
        return text == null || text.isBlank() ? null : Long.valueOf(text.strip());
    }
}
