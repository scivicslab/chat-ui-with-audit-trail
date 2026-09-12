package com.scivicslab.chatui.agent;

import java.util.Set;

/**
 * Which of the conversation's tools a conversation is shown and may call
 * ({@code HarnessPrefaceAndToolSplit_260912_oo01}).
 *
 * <p>A conversation whose provider is a CLI harness (Claude Code, Codex) already has file and shell
 * tools of its own. Offering this conversation's {@code read}, {@code write} and {@code calc} as well
 * would show the same capability twice under different names, so such a conversation is given
 * {@link #HARNESS}: every registered tool except those three. Web tools and the tools that reach
 * other conversations and the document index stay in, since a harness either lacks them or reaches
 * them differently, and calls through this conversation are recorded in its own form.</p>
 */
public enum ToolSet {

    /** Every tool: the conversation's built-in ones and every tool a plugin registered. */
    FULL(Set.of()),

    /** Every tool except the ones a harness duplicates with its own file and shell tools. */
    HARNESS(Set.of("read", "write", "calc"));

    /** The names this set leaves out. */
    private final Set<String> excluded;

    ToolSet(Set<String> excluded) {
        this.excluded = excluded;
    }

    /** @return {@code true} if the named tool is in this set */
    public boolean contains(String toolName) {
        return !excluded.contains(toolName);
    }

    /** @return the tool names this set leaves out; empty for {@link #FULL} */
    public Set<String> excluded() {
        return excluded;
    }

    /**
     * @param text {@code "full"} or {@code "harness"}, case-insensitive
     * @return the matching set
     * @throws IllegalArgumentException for any other text
     */
    public static ToolSet parse(String text) {
        if (text == null) throw new IllegalArgumentException("tool set is required");
        return switch (text.trim().toLowerCase()) {
            case "full" -> FULL;
            case "harness" -> HARNESS;
            default -> throw new IllegalArgumentException("unknown tool set '" + text + "' (full | harness)");
        };
    }

    /** @return the config/API spelling of this set */
    public String id() {
        return name().toLowerCase();
    }
}
