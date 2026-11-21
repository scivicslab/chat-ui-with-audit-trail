package com.scivicslab.chatui.agent;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * DocSearchTool's HTTP client and base URL are made on first use, not when the class is initialized, so a native image
 * built with Quarkus (which initializes application classes at build time) holds neither in its image heap (tasks/todo.md,
 * stage 12). chat-ui's behaviour is unchanged: one client with a 10 s connect timeout, reused after the first call, and
 * one base URL.
 */
class DocSearchToolClientTest {

    @Test
    void oneClientIsMadeAndReusedWithTheSameSettings() {
        HttpClient first = DocSearchTool.httpClient();
        assertSame(first, DocSearchTool.httpClient());
        assertEquals(Optional.of(Duration.ofSeconds(10)), first.connectTimeout());
    }

    @Test
    void theBaseUrlIsResolvedOnceAndKept() {
        String first = DocSearchTool.baseUrl();
        assertSame(first, DocSearchTool.baseUrl());
    }
}
