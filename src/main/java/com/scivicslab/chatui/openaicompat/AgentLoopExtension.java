package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.openaicompat.client.ChatMessage;
import com.scivicslab.chatui.openaicompat.client.OpenAiCompatClient;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SPI for an optional agent-loop plugin that handles tool calling for OpenAI-compatible providers.
 *
 * <p>When a {@code AgentLoopExtension} CDI bean is present on the classpath and
 * {@link #isEnabled()} returns {@code true}, {@link com.scivicslab.chatui.openaicompat.OpenAiCompatProvider}
 * delegates {@code sendPrompt} to {@link #runAgentLoop} instead of performing a single-shot request.</p>
 *
 * <p>The agent loop is responsible for:</p>
 * <ol>
 *   <li>Fetching tool definitions from MCP servers</li>
 *   <li>Injecting tools into each LLM request</li>
 *   <li>Parsing {@code tool_calls} from responses</li>
 *   <li>Executing tools via MCP and feeding results back to the model</li>
 *   <li>Repeating until {@code finish_reason=stop} or the iteration cap is hit</li>
 * </ol>
 */
public interface AgentLoopExtension {

    /**
     * Returns {@code true} if the agent loop is configured and ready to run
     * (i.e. at least one MCP URL is set).
     * When the provider is {@code openai-compat}, this must return {@code true}
     * or startup will fail.
     */
    boolean isEnabled();

    /**
     * Called once after the provider is created, supplying the underlying HTTP clients.
     *
     * @param clients the OpenAI-compatible clients configured for this provider
     */
    void initialize(List<OpenAiCompatClient> clients);

    /**
     * Runs the tool-calling loop for a single user turn.
     *
     * <p>On entry {@code history} already contains the user message for this turn.
     * The implementation should append assistant and tool messages to {@code history}
     * as the loop progresses, so that {@link com.scivicslab.chatui.openaicompat.OpenAiCompatProvider}
     * can preserve the full conversation for the next turn.</p>
     *
     * @param model    the model identifier to use
     * @param history  mutable conversation history (caller owns; loop may append)
     * @param emitter  SSE event callback for streaming output to the client
     * @param ctx      per-request context (API key, images, noThink flag, activity hook)
     */
    void runAgentLoop(String model, LinkedList<ChatMessage> history,
                      Consumer<ChatEvent> emitter, ProviderContext ctx);

    /**
     * Cancels the currently running agent loop, if any.
     * Called from {@link com.scivicslab.chatui.openaicompat.OpenAiCompatProvider#cancel()}.
     * Safe to call when no loop is running (no-op).
     */
    void cancel();
}
