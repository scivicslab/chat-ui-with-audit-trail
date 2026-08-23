package com.scivicslab.chatui.core.provider;

import com.scivicslab.chatui.core.rest.ChatEvent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * SPI for LLM backend providers.
 *
 * <p>Each provider handles prompt dispatch, model listing, and lifecycle management.
 * The {@link #sendPrompt} method is always called from a virtual thread so blocking is allowed.</p>
 */
public interface LlmProvider {

    /** Unique provider identifier used in config (e.g., "claude", "codex", "openai-compat"). */
    String id();

    /** Human-readable display name (e.g., "Claude", "Codex", "Local LLM"). */
    String displayName();

    /** Returns the list of available models for this provider. */
    List<ModelEntry> getAvailableModels();

    /** Returns the currently selected model name. */
    String getCurrentModel();

    /** Sets the current model. */
    void setModel(String model);

    /**
     * Sends a prompt and streams events to the emitter.
     *
     * <p>This method is blocking. It is called via
     * {@code providerRef.ask(p -> p.sendPrompt(...), actorSystem.getManagedThreadPool())}
     * so that the blocking I/O runs on a managed platform thread, not on an actor's
     * virtual thread.</p>
     *
     * @param prompt  user prompt text
     * @param model   model name to use
     * @param emitter callback for streaming ChatEvents (delta, result, error, thinking, etc.)
     * @param ctx     per-request context (api key, images, activity callback)
     */
    void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx);

    /** Cancels the currently running request. */
    void cancel();

    // ---- Optional features with default no-op implementations ----

    /** Returns the current session ID, or null if not applicable (e.g., HTTP-based providers). */
    default String getSessionId() { return null; }

    /** True if this provider supports interactive user prompts (tool permission dialogs, etc.). */
    default boolean supportsInteractivePrompts() { return false; }

    /** Sends a response to an interactive prompt (tool permission, yes/no, free text). */
    default void respond(String promptId, String response) throws IOException {
        throw new UnsupportedOperationException("Interactive prompts not supported by " + id());
    }

    /**
     * Returns whether the given prompt id is an outstanding plan-approval prompt
     * (emitted when Claude calls {@code ExitPlanMode}).
     *
     * <p>Plan approval is not a tool-permission reply: by the time the prompt is shown
     * the turn has already ended, so the answer must start a fresh turn rather than be
     * written back to the subprocess as a tool result. Callers use this to route the
     * response accordingly.</p>
     *
     * @param promptId the prompt identifier being answered
     * @return {@code true} if this id is an outstanding plan-approval prompt
     */
    default boolean isPlanApproval(String promptId) { return false; }

    /** Removes the given plan-approval prompt id from the outstanding set, if present. */
    default void clearPlanApproval(String promptId) { }

    /**
     * For providers whose interactive answers must resume via a fresh turn (rather than a
     * mid-turn write to a still-running request), returns the prompt text to enqueue as that
     * continuation turn, or {@code null} to fall back to {@link #respond}.
     *
     * <p>The tmux-driven Claude provider returns the key(s) to type at the on-screen dialog
     * (for example {@code "1"}); enqueuing that as a turn types it into the live TUI and the
     * continuation streams over SSE like a normal message. The default returns {@code null}
     * so other providers keep their existing in-turn {@link #respond} behaviour.
     *
     * @param promptId the prompt being answered
     * @param response the user's answer
     * @return the continuation prompt to enqueue, or {@code null} to use {@link #respond}
     */
    default String resolveApprovalToContinuation(String promptId, String response) { return null; }

    // ---- Autonomous events (output produced outside a sendPrompt turn) ----

    /**
     * Returns whether this provider can produce autonomous events — output that arrives while no
     * {@link #sendPrompt} turn is active, for example when a background job the model started
     * finishes, or a scheduled wake-up fires. Only such providers are polled by the idle monitor.
     *
     * @return {@code true} if autonomous events are possible for this provider
     */
    default boolean supportsAutonomousEvents() { return false; }

    /**
     * Returns whether autonomous output is currently buffered and waiting to be surfaced.
     *
     * <p>Non-blocking and cheap. The idle monitor calls this on the actor thread before
     * committing to a (blocking) drain, so it never reserves the session when nothing is pending.</p>
     *
     * @return {@code true} if at least one autonomous event is waiting
     */
    default boolean hasAutonomousActivity() { return false; }

    /**
     * Drains one autonomous turn, dispatching its events to {@code emitter} using the same shapes
     * as {@link #sendPrompt} (delta / thinking / result …), and returns whether any event was
     * surfaced.
     *
     * <p>This method blocks until the autonomous turn's {@code result} arrives (or the buffer
     * drains). It is called on a managed thread pool, never on an actor's virtual thread.</p>
     *
     * @param emitter callback that receives the streamed {@link ChatEvent} instances
     * @return {@code true} if at least one event was surfaced (an autonomous turn happened)
     */
    default boolean drainAutonomousActivity(Consumer<ChatEvent> emitter) { return false; }

    /** True if the given input is a slash command handled by this provider. */
    default boolean isCommand(String input) { return false; }

    /** Handles a slash command and returns response events. */
    default List<ChatEvent> handleCommand(String input) {
        return List.of(ChatEvent.error("Slash commands not supported by provider: " + id()));
    }

    /** Provider capabilities for conditional feature activation. */
    default ProviderCapabilities capabilities() { return ProviderCapabilities.DEFAULT; }

    /**
     * Detects an API key from environment variables specific to this provider.
     * Returns null if no relevant env var is set.
     * Override in each provider (e.g., ANTHROPIC_API_KEY, OPENAI_API_KEY).
     */
    default String detectEnvApiKey() { return null; }

    /** A model entry returned by {@link #getAvailableModels()}. */
    record ModelEntry(String name, String type, String server) {}
}
