package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@link ArtexProxyResource#isStreamingRequest}: no DB, no network — the
 * caller's declared {@code "stream"} field decides which relay path {@link ArtexProxyResource}
 * takes ({@code ArtexProxyLogging_261017_oo01}).
 */
@DisplayName("ArtexProxyResource — reading the caller's stream flag")
class ArtexProxyResourceTest {

    @Test
    void streamTrue_isStreaming() {
        assertTrue(ArtexProxyResource.isStreamingRequest(
                "{\"model\":\"m\",\"stream\":true,\"messages\":[]}"));
    }

    @Test
    void streamFalse_isNotStreaming() {
        assertFalse(ArtexProxyResource.isStreamingRequest(
                "{\"model\":\"m\",\"stream\":false,\"messages\":[]}"));
    }

    @Test
    void streamFieldAbsent_defaultsToNotStreaming() {
        assertFalse(ArtexProxyResource.isStreamingRequest("{\"model\":\"m\",\"messages\":[]}"));
    }

    @Test
    void notValidJson_treatedAsNotStreaming() {
        // The upstream server, not this check, is what rejects malformed JSON — treated as
        // non-streaming so the rejection comes back as one plain response, not a hung SSE relay.
        assertFalse(ArtexProxyResource.isStreamingRequest("not json at all"));
    }
}
