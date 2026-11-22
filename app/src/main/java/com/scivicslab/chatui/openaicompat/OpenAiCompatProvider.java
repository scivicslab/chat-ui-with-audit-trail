package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.agent.ContextBudget;
import com.scivicslab.chatui.agent.ConversationToolCatalog;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.ContextLengthExceededException;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * LlmProvider implementation for OpenAI-compatible HTTP APIs (vLLM, Ollama, NemoClaw, etc.).
 *
 * <p>Maintains per-request conversation history for context, with automatic trimming
 * on context-length overflow.</p>
 *
 * <p>The model is given the conversation's tools as OpenAI tool definitions, and its calls come back from the
 * server's {@code tool_calls} ({@code OpenAiCompatStandardToolCalling_261120_oo01}). The tools that do not drive
 * the conversation are listed and run through an MCP client to chat-ui's MCP server; the ones that do come from
 * {@link ConversationToolCatalog} and are run by the conversation itself.</p>
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(OpenAiCompatProvider.class.getName());
    private static final int MAX_TRIM_RETRIES = 5;

    private final List<OpenAiCompatClient> clients;
    // volatile because GET .../status reads it from the request thread while this provider's own
    // thread writes it (ModelBelongsToTheConversation_260906_oo01) — the same reason
    // ChatSession.busy is volatile (BusyStateReadableSnapshot_260828_oo01).
    private volatile String currentModel;
    private volatile boolean cancelled;
    private volatile Thread sendingThread;
    /** The client of chat-ui's MCP server for this conversation, or {@code null} when it has none (a test). */
    private final McpToolClient mcp;
    /** The tools the model is given, read again whenever the system message is set at a turn's start. */
    private List<ToolDefinition> tools = List.of();
    /** The conversation's external MCP servers, by name ({@code ExternalMcpServersOfAConversation_261120_oo01}). */
    private java.util.Map<String, McpToolClient> externalServers = java.util.Map.of();
    /** Separates an external server's name from its tool's name in the name the model is given. */
    public static final String EXTERNAL_SEPARATOR = "__";
    /** What OpenAI accepts as a function name. */
    private static final java.util.regex.Pattern FUNCTION_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    // Conversation history for context (not managed by actor — provider owns it)
    private final LinkedList<ChatMessage> history = new LinkedList<>();
    /**
     * Context length assumed when the server does not report {@code max_model_len} for the model in
     * use. 128K tokens is the agreed floor for the models this system talks to
     * ({@code TurnResourceLimits_260830_oo01}).
     */
    private static final int DEFAULT_CONTEXT_TOKENS = 128_000;
    /**
     * Share of the model's context length the conversation history may occupy. The rest is left for
     * the reply and for the estimate being wrong: the estimate is char-based, not a real tokenizer.
     */
    private static final double HISTORY_SHARE = 0.5;
    /**
     * Fewest tokens a reply is ever allowed to run to, however full the window already is. Below this a
     * request would be refused or cut in a few sentences; the server's own context check, and the
     * history trimming that follows it, decide what happens when even this does not fit.
     */
    static final int MIN_REPLY_TOKENS = 2_048;
    /** The least tokens kept free of the window for the prompt estimate being wrong. */
    private static final int REPLY_MARGIN_MIN_TOKENS = 1_024;
    /** The share of the window kept free for the same reason, when that is more than the minimum. */
    private static final double REPLY_MARGIN_SHARE = 0.02;
    /** How many leading messages are collapsed turns rather than the running turn's steps
     *  ({@code TurnResourceLimits_260830_oo01}). */
    private int collapsedCount = 0;

    /**
     * Creates a provider that connects to one or more OpenAI-compatible servers, with no MCP server: the model is
     * given only the tools that drive the conversation.
     *
     * @param serverUrls   base URLs of the LLM servers (e.g. {@code http://localhost:8000})
     * @param defaultModel the model name to use when none is explicitly requested
     */
    public OpenAiCompatProvider(List<String> serverUrls, String defaultModel) {
        this(serverUrls, defaultModel, null);
    }

    /**
     * Creates a provider whose tools come from an MCP server.
     *
     * @param serverUrls   base URLs of the LLM servers
     * @param defaultModel the model name to use when none is explicitly requested
     * @param mcp          the client of chat-ui's MCP server for this conversation, or {@code null} for none
     */
    public OpenAiCompatProvider(List<String> serverUrls, String defaultModel, McpToolClient mcp) {
        this.clients = serverUrls.stream()
                .map(OpenAiCompatClient::new)
                .toList();
        this.currentModel = defaultModel;
        this.mcp = mcp;
        this.tools = drivingTools();
    }

    /** {@inheritDoc} */
    @Override public String id() { return "openai-compat"; }

    /** {@inheritDoc} */
    @Override public String displayName() { return "OpenAI-compatible LLM"; }

    /** {@inheritDoc} */
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.OPENAI_COMPAT; }

    /** {@inheritDoc} */
    @Override public String getCurrentModel() { return currentModel; }

    /** {@inheritDoc} */
    @Override public boolean callsToolsByDefinition() { return true; }

    /**
     * {@inheritDoc}
     *
     * <p>Called at the start of each turn, so the tool list is read again here: a conversation's tool set,
     * and the plugin tools, may have changed since the last turn.</p>
     */
    @Override
    public void setSystemMessage(String text) {
        if (!history.isEmpty() && history.getFirst() instanceof ChatMessage.System) {
            history.set(0, new ChatMessage.System(text));
        } else {
            history.addFirst(new ChatMessage.System(text));
            collapsedCount++;
        }
        tools = readTools();
    }

    @Override
    public void setMcpServers(java.util.Map<String, String> servers) {
        java.util.Map<String, McpToolClient> clientsByName = new java.util.LinkedHashMap<>();
        servers.forEach((name, url) -> clientsByName.put(name, new McpToolClient(url, null)));
        externalServers = java.util.Collections.unmodifiableMap(clientsByName);
    }

    /** @return the tools the model is given now */
    List<ToolDefinition> tools() {
        return tools;
    }

    /** @return the MCP server's tools, then the tools that drive the conversation */
    private List<ToolDefinition> readTools() {
        List<ToolDefinition> all = new ArrayList<>();
        if (mcp != null) {
            try {
                all.addAll(mcp.listTools());
            } catch (McpToolClient.McpException e) {
                logger.warning("tools/list to " + mcp.endpoint() + " failed; the model is given only the tools"
                        + " that drive the conversation: " + e.getMessage());
            }
        }
        externalServers.forEach((server, client) -> {
            try {
                for (ToolDefinition t : client.listTools()) {
                    String name = server + EXTERNAL_SEPARATOR + t.name();
                    if (!FUNCTION_NAME.matcher(name).matches()) {
                        logger.warning("Tool '" + t.name() + "' of MCP server " + server + " is not given to the model:"
                                + " '" + name + "' is not a valid function name");
                        continue;
                    }
                    all.add(new ToolDefinition(name, t.description(), t.parametersJson()));
                }
            } catch (McpToolClient.McpException e) {
                logger.warning("tools/list to MCP server " + server + " (" + client.endpoint() + ") failed; its tools"
                        + " are not given to the model this turn: " + e.getMessage());
            }
        });
        all.addAll(drivingTools());
        return List.copyOf(all);
    }

    /** @return the definitions of the tools the conversation runs itself, from {@link ConversationToolCatalog} */
    static List<ToolDefinition> drivingTools() {
        return ConversationToolCatalog.builtInTools().values().stream().filter(ToolCatalog.Spec::drivesConversation)
                .map(spec -> new ToolDefinition(spec.name(), spec.description(), spec.inputSchema().toString()))
                .toList();
    }

    @Override
    public void addToolResults(List<ToolCallResult> results) {
        for (ToolCallResult r : results) {
            history.addLast(new ChatMessage.ToolResult(r.callId(), r.toolName(), r.content()));
        }
    }

    /** {@inheritDoc} Runs it through chat-ui's MCP server, which records it to the I/O log. */
    @Override
    public String callTool(String name, String argumentsJson) {
        McpToolClient client = mcp;
        String tool = name;
        int at = name.indexOf(EXTERNAL_SEPARATOR);
        if (at > 0 && externalServers.containsKey(name.substring(0, at))) {
            client = externalServers.get(name.substring(0, at));
            tool = name.substring(at + EXTERNAL_SEPARATOR.length());
        }
        if (client == null) return "error: tool '" + name + "' cannot be run: this conversation has no MCP server";
        McpToolClient.Result r = client.callTool(tool, argumentsJson);
        return r.isError() && !r.text().startsWith("error: ") ? "error: " + r.text() : r.text();
    }

    /**
     * Sets the model to use for subsequent requests.
     *
     * @param model the model identifier
     */
    @Override public void setModel(String model) { this.currentModel = model; }

    /**
     * Returns {@code null} because OpenAI-compatible providers do not use persistent sessions.
     *
     * @return always {@code null}
     */
    @Override public String getSessionId() { return null; }

    /**
     * Detects an API key from the {@code LLM_API_KEY} environment variable.
     * Most local deployments do not require a key.
     *
     * @return the API key value, or {@code null} if the variable is not set
     */
    @Override
    public String detectEnvApiKey() {
        // Most local deployments don't need a key, but check a common var
        return System.getenv("LLM_API_KEY");
    }

    /**
     * Queries all configured servers and returns the union of their available models.
     *
     * @return list of model entries, each annotated with its server host
     */
    @Override
    public List<ModelEntry> getAvailableModels() {
        List<ModelEntry> models = new ArrayList<>();
        for (OpenAiCompatClient client : clients) {
            String server = extractHost(client.getBaseUrl());
            for (String name : client.fetchModels()) {
                models.add(new ModelEntry(name, "openai-compat", server));
            }
        }
        // Auto-select the first available model if currentModel is still a placeholder
        if (!models.isEmpty() && (currentModel == null || currentModel.isBlank() || currentModel.equals("default"))) {
            currentModel = models.get(0).name();
            logger.info("Auto-selected model: " + currentModel);
        }
        return models;
    }

    /**
     * Sends a prompt to the selected model via the OpenAI-compatible chat completions API,
     * streaming results back through the emitter. Automatically trims conversation history
     * when the context length is exceeded.
     *
     * @param prompt  the user's prompt text
     * @param model   the model to use, or {@code null} to keep the current model
     * @param emitter callback that receives streamed {@link ChatEvent}s
     * @param ctx     provider context containing images and other request metadata
     */
    /** What this conversation asks the server to sample at; {@code null} asks for nothing. */
    private volatile Double temperature;

    /**
     * {@inheritDoc}
     *
     * <p>Set on every client this provider holds: which one serves a turn depends on the model,
     * and the conversation's temperature does not.</p>
     */
    @Override
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
        for (OpenAiCompatClient c : clients) {
            c.setTemperature(temperature);
        }
    }

    @Override
    public Double getTemperature() {
        return temperature;
    }

    @Override
    public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
        cancelled = false;
        sendingThread = Thread.currentThread();
        try {
        if (model != null && !model.isBlank()) currentModel = model;

        // If model is still a placeholder, try to resolve it from the server
        if (currentModel == null || currentModel.isBlank() || currentModel.equals("default")) {
            getAvailableModels();
        }
        if (currentModel == null || currentModel.isBlank() || currentModel.equals("default")) {
            emitter.accept(ChatEvent.error("No model available. Please check that your LLM server is running and reachable."));
            return;
        }

        List<String> imageDataUrls = ctx.imageDataUrls() != null ? ctx.imageDataUrls() : List.of();
        // A blank prompt follows the tool results just added: the model answers them, and no user
        // message is added (OpenAiCompatStandardToolCalling_261120_oo01).
        boolean addsUserMessage = prompt != null && !prompt.isBlank() || !imageDataUrls.isEmpty();
        if (addsUserMessage) history.addLast(new ChatMessage.User(prompt == null ? "" : prompt, imageDataUrls));
        fitHistoryToBudget();

        OpenAiCompatClient client = selectClient(currentModel);
        if (client == null) {
            emitter.accept(ChatEvent.error("No server available for model: " + currentModel));
            if (addsUserMessage) history.pollLast();
            return;
        }

        int retries = 0;
        while (retries <= MAX_TRIM_RETRIES) {
            if (cancelled) return;
            List<ChatMessage> snapshot = List.copyOf(history);
            final int currentRetry = retries;
            try {
                StringBuilder assistantBuf = new StringBuilder();
                List<ChatMessage.ToolCallRequest.ToolCall> calls = new ArrayList<>();
                client.sendPrompt(currentModel, snapshot, ctx.noThink(), maxTokensFor(client, currentModel, snapshot),
                    tools, new OpenAiCompatClient.StreamCallback() {
                        @Override public void onDelta(String content) {
                            assistantBuf.append(content);
                            emitter.accept(ChatEvent.delta(content));
                        }
                        @Override public void onReasoning(String reasoning) {
                            // Reasoning is shown but never kept: it is not part of the answer, so
                            // it stays out of assistantBuf and therefore out of the history entry
                            // added in onComplete and out of the text parsed for tool calls.
                            emitter.accept(ChatEvent.thinking(reasoning));
                        }
                        @Override public void onToolCalls(List<ChatMessage.ToolCallRequest.ToolCall> toolCalls) {
                            calls.addAll(toolCalls);
                        }
                        @Override public void onComplete(long durationMs, String finishReason) {
                            String response = assistantBuf.toString();
                            if (wasCutOff(finishReason) && ctx.handlesCutOff()) {
                                // The caller continues the turn itself: keep what was written, so the
                                // next request carries it, and say the text is unfinished instead of
                                // failing. Reasoning stays out of the history, as it does for a reply
                                // that finished (RunawayGenerationLimits_260915_oo01).
                                logger.warning("Reply cut off at the token limit after "
                                        + response.length() + " chars; partial reply kept");
                                history.addLast(new ChatMessage.Assistant(response));
                                fitHistoryToBudget();
                                emitter.accept(ChatEvent.cutOff(String.valueOf(response.length())));
                                return;
                            }
                            if (wasCutOff(finishReason)) {
                                // Not an answer: the server stopped mid-sentence at a token limit.
                                // Kept out of the history as well as reported, because a truncated
                                // assistant message is what the next turn would be built on
                                // (RunawayGenerationLimits_260915_oo01).
                                logger.warning("Reply cut off at the token limit after "
                                        + response.length() + " chars");
                                emitter.accept(ChatEvent.error(
                                        "The reply was cut off at the token limit (finish_reason=length)."));
                                return;
                            }
                            if (calls.isEmpty()) {
                                history.addLast(new ChatMessage.Assistant(response));
                            } else {
                                // The calls and the text written with them are one message; each call's
                                // result follows it under the call's id (addToolResults).
                                history.addLast(new ChatMessage.ToolCallRequest(response, List.copyOf(calls)));
                                for (ChatMessage.ToolCallRequest.ToolCall c : calls) {
                                    emitter.accept(ChatEvent.toolCall(c.id(), c.name(), c.arguments()));
                                }
                            }
                            fitHistoryToBudget();
                            if (currentRetry > 0) {
                                logger.info("Context overflow recovered after " + currentRetry
                                        + " trim(s). Session preserved with "
                                        + history.size() + " messages.");
                            }
                            emitter.accept(ChatEvent.result(null, 0.0, durationMs, currentModel, false));
                        }
                        @Override public void onError(String message) {
                            emitter.accept(ChatEvent.error(message));
                        }
                    }, ctx.onRequestBody());
                return;
            } catch (ContextLengthExceededException e) {
                retries++;
                if (retries > MAX_TRIM_RETRIES || history.size() <= 2) {
                    emitter.accept(ChatEvent.error("Context too long even after trimming."));
                    return;
                }
                logger.warning("Context length exceeded, trimming history (attempt " + retries + ")");
                emitter.accept(ChatEvent.thinking("Context too long, trimming history..."));
                trimHistory();
            }
        }
        } finally {
            sendingThread = null;
        }
    }

    /**
     * Empties the context the next prompt is built from
     * ({@code ForgetTheConversationOnBothSides_260917_oo01}).
     */
    @Override
    public void clearHistory() {
        history.clear();
        collapsedCount = 0;
    }

    @Override
    public String requestMessagesJson() {
        return RequestMessagesJson.toJson(List.copyOf(history));
    }

    /**
     * Replaces the history with the given array whole. A history over the budget is refused, not
     * trimmed: trimming would make the array that is sent differ from the one that was given.
     * The replaced array is treated as already collapsed, so a later {@link #collapseTurn} only
     * replaces what the next turn adds.
     */
    @Override
    public String replaceRequestMessages(String json) {
        List<ChatMessage> messages;
        try {
            messages = RequestMessagesJson.parse(json);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        int used = 0;
        for (ChatMessage m : messages) used += ContextBudget.estimateMessageTokens(contentOf(m));
        int budget = historyBudgetTokens();
        if (used > budget) {
            return "too long: about " + used + " tokens, the budget for this model is " + budget;
        }
        history.clear();
        history.addAll(messages);
        collapsedCount = history.size();
        return null;
    }

    /**
     * Ends a turn in the history ({@code OpenAiCompatStandardToolCalling_261120_oo01}). Of what the turn added,
     * the question, the replies that called tools with the results of those calls, and the answer are kept;
     * the notes the conversation sent between steps, and the replies that were neither, are dropped. A call
     * with no result, such as the {@code ask_person} that ended the turn, is dropped too: a request whose call
     * has no answer is refused by the server.
     *
     * @param question the turn's question, as the person or the requesting conversation wrote it
     * @param answer   the turn's answer, or {@code null} when it has none
     */
    @Override
    public void collapseTurn(String question, String answer) {
        if (question == null || question.isBlank()) return;
        List<ChatMessage> turn = new ArrayList<>(history.subList(Math.min(collapsedCount, history.size()), history.size()));
        while (history.size() > collapsedCount) history.removeLast();
        history.addLast(new ChatMessage.User(question, List.of()));
        java.util.Set<String> answered = new java.util.HashSet<>();
        for (ChatMessage m : turn) if (m instanceof ChatMessage.ToolResult r) answered.add(r.toolCallId());
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (ChatMessage m : turn) {
            if (m instanceof ChatMessage.ToolCallRequest t) {
                List<ChatMessage.ToolCallRequest.ToolCall> withResults =
                        t.toolCalls().stream().filter(c -> answered.contains(c.id())).toList();
                if (withResults.isEmpty()) continue;
                history.addLast(new ChatMessage.ToolCallRequest(t.content(), withResults));
                withResults.forEach(c -> kept.add(c.id()));
            } else if (m instanceof ChatMessage.ToolResult r && kept.contains(r.toolCallId())) {
                history.addLast(r);
            }
        }
        if (answer != null && !answer.isBlank()) {
            history.addLast(new ChatMessage.Assistant(answer));
        }
        collapsedCount = history.size();
        fitHistoryToBudget();
    }

    /**
     * Drops the oldest message after the system message, keeping {@link #collapsedCount} pointing at the same
     * boundary. A reply that called tools goes together with the results of its calls, and a result whose call
     * is gone goes too: the server refuses a call without its result and a result without its call.
     *
     * @return the estimated tokens dropped
     */
    private int evictOldest() {
        int at = !history.isEmpty() && history.getFirst() instanceof ChatMessage.System ? 1 : 0;
        if (history.size() <= at) return 0;
        ChatMessage removed = history.remove(at);
        int tokens = ContextBudget.estimateMessageTokens(contentOf(removed));
        if (collapsedCount > at) collapsedCount--;
        while (history.size() > at && history.get(at) instanceof ChatMessage.ToolResult r) {
            history.remove(at);
            tokens += ContextBudget.estimateMessageTokens(contentOf(r));
            if (collapsedCount > at) collapsedCount--;
        }
        return tokens;
    }

    /**
     * Drops the oldest messages until the history's estimated tokens fit the budget the model's
     * context length allows.
     *
     * <p>This used to be a cap on the number of messages (20), which ignored how large a message
     * was: one message of ten characters and one of twenty thousand counted the same. It also had
     * nothing to do with the model — a 128K-token model was held to the same twenty messages as a
     * small one. During an agent-loop turn each step adds two messages, so the count alone decided
     * how far back a step could see, and a long turn silently lost its early observations while
     * most of the context window stayed empty
     * ({@code TurnResourceLimits_260830_oo01} "会話履歴の上限が件数なのはなぜ駄目か").</p>
     *
     * <p>Messages are dropped one at a time rather than in pairs, because during a turn the
     * history is not a strict user/assistant alternation: an agent-loop step contributes a prompt
     * and a reply, and a collapsed turn contributes a question and an answer. The last message is
     * never dropped — it is the prompt about to be sent.</p>
     */
    private void fitHistoryToBudget() {
        int budget = historyBudgetTokens();
        int used = estimateHistoryTokens();
        int keep = !history.isEmpty() && history.getFirst() instanceof ChatMessage.System ? 2 : 1;
        while (used > budget && history.size() > keep) {
            int dropped = evictOldest();
            if (dropped == 0 && history.size() > keep) dropped = 1;
            used -= dropped;
        }
    }

    /** @return how many tokens the history may occupy, from the model's own reported context length */
    private int historyBudgetTokens() {
        int contextTokens = -1;
        // Guard the model name: selectClient asks each client whether it serves this model, and
        // servesModel does contains() on an immutable list, which rejects null before any model
        // list has been fetched. collapseTurn can reach here before a model has been resolved.
        if (currentModel != null && !currentModel.isBlank()) {
            OpenAiCompatClient client = selectClient(currentModel);
            if (client != null) contextTokens = client.getMaxModelLen(currentModel);
        }
        if (contextTokens <= 0) contextTokens = DEFAULT_CONTEXT_TOKENS;
        return (int) (contextTokens * HISTORY_SHARE);
    }

    /**
     * The {@code max_tokens} to send: the room left in the model's window after the prompt, less a
     * safety margin, and never below {@link #MIN_REPLY_TOKENS}. Without it each server applies its own
     * default, which is far below the window on some (32,768 tokens on one broker deployment), and a
     * long reply is cut off the moment it passes that default.
     *
     * <p>The prompt size is {@link ContextBudget}'s estimate, which counts two characters to a token
     * and so over-counts: the room it leaves is no more than the real room.</p>
     *
     * @param contextTokens the model's context length as the server reported it; at or below zero
     *                      when it did not
     * @param promptTokens  the estimated tokens of the whole request
     * @return the value to send, or {@code 0} to send nothing because the window is unknown
     */
    static int replyBudgetTokens(int contextTokens, int promptTokens) {
        if (contextTokens <= 0) return 0;
        int margin = Math.max(REPLY_MARGIN_MIN_TOKENS, (int) (contextTokens * REPLY_MARGIN_SHARE));
        int room = contextTokens - promptTokens - margin;
        return Math.min(Math.max(room, MIN_REPLY_TOKENS), contextTokens);
    }

    /**
     * {@link #replyBudgetTokens} for one request, with the window read from the server's model list
     * ({@code max_model_len} in {@code /v1/models}, cached by the client).
     *
     * @return the value to send as {@code max_tokens}, or {@code 0} when the window is not known
     */
    private int maxTokensFor(OpenAiCompatClient client, String model, List<ChatMessage> messages) {
        if (client == null || model == null || model.isBlank()) return 0;
        int prompt = 0;
        for (ChatMessage m : messages) prompt += ContextBudget.estimateMessageTokens(contentOf(m));
        return replyBudgetTokens(client.getMaxModelLen(model), prompt);
    }

    /** @return the estimated token count of the whole history */
    private int estimateHistoryTokens() {
        int sum = 0;
        for (ChatMessage m : history) sum += ContextBudget.estimateMessageTokens(contentOf(m));
        return sum;
    }

    /**
     * The text a message contributes to the request. A tool-call request carries no prose, so its
     * size is the serialized calls; an image is sent as a data URL, which is part of the message's
     * size even though it is not text the model reads as words.
     *
     * @param m one history message
     * @return the text whose length stands for that message's size
     */
    private static String contentOf(ChatMessage m) {
        return switch (m) {
            case ChatMessage.User u -> u.hasImages()
                    ? u.content() + String.join("", u.imageDataUrls())
                    : u.content();
            case ChatMessage.Assistant a -> a.content();
            case ChatMessage.System s -> s.content();
            case ChatMessage.ToolResult r -> r.content();
            case ChatMessage.ToolCallRequest t -> {
                StringBuilder b = new StringBuilder(t.content() == null ? "" : t.content());
                for (ChatMessage.ToolCallRequest.ToolCall c : t.toolCalls()) {
                    b.append(c.name()).append(c.arguments());
                }
                yield b.toString();
            }
        };
    }

    @Override
    public void cancel() {
        cancelled = true;
        Thread t = sendingThread;
        if (t != null) t.interrupt();
    }

    private void trimHistory() {
        // Remove the oldest messages after the system message (keep at least the last one)
        int keep = !history.isEmpty() && history.getFirst() instanceof ChatMessage.System ? 3 : 2;
        if (history.size() > keep) evictOldest();
        if (history.size() > keep) evictOldest();
    }

    /**
     * One completion, outside the conversation: same server and model, but nothing is added to
     * {@link #history} and no {@code ChatEvent} is emitted.
     *
     * <p>{@link #sendPrompt} cannot serve this. It carries the conversation's history, streams
     * into the browser and holds the provider's session, so calling it ten times in parallel to
     * summarise ten web pages would interleave ten answers into one conversation. Summarising a
     * page is not part of the conversation; it is how one observation is made small enough to put
     * into it ({@code WebPageSummarizer}).
     *
     * @param prompt the whole instruction, standing on its own
     * @return the reply text, or {@code null} when the call failed
     */
    public String completeOutsideConversation(String prompt) {
        OpenAiCompatClient client = clientForCompletionOutsideConversation();
        if (client == null) {
            return null;
        }
        List<ChatMessage> oneShot = List.of(new ChatMessage.User(prompt));
        OpenAiCompatClient.NonStreamingResponse response = client.sendNonStreaming(
                currentModel, oneShot, true, maxTokensFor(client, currentModel, oneShot), List.of());
        String reason = response.finishReason();
        if (reason != null && reason.startsWith("error")) {
            return null;
        }
        return response.content();
    }

    /**
     * One completion from exactly the given messages, outside the conversation. The history, the
     * agent loop's instruction text and the tool list are not part of the request; only the
     * messages, the model and the thinking switch are. Safe to call while a turn is running: it
     * touches neither {@link #history} nor the provider's session.
     */
    @Override
    public com.scivicslab.chatui.core.provider.OneShotReply completeFromMessages(
            String messagesJson, String model, Boolean noThink) {
        List<ChatMessage> messages;
        try {
            messages = RequestMessagesJson.parse(messagesJson);
        } catch (IllegalArgumentException e) {
            return com.scivicslab.chatui.core.provider.OneShotReply.refused(e.getMessage());
        }
        if (messages.isEmpty()) {
            return com.scivicslab.chatui.core.provider.OneShotReply.refused("messages is empty");
        }
        boolean named = model != null && !model.isBlank();
        OpenAiCompatClient client = named ? selectClient(model) : clientForCompletionOutsideConversation();
        String useModel = named ? model : currentModel;
        if (client == null || useModel == null || useModel.isBlank()) {
            return com.scivicslab.chatui.core.provider.OneShotReply.refused("no server available for the model");
        }
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        OpenAiCompatClient.NonStreamingResponse response = client.sendNonStreaming(
                useModel, messages, noThink == null || noThink, maxTokensFor(client, useModel, messages), List.of(), sent::set);
        String reason = response.finishReason();
        if (reason != null && reason.startsWith("error")) {
            return new com.scivicslab.chatui.core.provider.OneShotReply(null, sent.get(), reason);
        }
        return new com.scivicslab.chatui.core.provider.OneShotReply(response.content(), sent.get(), null);
    }

    /**
     * One completion outside the conversation, like {@link #completeOutsideConversation}, with
     * the reply handed over as it is generated. Used where a person waits for the whole text,
     * such as a translation of a long message: the first words appear after a fraction of a
     * second instead of after the last token.
     *
     * @param prompt  the whole instruction, standing on its own
     * @param onDelta receives each piece of the reply, in order, on the calling thread
     * @return the whole reply, or {@code null} when the call failed or the reply was empty
     */
    public String completeOutsideConversationStreaming(String prompt, Consumer<String> onDelta) {
        OpenAiCompatClient client = clientForCompletionOutsideConversation();
        if (client == null) {
            return null;
        }
        try {
            List<ChatMessage> oneShot = List.of(new ChatMessage.User(prompt));
            return client.sendPrompt(currentModel, oneShot, true, maxTokensFor(client, currentModel, oneShot),
                    new OpenAiCompatClient.StreamCallback() {
                        @Override public void onDelta(String content) { onDelta.accept(content); }
                        @Override public void onComplete(long durationMs, String finishReason) { }
                        @Override public void onError(String message) {
                            logger.warning("Completion outside the conversation failed: " + message);
                        }
                    });
        } catch (ContextLengthExceededException e) {
            logger.warning("Completion outside the conversation exceeded the context length");
            return null;
        }
    }

    /**
     * The client that serves {@link #currentModel}, once the model is known; {@code null} when no
     * model is known or no server offers it.
     */
    private OpenAiCompatClient clientForCompletionOutsideConversation() {
        // Resolve the model first, the way sendPrompt does. A caller that runs before any prompt
        // has been sent finds currentModel still unset, and selectClient then hands back whichever
        // client is first, which is asked for a model named "" and answers an error. Observed on
        // the activity summary, which is asked for as soon as the process is up
        // (ActivitySummary_260905_oo01).
        if (currentModel == null || currentModel.isBlank() || currentModel.equals("default")) {
            getAvailableModels();
        }
        if (currentModel == null || currentModel.isBlank() || currentModel.equals("default")) {
            return null;
        }
        return selectClient(currentModel);
    }

    /**
     * Whether the server stopped because a token limit was reached rather than because the model
     * had finished. Such a reply ends mid-sentence, so the text is not the answer to anything --
     * and when it holds a tool call, the closing tag never arrived and no tool runs either
     * ({@code RunawayGenerationLimits_260915_oo01}).
     *
     * @param finishReason what the server reported, or {@code null} when it reported nothing
     */
    static boolean wasCutOff(String finishReason) {
        return "length".equals(finishReason);
    }

    private OpenAiCompatClient selectClient(String model) {
        // Try to find a client that serves this model (using cached model list)
        for (OpenAiCompatClient client : clients) {
            if (client.servesModel(model)) return client;
        }
        // Fall back to first available client
        return clients.isEmpty() ? null : clients.get(0);
    }

    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (Exception e) {
            return url;
        }
    }
}
