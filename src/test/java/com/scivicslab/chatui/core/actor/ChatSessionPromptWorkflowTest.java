package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the prompt-construction sub-workflow indirection
 * ({@code ChatSessionPorting_260823_oo01} 2-b-i): {@link ChatSession#stepExpectingAction()} no
 * longer builds the text it sends to the provider directly — it drains a queue that a swappable
 * sub-workflow ({@link ChatSession#setPromptWorkflowFile(String)}) fills via
 * {@link ChatSession#appendConstructedPrompt(String)}. No CDI, no network: the fake
 * {@link LlmProvider} records every prompt text it receives, in order, and always replies with a
 * plain final answer (no tool call).
 */
class ChatSessionPromptWorkflowTest {

    private static final class RecordingProvider implements LlmProvider {
        final List<String> receivedPrompts = new ArrayList<>();

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            receivedPrompts.add(prompt);
            emitter.accept(ChatEvent.delta("reply #" + receivedPrompts.size()));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static RecordingProvider runTurn(String promptWorkflowFile, String userPrompt) {
        RecordingProvider provider = new RecordingProvider();
        IIActorSystem system = new IIActorSystem("chat-session-prompt-workflow-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar); // "actor: this"/".." resolve by name even with selfActorRef set
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        if (promptWorkflowFile != null) {
            iiar.tellNow(a -> ((ChatSession) a).setPromptWorkflowFile(promptWorkflowFile)).join();
        }

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start(userPrompt, null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return provider;
    }

    @Test
    void defaultWorkflow_prefixesSystemPromptOnFirstStepOnly() {
        RecordingProvider provider = runTurn(null, "hello");

        assertEquals(1, provider.receivedPrompts.size(),
                "default sub-workflow queues exactly one prompt per step; a plain reply ends the turn");
        String sent = provider.receivedPrompts.get(0);
        assertTrue(sent.endsWith("\n\nhello"), "expected system prompt prefix before the user's text, got: " + sent);
        assertTrue(sent.length() > "hello".length() + 2, "expected a non-empty system prompt prefix, got: " + sent);
    }

    @Test
    void customWorkflow_sendsEachQueuedPromptAsItsOwnLlmCall() {
        RecordingProvider provider = runTurn("custom-multi-prompt.yaml", "ignored by this workflow");

        assertEquals(List.of("first constructed prompt", "second constructed prompt"), provider.receivedPrompts,
                "both prompts the sub-workflow queued should reach the provider, in order, as separate calls");
    }
}
