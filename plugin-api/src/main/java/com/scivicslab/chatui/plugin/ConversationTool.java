package com.scivicslab.chatui.plugin;

/**
 * One tool a plugin adds to every conversation ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>The conversation lists {@link #description()} in its first prompt after its built-in tools,
 * parses the model's {@code <invoke name="...">} as it does for them, and calls {@link #execute}
 * with the call's arguments as JSON text. The whole result reaches the I/O log; what the model is
 * shown is fitted by the conversation's workflow.</p>
 */
public interface ConversationTool {

    /** @return the name the model writes in {@code <invoke name="...">} */
    String name();

    /**
     * The tool's entry in the first prompt's "Available tools" list, starting with
     * {@code - name(args): } and ending with a newline, in the same voice as the built-in entries.
     *
     * @return the entry text
     */
    String description();

    /**
     * The tool's arguments, in the order {@link #description()} lists them, with their JSON Schema types
     * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}). The default reads the names from the
     * {@code - name(args): } head of {@link #description()} and makes each a required string; a tool
     * with an optional or non-string argument overrides it.
     *
     * @return the arguments
     */
    default java.util.List<ToolParameter> parameters() {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-\\s*" + java.util.regex.Pattern.quote(name()) + "\\(([^)]*)\\)")
                .matcher(description());
        if (!m.find() || m.group(1).isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(a -> !a.isEmpty())
                .map(ToolParameter::requiredString).toList();
    }

    /**
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation, or a text starting with {@code error: }
     */
    String execute(String argumentsJson);

    /**
     * Whether this tool's observation is a set of fetched pages, so the workflow's
     * page-summarising transition applies to it.
     *
     * @return {@code true} for a web search
     */
    default boolean returnsPages() {
        return false;
    }

    /**
     * The same call with every page kept whole, for the page-summarising transition, which
     * summarises each page itself instead of showing the model each page's opening characters.
     *
     * @param argumentsJson the call's arguments as a JSON object text
     * @return the observation with whole pages
     */
    default String executeUntrimmed(String argumentsJson) {
        return execute(argumentsJson);
    }
}
