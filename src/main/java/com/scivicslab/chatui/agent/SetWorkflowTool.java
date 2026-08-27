package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code set_workflow} tool: replaces another conversation tab's agent-loop workflow with the
 * given YAML, so one tab can author a workflow for another to run (graph-engineering scenarios —
 * see {@code WorkerBabysitterOrchestration_260828_oo01}).
 *
 * <p>Takes the YAML as a string, not a file name — {@code write}-tool output lands under the OS
 * working directory, but workflow files are loaded from the classpath baked into the running
 * uber-jar, so a freshly-written file would never be found ({@code
 * WorkerBabysitterOrchestration_260828_oo01} "なぜ`write`ツール＋ファイル読み込みではなく..."). Calls
 * {@code Interpreter.reset()} before {@code readYaml(...)} — {@code readYaml} alone does not reset
 * the interpreter's current state, only its step table ({@code WorkflowReloadReset_260828_oo01}).</p>
 */
public final class SetWorkflowTool {

    private SetWorkflowTool() {}

    private static final Logger LOG = Logger.getLogger(SetWorkflowTool.class.getName());

    /** Generous: this waits behind the target's own mailbox, so it can queue behind a running turn. */
    private static final int TIMEOUT_SECONDS = 60;

    /**
     * @param system      this tab's actor system, used to resolve the target tab's actor
     * @param targetTabId the tab id whose agent-loop workflow to replace
     * @param yaml        the new workflow's YAML text
     * @return {@code "ok: ..."} on success, or an {@code error: ...} string
     */
    public static String setWorkflow(IIActorSystem system, String targetTabId, String yaml) {
        if (targetTabId == null || targetTabId.isBlank()) return "error: chatId is required";
        if (yaml == null || yaml.isBlank()) return "error: yaml is required";

        IIActorRef<?> targetIIActor = system.getIIActor("chat-" + targetTabId + ".chat");
        if (!(targetIIActor instanceof ChatSessionIIAR targetChatSessionIIAR)) {
            return "error: chat not found: " + targetTabId;
        }

        try {
            targetChatSessionIIAR.<Void>ask(interp -> {
                ChatSession chat = (ChatSession) interp;
                chat.reset();
                chat.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
                return null;
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return "ok: workflow installed on chat " + targetTabId;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "set_workflow: failed for " + targetTabId, e);
            return "error: set_workflow failed: " + e.getMessage();
        }
    }
}
