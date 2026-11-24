package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ChatNameTool;
import com.scivicslab.chatui.agent.CreateChatTool;
import com.scivicslab.chatui.agent.DeleteChatTool;
import com.scivicslab.chatui.agent.DocSearchTool;
import com.scivicslab.chatui.agent.DocsSources;
import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.GetChatLogTool;
import com.scivicslab.chatui.agent.GetChatStatusTool;
import com.scivicslab.chatui.agent.ListChatModelsTool;
import com.scivicslab.chatui.agent.LoadSkillTool;
import com.scivicslab.chatui.agent.OrganizationTools;
import com.scivicslab.chatui.agent.ProviderTools;
import com.scivicslab.chatui.agent.RequestMessagesTools;
import com.scivicslab.chatui.agent.SetAgentLoopTool;
import com.scivicslab.chatui.agent.SetChatModelTool;
import com.scivicslab.chatui.agent.SetCollaboratorTool;
import com.scivicslab.chatui.agent.TimekeeperTool;
import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.remote.RemoteNodes;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.tool.DocsLookup;
import com.scivicslab.chatui.tool.ExpressionEvaluator;
import com.scivicslab.chatui.tool.ToolCallRecorder;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.chatui.tool.ToolRunner;
import com.scivicslab.chatui.tool.ToolScope;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.examples.jshell.JShellCalculator;
import org.json.JSONObject;

import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs one conversation's tools that do not drive the conversation, from any thread
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>It holds a copy of what those tools read from the conversation's {@link ChatSession}: the wiring the
 * generating side hands the session when it creates it, and the session's {@link ToolSet}. The session makes
 * a new copy whenever one of them is set and hands it to {@link ConversationControls#publishToolRunner}, so
 * chat-ui's MCP server can run a tool without sending the session a message. A {@code tools/call} arrives
 * while the session waits in {@code runTool} for that very call; a message would wait behind it for ever.</p>
 *
 * <p>The tools that drive the conversation ({@link ConversationToolCatalog#conversationDrivingTools()}) change the
 * running turn's state, and stay in {@link ChatSession}.</p>
 *
 * <p>The plain tools, the plugin tools and the MCP checks run in agent-connector-kernel's {@link ToolRunner}
 * (tasks/todo.md, stage 5). This class is what the screen and the record add to it: the tools that operate
 * ConversationSquad actors or the actor tree ({@link ToolExtension}), the I/O-log record of an MCP call
 * ({@link ToolCallRecorder}), and the html-saurus nodes of the distributed actor system ({@link DocsLookup}).</p>
 */
public final class ConversationToolRunner implements ToolCallRecorder {

    private static final Logger logger = Logger.getLogger(ConversationToolRunner.class.getName());

    /**
     * Where the conversation is, for the {@code turnN/stepM/tool} label of the I/O log.
     *
     * @param session the conversation's I/O-log session, or a negative number when it has none yet
     * @param turn    the turn it is running, or ran last
     * @param step    the step of that turn
     */
    public record IoPosition(long session, int turn, int step) {}

    /**
     * The conversation's {@code calc} evaluator, made on first use and shared by every copy of the runner.
     * {@link JShellCalculator} is not safe to call from two threads at once, so calls take turns.
     */
    public static final class Calculator implements ExpressionEvaluator {
        private JShellCalculator calculator;

        /** @return what JShell made of {@code expression} */
        @Override
        public synchronized String evaluate(String expression) {
            if (calculator == null) calculator = new JShellCalculator();
            return calculator.evaluate(expression);
        }
    }

    private final IIActorSystem system;
    private final String projectId;
    private final String chatId;
    private final FileAccessScope fileScope;
    private final ConversationControls controls;
    private final ActorRef<RoleTable> roleTableRef;
    private final ActorRef<SkillRegistry> skillRegistryRef;
    private final IoLogStore ioLog;
    private final Supplier<IoPosition> position;
    private final ToolRunner core;

    /**
     * @param system           the actor system the conversation is registered in
     * @param projectId        the conversation's project
     * @param chatId           the conversation's id in that project
     * @param fileScope        the range of the file system its file tools may touch
     * @param controls         the settings and log window its conversation tools use, or {@code null} in a test
     * @param roleTableRef     its project's role table
     * @param skillRegistryRef the shared skill registry
     * @param pluginTools      the plugin tools, in the order they are listed; copied
     * @param toolSet          which tools it is shown and may call
     * @param calculator       its {@code calc} evaluator
     * @param ioLog            the I/O log, or {@code null} when logging is off
     * @param position         reads where the conversation is, for the I/O-log label
     */
    public ConversationToolRunner(IIActorSystem system, String projectId, String chatId, FileAccessScope fileScope,
                                  ConversationControls controls, ActorRef<RoleTable> roleTableRef,
                                  ActorRef<SkillRegistry> skillRegistryRef, Map<String, ConversationTool> pluginTools,
                                  ToolSet toolSet, Calculator calculator, IoLogStore ioLog,
                                  Supplier<IoPosition> position) {
        this.system = system;
        this.projectId = projectId;
        this.chatId = chatId;
        this.fileScope = fileScope;
        this.controls = controls;
        this.roleTableRef = roleTableRef;
        this.skillRegistryRef = skillRegistryRef;
        this.ioLog = ioLog;
        this.position = position;
        DocsLookup docs = node -> DocsSources.choose(RemoteNodes.of(system), node, DocSearchTool.baseUrl());
        this.core = new ToolRunner(new ToolScope(ChatUiActorSystem.chatActorName(projectId, chatId), fileScope, toolSet,
                pluginTools), calculator, docs, new AppTools(), this);
    }

    /** @return agent-connector-kernel's runner of this conversation's tools, which chat-ui's MCP server calls */
    public ToolRunner core() {
        return core;
    }

    /** @return the conversation's qualified name, e.g. {@code "project1/chat-01"} */
    public String conversationName() {
        return ChatUiActorSystem.chatActorName(projectId, chatId);
    }

    /**
     * @return whether chat-ui's MCP server offers {@code tool} to this conversation: a built-in tool that does not
     *         drive the conversation, or a plugin tool, and in its {@link ToolSet}
     */
    public boolean offers(String tool) {
        return core.offers(tool);
    }

    /**
     * Runs a tool for chat-ui's MCP server and records it to the I/O log once, under the label of the step the
     * conversation is at.
     *
     * @param tool          the tool's name
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation, or a text starting with {@code error: }
     */
    public String callFromMcp(String tool, String argumentsJson) {
        return core.callFromMcp(tool, argumentsJson);
    }

    /**
     * Runs one tool that does not drive the conversation. The caller has checked the {@link ToolSet}.
     *
     * @param tool              the tool's name
     * @param args              the call's arguments as a JSON object text
     * @param summarizingPages  whether the page-summarising transition asked for whole pages
     * @return the observation, or a text starting with {@code error: }
     */
    String run(String tool, String args, boolean summarizingPages) {
        return core.run(tool, args, summarizingPages);
    }

    /** The tools that operate ConversationSquad actors or the actor tree, run on the core {@link ToolRunner}'s behalf. */
    private final class AppTools implements ToolExtension {

        /** @return whether {@code tool} is one of the app's own built-in tools: in the catalog, and not a core tool */
        @Override
        public boolean defines(String tool) {
            return ConversationToolCatalog.builtIn(tool) != null && ToolCatalog.core(tool) == null;
        }

        /** @return whether {@code tool} is one of the app's own built-in tools that does not drive the conversation */
        @Override
        public boolean offers(String tool) {
            ToolCatalog.Spec spec = ConversationToolCatalog.builtIn(tool);
            return spec != null && !spec.drivesConversation();
        }

        /** Runs one of the app's own tools, or returns {@code null} when {@code tool} is not one of them. */
        @Override
        public String run(String tool, String args) {
            return switch (tool) {
                case "list_running_tools" -> com.scivicslab.chatui.agent.RunningToolsTool.list(
                        RemoteNodes.of(system), input(args, "tool"));
                // Through the controls, which also record the loop; without them (a test) only installs it.
                case "set_agent_loop" -> controls != null
                        ? controls.setAgentLoop(projectId, input(args, "chatId"), input(args, "yaml"))
                        : SetAgentLoopTool.setAgentLoop(system, projectId, input(args, "chatId"), input(args, "yaml"));
                case "load_skill" -> LoadSkillTool.load(skillRegistryRef, input(args, "name"));
                // Through the controls, which also save the edge; without them (a test) writes the graph only.
                case "set_collaborator" -> {
                    String node = input(args, "node");
                    yield controls != null
                            ? controls.setCollaborator(conversationName(), input(args, "chatId"),
                                    input(args, "role"), input(args, "collaboratorChatId"), node)
                            : SetCollaboratorTool.setCollaborator(roleTableRef,
                                    conversationName(), input(args, "chatId"), input(args, "role"),
                                    input(args, "collaboratorChatId"), node,
                                    n -> RemoteNodes.of(system) != null && RemoteNodes.of(system).knows(n));
                }
                case "get_chat_status" -> GetChatStatusTool.get(system, projectId, input(args, "chatId"));
                case "list_chat_models" -> ListChatModelsTool.list(system, projectId, input(args, "chatId"));
                case "set_chat_model" -> SetChatModelTool.set(system, controls, projectId,
                        input(args, "chatId"), input(args, "model"));
                case "list_providers" -> ProviderTools.list(controls);
                case "create_chat" -> CreateChatTool.create(system, controls, projectId, chatId,
                        input(args, "provider"), input(args, "model"), input(args, "superior"), input(args, "role"));
                case "list_chats" -> OrganizationTools.list(controls, projectId, chatId);
                case "set_superior" -> OrganizationTools.setSuperior(controls, projectId, chatId,
                        input(args, "chatId"), input(args, "superiorChatId"));
                case "set_chat_provider" -> ProviderTools.setChatProvider(controls, projectId,
                        input(args, "chatId"), input(args, "provider"));
                case "set_chat_timekeeper" -> TimekeeperTool.set(controls, projectId,
                        input(args, "chatId"), input(args, "urgentWaitMinutes"), input(args, "childReplyWarnMinutes"));
                case "set_mcp_servers" -> controls != null
                        ? controls.setMcpServers(projectId, input(args, "chatId"), input(args, "servers"))
                        : "error: this conversation has no controls to register MCP servers with";
                case "set_chat_name" -> ChatNameTool.set(controls, projectId, input(args, "chatId"), input(args, "name"));
                case "delete_chat" -> DeleteChatTool.delete(controls, projectId, conversationName(), input(args, "chatId"));
                case "get_chat_log" -> GetChatLogTool.get(controls, projectId,
                        input(args, "chatId"), parseIntOrNull(input(args, "count")));
                case "get_request_messages" -> RequestMessagesTools.get(system, projectId, input(args, "chatId"));
                case "set_request_messages" -> RequestMessagesTools.set(system, projectId,
                        input(args, "chatId"), input(args, "messages"));
                case "complete_messages" -> RequestMessagesTools.complete(system, projectId,
                        input(args, "chatId"), input(args, "messages"), input(args, "model"), input(args, "noThink"));
                default -> null;
            };
        }
    }

    /** Records a call made through the MCP server, in the form {@code ChatSession} records its own calls. */
    @Override
    public void record(String tool, String argumentsJson, String observation) {
        IoPosition at = position.get();
        if (ioLog == null || at.session() < 0) return;
        String label = "turn" + at.turn() + "/step" + at.step() + "/tool";
        try {
            ioLog.record(at.session(), "agent", label,
                    "TOOL: " + tool + "\nINPUT:\n" + argumentsJson + "\nOBSERVATION:\n" + observation);
            IIActorRef<?> conversationLog = system == null ? null : system.getIIActor(conversationName() + ".log");
            if (conversationLog != null) {
                conversationLog.callByActionName("add", new JSONObject().put("source", "ChatSession")
                        .put("type", "INFO").put("data", label + " (mcp): " + tool).toString());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log record of an MCP tool call failed", e);
        }
    }

    /** @return one named field of a call's JSON arguments as text, or {@code ""} if absent */
    static String input(String argumentsJson, String field) {
        try {
            return new JSONObject(argumentsJson == null ? "{}" : argumentsJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** @return {@code text} parsed as an {@code Integer}, or {@code null} if blank or not a number */
    static Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
