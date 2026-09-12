package com.scivicslab.chatui.core.provider;

import java.util.List;

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
    Runnable onActivity
) {
    /** Convenience constructor for simple single-turn requests with no images. */
    public static ProviderContext simple(String apiKey) {
        return new ProviderContext(apiKey, List.of(), false, () -> {});
    }
}
