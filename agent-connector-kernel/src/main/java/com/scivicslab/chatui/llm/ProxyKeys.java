package com.scivicslab.chatui.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * The scopes the LLM proxy answers for, each with the throw-away key its harness is given in place of the real
 * credential ({@code LlmKeyInjectionProxy_261121_oo01}; tasks/todo.md stage 8). A plain object: run it as an actor so
 * registering and checking never overlap.
 */
public final class ProxyKeys {

    private final Map<String, byte[]> keys = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** @return a new throw-away key for {@code scope}, replacing any earlier one */
    public String register(String scope) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String key = "ack-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        keys.put(scope, key.getBytes(StandardCharsets.UTF_8));
        return key;
    }

    /** Forgets a scope; its throw-away key no longer opens anything. */
    public void unregister(String scope) {
        keys.remove(scope);
    }

    /** @return whether a scope of that name is registered */
    public boolean has(String scope) {
        return scope != null && keys.containsKey(scope);
    }

    /** @return whether {@code key} is the scope's throw-away key, compared in constant time */
    public boolean opens(String scope, String key) {
        byte[] expected = scope == null ? null : keys.get(scope);
        return expected != null && key != null && MessageDigest.isEqual(expected, key.getBytes(StandardCharsets.UTF_8));
    }
}
