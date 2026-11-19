package com.scivicslab.chatui.core.actor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A conversation's external MCP servers: each one's name and the URL of its MCP endpoint
 * ({@code ExternalMcpServersOfAConversation_261120_oo01}).
 *
 * <p>A name starts with a lowercase letter and holds only lowercase letters, digits and {@code -}, at most 20 of
 * them, so that {@code <name>__<tool>} splits at its first {@code __}. A URL starts with {@code http://} or
 * {@code https://}.</p>
 */
public final class McpServerList {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");

    private McpServerList() {
    }

    /**
     * @param json a JSON object from server name to URL; blank is the empty list
     * @return the servers, by name
     * @throws IllegalArgumentException when it is not such an object, or a name or URL breaks the rules; the message
     *                                  says which
     */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("servers must be a JSON object from server name to URL");
        }
        Map<String, String> servers = new java.util.TreeMap<>();
        for (String name : o.keySet()) servers.put(name, o.opt(name) instanceof String s ? s : "");
        return check(servers);
    }

    /**
     * @return {@code servers}, unchanged, as an unmodifiable map by name
     * @throws IllegalArgumentException when a name or URL breaks the rules
     */
    public static Map<String, String> check(Map<String, String> servers) {
        for (Map.Entry<String, String> e : servers.entrySet()) {
            if (!NAME.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("server name '" + e.getKey() + "' must start with a lowercase letter"
                        + " and hold only lowercase letters, digits and '-', at most 20 characters");
            }
            String url = e.getValue() == null ? "" : e.getValue().strip();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("the URL of server '" + e.getKey() + "' must start with http:// or https://");
            }
        }
        return Collections.unmodifiableMap(new java.util.TreeMap<>(servers));
    }

    /** @return the servers as a JSON object text */
    public static String toJson(Map<String, String> servers) {
        JSONObject o = new JSONObject();
        servers.forEach(o::put);
        return o.toString();
    }
}
