package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the step limit as a workflow transition
 * ({@code TurnResourceLimits_260830_oo01}): how many LLM calls one turn may make is the number in
 * the workflow's step-limit transition, not a constant in Java.
 *
 * <p>The fake provider never stops on its own — every reply asks for another tool call — so the
 * only thing that can end the turn is the limit.</p>
 */
class ChatSessionStepLimitTest {

    /** Always answers with a tool call, so the loop only ends when something stops it. */
    private static final class NeverFinishingProvider implements LlmProvider {
        final List<String> calls = new ArrayList<>();

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            calls.add(prompt);
            emitter.accept(ChatEvent.delta("""
                    <invoke name="read">
                    <parameter name="path">no-such-file-for-this-test</parameter>
                    </invoke>"""));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static NeverFinishingProvider runTurn(String agentLoopYaml) {
        NeverFinishingProvider provider = new NeverFinishingProvider();
        IIActorSystem system = new IIActorSystem("chat-session-step-limit-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        if (agentLoopYaml != null) {
            iiar.tellNow(a -> {
                ChatSession c = (ChatSession) a;
                c.reset();
                try (InputStream in = ChatSessionStepLimitTest.class.getResourceAsStream(
                        "/workflows/" + agentLoopYaml)) {
                    c.readYaml(in);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).join();
        }

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("find something", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return provider;
    }

    @Test
    void theTurnStopsAtTheNumberTheWorkflowNames() {
        assertEquals(6, runTurn(null).calls.size(),
                "chat-session-agent-loop.yaml's step-limit transition names 6");

        assertEquals(2, runTurn("step-limit-2.yaml").calls.size(),
                "the same machinery with 2 in the YAML stops after 2 — the number is the workflow's");
    }

    @Test
    void theSecondStepSeesTheFirstStepsObservation() {
        List<String> prompts = runTurn("step-limit-2.yaml").calls;

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("no-such-file-for-this-test"),
                "the tool result is what the next step is asked about, got: " + prompts.get(1));
    }
}
