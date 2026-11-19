package com.scivicslab.chatui.plugin;

/**
 * One argument of a tool, as a tool's JSON Schema states it ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * @param name        the argument's name, as the model writes it
 * @param type        its JSON Schema type: {@code "string"}, {@code "integer"} or {@code "boolean"}
 * @param required    whether the tool cannot run without it
 * @param description what the argument means; empty when the tool's own description already says it
 */
public record ToolParameter(String name, String type, boolean required, String description) {

    /** @return a required string argument with no description of its own */
    public static ToolParameter requiredString(String name) {
        return new ToolParameter(name, "string", true, "");
    }
}
