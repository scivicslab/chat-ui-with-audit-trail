package com.scivicslab.chatui.tool;

import com.scivicslab.chatui.agent.CalcExpression;
import com.scivicslab.chatui.agent.DocSearchTool;
import com.scivicslab.chatui.agent.FileReadTool;
import com.scivicslab.chatui.agent.FileRemoveTool;
import com.scivicslab.chatui.agent.FileWriteTool;
import com.scivicslab.chatui.agent.ReferenceLinkTool;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.plugin.ConversationTool;
import org.json.JSONObject;

/**
 * Runs one scope's tools that do not drive a conversation, from any thread
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>It runs the plain tools agent-connector-kernel defines ({@link ToolCatalog#coreTools()}) and the plugin tools
 * itself, and hands any other name to its {@link ToolExtension}, which runs before the plugin tools so that an
 * extension's tool is never shadowed by a plugin tool of the same name. A call that came through MCP is recorded
 * once by its {@link ToolCallRecorder}. Everything it holds is set when it is made and does not change, so calls
 * from several threads at once need no lock; the {@link ExpressionEvaluator} takes care of its own.</p>
 */
public final class ToolRunner {

    private final ToolScope scope;
    private final ExpressionEvaluator calculator;
    private final DocsLookup docs;
    private final ToolExtension extension;
    private final ToolCallRecorder recorder;

    /**
     * @param scope      what the calls run with
     * @param calculator the {@code calc} evaluator
     * @param docs       where {@code search_docs} and {@code list_references} look
     * @param extension  the tools run on the builder's behalf, or {@link ToolExtension#NONE}
     * @param recorder   where MCP calls are recorded, or {@link ToolCallRecorder#NONE}
     */
    public ToolRunner(ToolScope scope, ExpressionEvaluator calculator, DocsLookup docs, ToolExtension extension,
                      ToolCallRecorder recorder) {
        this.scope = scope;
        this.calculator = calculator;
        this.docs = docs;
        this.extension = extension;
        this.recorder = recorder;
    }

    /** @return the scope the calls run with */
    public ToolScope scope() {
        return scope;
    }

    /**
     * @return whether an MCP client is offered {@code tool} in this scope: in its {@code ToolSet}, and a core tool,
     *         an extension tool the extension offers, or a plugin tool
     */
    public boolean offers(String tool) {
        if (!scope.toolSet().contains(tool)) return false;
        if (ToolCatalog.core(tool) != null) return true;
        if (extension.defines(tool)) return extension.offers(tool);
        return scope.pluginTools().containsKey(tool);
    }

    /**
     * Runs a tool for an MCP client and records the call once.
     *
     * @param tool          the tool's name
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation, or a text starting with {@code error: }
     */
    public String callFromMcp(String tool, String argumentsJson) {
        String observation;
        if (!offers(tool)) {
            observation = "error: tool '" + tool + "' is not available in this conversation";
        } else {
            try {
                observation = run(tool, argumentsJson, false);
            } catch (Exception e) {
                observation = "error: " + e.getMessage();
            }
        }
        recorder.record(tool, argumentsJson, observation);
        return observation;
    }

    /**
     * Runs one tool that does not drive a conversation. The caller has checked the {@code ToolSet}.
     *
     * @param tool             the tool's name
     * @param args             the call's arguments as a JSON object text
     * @param summarizingPages whether the page-summarising transition asked for whole pages
     * @return the observation, or a text starting with {@code error: }
     */
    public String run(String tool, String args, boolean summarizingPages) {
        switch (tool) {
            case "read":
                return FileReadTool.read(scope.fileScope(), input(args, "path"));
            case "write":
                return FileWriteTool.write(scope.fileScope(), input(args, "path"), input(args, "content"));
            case "calc": {
                // JShell evaluates Java, not arithmetic, and does it in this process
                // (CalcExpression). Read the argument before it gets there.
                String expression = input(args, "expression");
                String refusal = CalcExpression.reject(expression);
                return refusal != null ? refusal : calculator.evaluate(expression);
            }
            case "remove":
                return FileRemoveTool.remove(scope.fileScope(), input(args, "path"));
            case "search_docs":
            case "list_references": {
                var choice = docs.choose(input(args, "node"));
                if (choice.error() != null) return choice.error();
                return "search_docs".equals(tool)
                        ? DocSearchTool.search(input(args, "query"), 0, "all", choice.source())
                        : ReferenceLinkTool.list(input(args, "id"), input(args, "direction"),
                                input(args, "relation"), choice.source());
            }
            default:
                break;
        }
        String extended = extension.run(tool, args);
        if (extended != null) return extended;
        // A plugin tool (ProviderAndToolPlugins_260912_oo01). With whole pages when the page-summarising
        // transition asked for them: what reaches the model is decided afterwards by that transition, and the
        // untouched observation reaches the I/O log either way.
        ConversationTool plugin = scope.pluginTools().get(tool);
        if (plugin == null) return "error: unknown tool '" + tool + "'";
        return (summarizingPages && plugin.returnsPages()) ? plugin.executeUntrimmed(args) : plugin.execute(args);
    }

    /** @return one named field of a call's JSON arguments as text, or {@code ""} if absent */
    public static String input(String argumentsJson, String field) {
        try {
            return new JSONObject(argumentsJson == null ? "{}" : argumentsJson).optString(field, "");
        } catch (Exception e) {
            return "";
        }
    }
}
