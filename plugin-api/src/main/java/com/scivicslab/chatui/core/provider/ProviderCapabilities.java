package com.scivicslab.chatui.core.provider;

/**
 * Declares which optional features a provider supports.
 * Used by {@link com.scivicslab.chatui.core.actor.ChatUiActorSystem} and
 * {@link com.scivicslab.chatui.core.rest.ChatResource} to enable conditional behavior.
 */
public record ProviderCapabilities(
    /** Provider supports interactive tool-permission prompts (/api/respond). */
    boolean supportsInteractivePrompts,
    /** Provider maintains session IDs that survive across requests. */
    boolean supportsSessionRestore,
    /** Watchdog stall detection should be wired (CLI providers that can hang). */
    boolean supportsWatchdog,
    /** Provider accepts image data URLs in the prompt. */
    boolean supportsImages,
    /** Provider can fetch and inject URL content (/api/fetch-url). */
    boolean supportsUrlFetch,
    /** Provider supports slash commands (/model, /session, /clear). */
    boolean supportsSlashCommands
) {
    public static final ProviderCapabilities DEFAULT = new ProviderCapabilities(
            false, false, false, false, false, false);

    public static final ProviderCapabilities CLI = new ProviderCapabilities(
            true, true, true, false, false, true);

    public static final ProviderCapabilities OPENAI_COMPAT = new ProviderCapabilities(
            false, false, false, true, true, false);
}
