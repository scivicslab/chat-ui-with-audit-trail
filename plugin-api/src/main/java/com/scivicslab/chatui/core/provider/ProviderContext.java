package com.scivicslab.chatui.core.provider;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-request context passed to {@link LlmProvider#sendPrompt}.
 *
 * <p>Carries request-scoped data without polluting the provider interface with many parameters.
 * Providers use only the fields they need and ignore the rest.</p>
 */
public record ProviderContext(
    /** API key (null for CLI-based providers that use system auth). */
    String apiKey,
    /** Image data URLs for multimodal prompts (empty list if not used). */
    List<String> imageDataUrls,
    /** Whether to disable thinking mode (Qwen3-specific). */
    boolean noThink,
    /** Called on each stream event so the watchdog can reset its stall timer. */
    Runnable onActivity,
    /**
     * Called with the exact request body just before it is sent, by providers that build one
     * (null when the caller does not want it). The I/O log records this string, so the record
     * shows the {@code messages} array the model actually read.
     */
    Consumer<String> onRequestBody
) {
    /** Constructor for callers that do not need the request body. */
    public ProviderContext(String apiKey, List<String> imageDataUrls, boolean noThink, Runnable onActivity) {
        this(apiKey, imageDataUrls, noThink, onActivity, null);
    }

    /** Convenience constructor for simple single-turn requests with no images. */
    public static ProviderContext simple(String apiKey) {
        return new ProviderContext(apiKey, List.of(), false, () -> {});
    }
}
