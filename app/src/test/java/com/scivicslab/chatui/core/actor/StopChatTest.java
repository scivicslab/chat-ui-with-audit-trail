package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stopping a conversation a plan has given up waiting for
 * ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}).
 *
 * <p>One run's fixer answered for thirty minutes and 131,000 characters. The plan timed out and
 * the job ended, and the conversation carried on writing, holding the GPU. Whoever stops waiting
 * has to stop the work as well.</p>
 */
@DisplayName("PlanRunner — stopChat")
class StopChatTest {

    /** Says whether it was told to stop. */
    private static final class CancellableProvider implements LlmProvider {
        boolean cancelled;
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) { }
        @Override public void cancel() { cancelled = true; }
        @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) { }
    }

    private IIActorSystem system;
    private PlanRunner runner;
    private CancellableProvider provider;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("stop-chat-test");
        runner = new PlanRunner("plan", system, null);
        PlanRunnerIIAR iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);

        provider = new CancellableProvider();
        ChatSessionIIAR chat = new ChatSessionIIAR("project1/chat-02.chat", provider,
                Optional.empty(), new IoLogStore(), system);
        system.addIIActor(chat);
        chat.<LlmProvider>createChild("project1/chat-02.chat.provider", provider);
        chat.tellNow(a -> {
            ((ChatSession) a).setProviderName("project1/chat-02.chat.provider");
            ((ChatSession) a).setChatIdentity("project1", "02");
        }).join();
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void theConversationIsToldToStop() {
        ActionResult result = runner.stopChat("project1/chat-02");

        assertTrue(result.isSuccess(), result.getResult());
        assertTrue(provider.cancelled, "the call it was in the middle of is cancelled");
    }

    @Test
    void aConversationThatIsNotThereIsSaidSoRatherThanPassedOver() {
        ActionResult result = runner.stopChat("project1/chat-nobody");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("project1/chat-nobody"), result.getResult());
    }
}
