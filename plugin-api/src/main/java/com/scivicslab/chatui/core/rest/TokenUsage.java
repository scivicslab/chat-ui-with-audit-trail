package com.scivicslab.chatui.core.rest;

/**
 * The tokens one model request used, as the provider reported them.
 *
 * <p>Claude Code reports {@code input_tokens}, {@code cache_creation_input_tokens},
 * {@code cache_read_input_tokens} and {@code output_tokens}. An OpenAI-compatible server reports
 * {@code prompt_tokens} (the whole input) and {@code completion_tokens}; it is mapped with
 * {@code cacheCreation} 0 and, when the server says how many prompt tokens were cached,
 * {@code cacheRead} set to that number.</p>
 *
 * @param input         input tokens that were neither written to nor read from the cache
 * @param cacheCreation input tokens written to the cache
 * @param cacheRead     input tokens read from the cache
 * @param output        tokens the model generated
 */
public record TokenUsage(int input, int cacheCreation, int cacheRead, int output) {

    /** @return the whole input of the request: what it put into the context window */
    public int total() {
        return input + cacheCreation + cacheRead;
    }

    /**
     * The {@code USAGE:} line of an I/O log message. {@code promptTokens} and {@code completionTokens}
     * come first and keep their old meaning, so a reader written before the cache keys existed still
     * parses the line; {@code promptTokens} is the whole input ({@link #total()}). The cache keys follow.
     *
     * @param usage the request's usage, or {@code null} when the provider reported none
     * @return the line, without a leading newline; {@code null} gives the zeros older rows hold
     */
    public static String logLine(TokenUsage usage) {
        if (usage == null) return "USAGE: promptTokens=0 completionTokens=0";
        return "USAGE: promptTokens=" + usage.total() + " completionTokens=" + usage.output()
                + " cacheCreationTokens=" + usage.cacheCreation() + " cacheReadTokens=" + usage.cacheRead();
    }
}
