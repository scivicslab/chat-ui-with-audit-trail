package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.agent.ContextBudget;
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
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger logger = Logger.getLogger(OpenAiCompatProvider.class.getName());
    private static final int MAX_TRIM_RETRIES = 5;

    private final List<OpenAiCompatClient> clients;
    private String currentModel;
    private volatile boolean cancelled;
    private volatile Thread sendingThread;
    private final AgentLoopExtension agentLoopExtension;

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
    /** How many leading messages are collapsed turns rather than the running turn's steps
     *  ({@code TurnResourceLimits_260830_oo01}). */
    private int collapsedCount = 0;

    /**
     * Creates a provider that connects to one or more OpenAI-compatible servers.
     *
     * @param serverUrls   base URLs of the LLM servers (e.g. {@code http://localhost:8000})
     * @param defaultModel the model name to use when none is explicitly requested
     */
    public OpenAiCompatProvider(List<String> serverUrls, String defaultModel) {
        this(serverUrls, defaultModel, null);
    }

    /**
     * Creates a provider with an optional agent-loop plugin.
     *
     * @param serverUrls        base URLs of the LLM servers
     * @param defaultModel      the model name to use when none is explicitly requested
     * @param agentLoopExtension optional plugin; {@code null} disables tool calling
     */
    public OpenAiCompatProvider(List<String> serverUrls, String defaultModel,
                                AgentLoopExtension agentLoopExtension) {
        this.clients = serverUrls.stream()
                .map(OpenAiCompatClient::new)
                .toList();
        this.currentModel = defaultModel;
        this.agentLoopExtension = agentLoopExtension;
        if (agentLoopExtension != null) {
            agentLoopExtension.initialize(this.clients);
        }
    }

    /** {@inheritDoc} */
    @Override public String id() { return "openai-compat"; }

    /** {@inheritDoc} */
    @Override public String displayName() { return "OpenAI-compatible LLM"; }

    /** {@inheritDoc} */
    @Override public ProviderCapabilities capabilities() { return ProviderCapabilities.OPENAI_COMPAT; }

    /** {@inheritDoc} */
    @Override public String getCurrentModel() { return currentModel; }

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
        history.addLast(new ChatMessage.User(prompt, imageDataUrls));
        fitHistoryToBudget();

        // Delegate to agent loop if the plugin is present and enabled
        if (agentLoopExtension != null && agentLoopExtension.isEnabled()) {
            agentLoopExtension.runAgentLoop(currentModel, history, emitter, ctx);
            return;
        }

        OpenAiCompatClient client = selectClient(currentModel);
        if (client == null) {
            emitter.accept(ChatEvent.error("No server available for model: " + currentModel));
            history.pollLast();
            return;
        }

        int retries = 0;
        while (retries <= MAX_TRIM_RETRIES) {
            if (cancelled) return;
            List<ChatMessage> snapshot = List.copyOf(history);
            final int currentRetry = retries;
            try {
                StringBuilder assistantBuf = new StringBuilder();
                client.sendPrompt(currentModel, snapshot, ctx.noThink(), 0,
                    new OpenAiCompatClient.StreamCallback() {
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
                        @Override public void onComplete(long durationMs) {
                            String response = assistantBuf.toString();
                            history.addLast(new ChatMessage.Assistant(response));
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
                    });
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
     * Signals that the current streaming request should be cancelled.
     */
    @Override
    public void collapseTurn(String question, String answer) {
        if (question == null || question.isBlank()) return;
        // Everything before collapsedCount is already (question, answer) pairs of earlier turns;
        // everything after it is the turn that just ended, one prompt/response per agent-loop step.
        // Only the latter is replaced — earlier turns are what a chat UI is expected to remember.
        while (history.size() > collapsedCount) history.removeLast();
        history.addLast(new ChatMessage.User(question, List.of()));
        if (answer != null && !answer.isBlank()) {
            history.addLast(new ChatMessage.Assistant(answer));
        }
        collapsedCount = history.size();
        fitHistoryToBudget();
    }

    /** Drops the oldest message, keeping {@link #collapsedCount} pointing at the same boundary. */
    private void evictOldest() {
        history.removeFirst();
        if (collapsedCount > 0) collapsedCount--;
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
        while (used > budget && history.size() > 1) {
            used -= ContextBudget.estimateMessageTokens(contentOf(history.getFirst()));
            evictOldest();
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
                StringBuilder b = new StringBuilder();
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
        if (agentLoopExtension != null) agentLoopExtension.cancel();
    }

    private void trimHistory() {
        // Remove oldest non-system messages (keep at least the last user message)
        if (history.size() > 2) evictOldest();
        if (history.size() > 2) evictOldest();
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
        OpenAiCompatClient client = selectClient(currentModel);
        if (client == null) {
            return null;
        }
        OpenAiCompatClient.NonStreamingResponse response = client.sendNonStreaming(
                currentModel, List.of(new ChatMessage.User(prompt)), true, 0, List.of());
        String reason = response.finishReason();
        if (reason != null && reason.startsWith("error")) {
            return null;
        }
        return response.content();
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
