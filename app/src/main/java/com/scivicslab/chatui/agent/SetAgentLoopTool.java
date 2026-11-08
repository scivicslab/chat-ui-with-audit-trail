package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_agent_loop} tool: replaces another ConversationSquad's agent loop — the workflow that
 * runs each of its turns — with the given YAML, so one ConversationSquad can author an agent loop for
 * another to run (graph-engineering scenarios —
 * see {@code WorkerBabysitterOrchestration_260828_oo01}).
 *
 * <p>Takes the YAML as a string, not a file name — {@code write}-tool output lands under the OS
 * working directory, but workflow files are loaded from the classpath baked into the running
 * uber-jar, so a freshly-written file would never be found ({@code
 * WorkerBabysitterOrchestration_260828_oo01} "なぜ`write`ツール＋ファイル読み込みではなく..."). Calls
 * {@code Interpreter.reset()} before {@code readYaml(...)} — {@code readYaml} alone does not reset
 * the interpreter's current state, only its step table ({@code WorkflowReloadReset_260828_oo01}).</p>
 */
public final class SetAgentLoopTool {

    private SetAgentLoopTool() {}

    private static final Logger LOG = Logger.getLogger(SetAgentLoopTool.class.getName());

    /** Generous: this waits behind the target's own mailbox, so it can queue behind a running turn. */
    private static final int TIMEOUT_SECONDS = 60;

    /**
     * @param system      this conversation's actor system, used to resolve the target's actor
     * @param myProjectId the calling conversation's project id
     * @param target      the target as written by the caller — a bare id ({@code "02"}) for this
     *                    project, or a qualified one ({@code "project2/02"}) to cross into another
     * @param yaml        the new agent loop's YAML text
     * @return {@code "ok: ..."} on success, or an {@code error: ...} string
     */
    public static String setAgentLoop(IIActorSystem system, String myProjectId, String target, String yaml) {
        if (target == null || target.isBlank()) return "error: chatId is required";
        if (yaml == null || yaml.isBlank()) return "error: yaml is required";

        String targetName = ChatUiActorSystem.resolveChatName(myProjectId, target);
        if (!targetName.startsWith(myProjectId + "/")) {
            // set_agent_loop rewrites how the target runs its own work, so crossing a project needs
            // the target project's consent. The gateway that grants it is not designed yet, so
            // refuse for now rather than silently reaching in (ProjectNamespacePrefix_260829_oo01).
            return "error: refused — " + target + " belongs to another project; set_agent_loop across"
                    + " projects must go through that project's gateway (not implemented yet)";
        }

        IIActorRef<?> targetIIActor = system.getIIActor(targetName + ".chat");
        if (!(targetIIActor instanceof ChatSessionIIAR targetChatSessionIIAR)) {
            return "error: chat not found: " + target + OtherInstances.notFoundHint(targetName);
        }

        try {
            targetChatSessionIIAR.<Void>ask(interp -> {
                ChatSession chat = (ChatSession) interp;
                chat.reset();
                chat.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
                return null;
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return "ok: agent loop installed on chat " + target;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_agent_loop: failed for " + target, e);
            return "error: set_agent_loop failed: " + e.getMessage();
        }
    }
}
