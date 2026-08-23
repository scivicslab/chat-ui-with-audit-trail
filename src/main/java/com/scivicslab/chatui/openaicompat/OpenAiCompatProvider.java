package com.scivicslab.chatui.openaicompat;

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
    private static final int MAX_HISTORY_MESSAGES = 20;

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
        if (history.size() > MAX_HISTORY_MESSAGES) history.removeFirst();

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
                        @Override public void onComplete(long durationMs) {
                            String response = assistantBuf.toString();
                            history.addLast(new ChatMessage.Assistant(response));
                            if (history.size() > MAX_HISTORY_MESSAGES) history.removeFirst();
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
    public void cancel() {
        cancelled = true;
        Thread t = sendingThread;
        if (t != null) t.interrupt();
        if (agentLoopExtension != null) agentLoopExtension.cancel();
    }

    private void trimHistory() {
        // Remove oldest non-system messages (keep at least the last user message)
        if (history.size() > 2) history.removeFirst();
        if (history.size() > 2) history.removeFirst();
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
