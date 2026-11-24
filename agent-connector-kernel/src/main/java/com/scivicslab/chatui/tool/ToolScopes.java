package com.scivicslab.chatui.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The scopes a localhost MCP server answers for, each with the key a request must carry (tasks/todo.md, Decisions 5).
 * A plain object: run it as an actor so registering and looking up never overlap.
 */
public final class ToolScopes {

    private record Entry(ToolRunner runner, byte[] key) {
    }

    private final Map<String, Entry> scopes = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Registers {@code runner} under its scope's name, replacing any earlier one, with a new key.
     *
     * @return the key a request for this scope must carry
     */
    public String register(ToolRunner runner) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        scopes.put(runner.scope().name(), new Entry(runner, key.getBytes(StandardCharsets.UTF_8)));
        return key;
    }

    /** Forgets a scope; its key no longer opens anything. */
    public void unregister(String scopeName) {
        scopes.remove(scopeName);
    }

    /** @return whether a scope of that name is registered */
    public boolean has(String scopeName) {
        return scopeName != null && scopes.containsKey(scopeName);
    }

    /**
     * @param scopeName the scope a request names
     * @param key       the key it carries, or {@code null}
     * @return the scope's runner when the key is the scope's key, compared in constant time; otherwise {@code null}
     */
    public ToolRunner open(String scopeName, String key) {
        Entry entry = scopeName == null ? null : scopes.get(scopeName);
        if (entry == null || key == null) return null;
        return MessageDigest.isEqual(entry.key(), key.getBytes(StandardCharsets.UTF_8)) ? entry.runner() : null;
    }
}
