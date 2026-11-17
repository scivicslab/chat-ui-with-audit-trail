package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The conversation registry as a plain object, on a real I/O log, without an actor system
 * ({@code ConversationRestoreOnRestart_260904_oo01}).
 */
class ConversationRegistryTest {

    @TempDir Path dir;
    private IoLogStore log;
    private IoLogView view;
    private ConversationRegistry registry;

    @BeforeEach
    void open() {
        log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("registry-test").toString());
        view = IoLogView.over(log);
        registry = new ConversationRegistry(log, view);
    }

    @AfterEach
    void close() throws Exception {
        log.store().close();
    }

    @Test
    void aCreatedConversationIsListedForRestoreWithTheSettingsItWasCreatedWith() {
        registry.created("project1/chat-02", "openai-compat", "full", "model-a");

        long session = log.findResumableSession("project1/chat-02");
        assertTrue(session >= 0, "it has a session, which is what start-up reopens");
        IoLogView.Settings s = view.latestSettings(session);
        assertEquals("openai-compat", s.provider());
        assertEquals("full", s.tools());
        assertEquals("model-a", s.model());
    }

    @Test
    void aConversationBeingBroughtBackIsNotRecordedAgain() {
        registry.created("project1/chat-02", "openai-compat", "full", "model-a");
        // At the next start-up the conversation is created again from its record; the provider's default must
        // not be written over the model it was on.
        registry.created("project1/chat-02", "openai-compat", "full", "provider-default");

        assertEquals("model-a", view.latestSettings(log.findResumableSession("project1/chat-02")).model());
    }

    // Which conversations come back, and that a deleted one does not, is the project's list (ProjectConversationsTest).

    @Test
    void theAgentLoopPutOnAConversationIsReadBack() {
        registry.created("project1/chat-02", "openai-compat", "full", null);
        assertNull(registry.agentLoopOf("project1/chat-02"));

        registry.agentLoopSet("project1/chat-02", "file:plain-text-loop.yaml");
        registry.agentLoopSet("project1/chat-02", "yaml:\nname: mine\n");

        assertEquals("yaml:\nname: mine\n", registry.agentLoopOf("project1/chat-02"), "the last one is the loop");
    }

    @Test
    void anUnknownConversationHasNoLoop() {
        assertNull(registry.agentLoopOf("project1/chat-09"));
    }
}
