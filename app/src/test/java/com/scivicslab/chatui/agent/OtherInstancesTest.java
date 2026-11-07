package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a conversation tool finds out that the name it was given also (or only) exists in another instance. */
class OtherInstancesTest {

    private HttpServer peer;
    private String peerUrl;

    /** A peer instance that has {@code project8/chat-01} and nothing else, as the status route answers it. */
    @BeforeEach
    void startPeer() throws Exception {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peer.createContext("/api/projects/project8/chats/01/status", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        peer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        peer.start();
        peerUrl = "http://127.0.0.1:" + peer.getAddress().getPort();
    }

    @AfterEach
    void stopPeer() {
        peer.stop(0);
    }

    @Test
    void portsAndBaseUrlsAreBothAccepted() {
        assertEquals(List.of("http://localhost:28010", "http://other:28011"),
                OtherInstances.parsePeers("28010, http://other:28011/", null));
    }

    @Test
    void thisInstanceIsLeftOutAndDuplicatesAreCollapsed() {
        assertEquals(List.of("http://localhost:28010"),
                OtherInstances.parsePeers("28014,28010,28010,,", 28014));
    }

    @Test
    void emptyPropertyMeansNoPeers() {
        assertEquals(List.of(), OtherInstances.parsePeers("", 28014));
        assertEquals(List.of(), OtherInstances.parsePeers(null, 28014));
    }

    @Test
    void aPeerThatHasTheConversationIsFound() {
        assertEquals(List.of(peerUrl), OtherInstances.having(List.of(peerUrl), "project8/chat-01"));
    }

    @Test
    void aPeerThatLacksTheConversationIsNotListed() {
        assertEquals(List.of(), OtherInstances.having(List.of(peerUrl), "project10/chat-02"));
    }

    @Test
    void aPeerThatDoesNotAnswerIsNotListedAndDoesNotFailTheAnswer() {
        peer.stop(0);
        assertEquals(List.of(), OtherInstances.having(List.of(peerUrl), "project8/chat-01"));
    }

    @Test
    void aNameWithoutChatPartIsNotAskedAbout() {
        assertTrue(OtherInstances.having(List.of(peerUrl), "project8").isEmpty());
    }
}
