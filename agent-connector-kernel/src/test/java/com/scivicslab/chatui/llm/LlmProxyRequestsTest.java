package com.scivicslab.chatui.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The LLM proxy passes a request on only for a registered scope with that scope's throw-away key, and then with the
 * real credential in its place (tasks/todo.md stage 8). Every key here is a dummy.
 */
class LlmProxyRequestsTest {

    private static final String DUMMY_CREDENTIAL = "dummy-real-credential";

    private ProxyKeys keys;
    private LlmProxyRequests requests;
    private String key;

    @BeforeEach
    void setUp() {
        keys = new ProxyKeys();
        key = keys.register("s1");
        requests = new LlmProxyRequests(keys::has, keys::opens, URI.create("https://upstream.invalid"), "x-api-key",
                DUMMY_CREDENTIAL);
    }

    @Test
    void noKeyIsRefused() {
        LlmProxyRequests.Decision d = requests.decide("/s1/v1/messages", Map.of());
        assertEquals(401, assertInstanceOf(LlmProxyRequests.Refuse.class, d).status());
    }

    @Test
    void aWrongKeyIsRefused() {
        LlmProxyRequests.Decision d = requests.decide("/s1/v1/messages", Map.of("x-api-key", List.of(key + "x")));
        assertEquals(401, assertInstanceOf(LlmProxyRequests.Refuse.class, d).status());
    }

    @Test
    void theRightKeyIsReplacedByTheRealCredential() {
        LlmProxyRequests.Decision d = requests.decide("/s1/v1/messages?beta=true",
                Map.of("X-Api-Key", List.of(key), "Content-Type", List.of("application/json"), "Host", List.of("127.0.0.1")));
        LlmProxyRequests.Forward f = assertInstanceOf(LlmProxyRequests.Forward.class, d);
        assertEquals(URI.create("https://upstream.invalid/v1/messages?beta=true"), f.target());
        assertEquals(DUMMY_CREDENTIAL, f.headers().get("x-api-key"));
        assertEquals("application/json", f.headers().get("content-type"));
        assertFalse(f.headers().containsKey("host"));
        assertFalse(f.headers().containsValue(key), "the throw-away key is not passed on");
    }

    /**
     * An unknown scope is refused exactly like a wrong key, so a process without a key cannot learn which scopes
     * exist. Only a request that names no scope at all is told apart (400).
     */
    @Test
    void anUnknownScopeIsRefusedLikeAWrongKey() {
        LlmProxyRequests.Decision unknown = requests.decide("/s2/v1/messages", Map.of("x-api-key", List.of(key)));
        LlmProxyRequests.Decision wrongKey = requests.decide("/s1/v1/messages", Map.of("x-api-key", List.of(key + "x")));
        assertEquals(401, assertInstanceOf(LlmProxyRequests.Refuse.class, unknown).status());
        assertEquals(wrongKey, unknown);
    }

    @Test
    void aRequestThatNamesNoScopeIs400() {
        assertEquals(400, assertInstanceOf(LlmProxyRequests.Refuse.class,
                requests.decide("/", Map.of("x-api-key", List.of(key)))).status());
        assertEquals(400, assertInstanceOf(LlmProxyRequests.Refuse.class,
                requests.decide("", Map.of())).status());
    }
}
