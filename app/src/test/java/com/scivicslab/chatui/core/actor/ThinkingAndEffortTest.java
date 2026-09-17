package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;

import org.junit.jupiter.api.Test;

/**
 * Thinking and effort are settings of the conversation, because a workflow's {@code ask_chat}
 * carries a prompt and nothing else ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}).
 */
class ThinkingAndEffortTest {

    /** Records what each prompt was given, and what the conversation told it about effort. */
    private static final class RecordingProvider implements LlmProvider {
        final List<Boolean> noThinkSeen = new ArrayList<>();
        String effort;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) { }
        @Override public void cancel() { }
        @Override public void setEffort(String value) { effort = value; }
        @Override public String getEffort() { return effort; }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            noThinkSeen.add(ctx.noThink());
            emitter.accept(ChatEvent.delta("done"));
        }
    }

    @Test
    void aConversationToldNotToThink_saysSoOnEveryPrompt() {
        RecordingProvider provider = new RecordingProvider();
        ChatSession session = new ChatSession(provider, Optional.empty());

        assertFalse(session.isNoThink(), "a conversation thinks until it is told not to");
        session.setNoThink(true);

        assertTrue(session.isNoThink());
    }

    @Test
    void effort_isPassedToTheProviderAndReadBackFromIt() {
        RecordingProvider provider = new RecordingProvider();
        ChatSession session = new ChatSession(provider, Optional.empty());

        session.setEffort("high");

        assertEquals("high", provider.effort, "the provider is where the setting lives");
        assertEquals("high", session.getEffort());
    }

    @Test
    void aProviderWithNoSuchSetting_reportsNothing() {
        // The default methods of LlmProvider: a plain LLM has no effort to report.
        LlmProvider plain = new LlmProvider() {
            @Override public String id() { return "plain"; }
            @Override public String displayName() { return "Plain"; }
            @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
            @Override public String getCurrentModel() { return "m"; }
            @Override public void setModel(String model) { }
            @Override public void cancel() { }
            @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) { }
        };
        ChatSession session = new ChatSession(plain, Optional.empty());

        session.setEffort("high");

        org.junit.jupiter.api.Assertions.assertNull(session.getEffort());
    }
}
