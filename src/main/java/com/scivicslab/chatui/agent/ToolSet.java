package com.scivicslab.chatui.agent;

import java.util.Set;

/**
 * Which of the conversation's tools a conversation is shown and may call
 * ({@code CliHarnessProvider_260912_oo01}).
 *
 * <p>A conversation whose provider is a CLI harness (Claude Code, Codex) runs file, shell and web
 * tools inside the harness. Offering this conversation's {@code read}/{@code write}/{@code fetch}
 * as well would show the same capability twice under different names, so such a conversation is
 * given {@link #COLLABORATION}: only the tools that reach other conversations and this team's
 * document index, which no harness has.</p>
 */
public enum ToolSet {

    /** Every tool the conversation implements. What an OpenAI-compatible provider gets. */
    FULL(Set.of("read", "calc", "web_search", "fetch", "search_docs", "list_references", "write",
                "ask_chat", "set_workflow", "run_plan", "load_skill", "set_collaborator")),

    /** Only the tools a harness cannot supply itself: other conversations and the document index. */
    COLLABORATION(Set.of("search_docs", "list_references", "ask_chat", "set_workflow", "run_plan",
                         "load_skill", "set_collaborator"));

    private final Set<String> names;

    ToolSet(Set<String> names) {
        this.names = names;
    }

    /** @return {@code true} if the named tool is in this set */
    public boolean contains(String toolName) {
        return names.contains(toolName);
    }

    /** @return the tool names in this set */
    public Set<String> names() {
        return names;
    }

    /**
     * @param text {@code "full"} or {@code "collaboration"}, case-insensitive
     * @return the matching set
     * @throws IllegalArgumentException for any other text
     */
    public static ToolSet parse(String text) {
        if (text == null) throw new IllegalArgumentException("tool set is required");
        return switch (text.trim().toLowerCase()) {
            case "full" -> FULL;
            case "collaboration" -> COLLABORATION;
            default -> throw new IllegalArgumentException("unknown tool set '" + text + "' (full | collaboration)");
        };
    }

    /** @return the config/API spelling of this set */
    public String id() {
        return name().toLowerCase();
    }
}
