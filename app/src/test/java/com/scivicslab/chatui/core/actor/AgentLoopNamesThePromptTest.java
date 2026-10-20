package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The agent loop decides how each step's prompt is built: a loop names its prompt construction
 * with a top-level {@code promptWorkflow}, and loading the loop installs it
 * ({@code AgentLoopDecidesThePrompt_261020_oo01}).
 */
@DisplayName("エージェントループが、プロンプト構築を決める")
class AgentLoopNamesThePromptTest {

    private static final class RecordingProvider implements LlmProvider {
        final List<String> received = new ArrayList<>();
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            received.add(prompt);
            emitter.accept(ChatEvent.delta("reply"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static ChatSessionIIAR conversation(RecordingProvider provider) {
        IIActorSystem system = new IIActorSystem("agent-loop-names-the-prompt-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        return iiar;
    }

    private static String promptWorkflowOf(ChatSessionIIAR iiar) {
        return iiar.asChatSessionRef().ask(ChatSession::getPromptWorkflowFile).join();
    }

    @Test
    @DisplayName("ループが promptWorkflow を書いていれば、それが読み取られる")
    void aLoopThatNamesOne_isReadAsNamed() {
        byte[] yaml = "name: x\npromptWorkflow: prompt-construction-plain.yaml\nsteps: []\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("prompt-construction-plain.yaml", ChatSessionIIAR.promptWorkflowNamedBy(yaml, "x"));
    }

    @Test
    @DisplayName("書いていないループ、空のキーは、既定になる")
    void aLoopThatNamesNone_getsTheDefault() {
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW,
                ChatSessionIIAR.promptWorkflowNamedBy("name: x\nsteps: []\n".getBytes(StandardCharsets.UTF_8), "x"));
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW,
                ChatSessionIIAR.promptWorkflowNamedBy("promptWorkflow: ''\n".getBytes(StandardCharsets.UTF_8), "x"));
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW,
                ChatSessionIIAR.promptWorkflowNamedBy("- just\n- a list\n".getBytes(StandardCharsets.UTF_8), "x"));
    }

    @Test
    @DisplayName("ループを替えると、プロンプト構築もそのループのものになり、前のループのものは残らない")
    void switchingLoops_switchesThePromptConstructionWithIt() {
        ChatSessionIIAR iiar = conversation(new RecordingProvider());
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW, promptWorkflowOf(iiar));

        assertNull(iiar.setAgentLoopWorkflowFile("plain-text-loop.yaml"));
        assertEquals("prompt-construction-plain.yaml", promptWorkflowOf(iiar));

        assertNull(iiar.setAgentLoopWorkflowFile("doc-retrieval-loop.yaml"));
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW, promptWorkflowOf(iiar),
                "a loop that names none does not keep the earlier loop's prompt construction");

        assertNull(iiar.setAgentLoopWorkflowFile("chat-session-agent-loop.yaml"));
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW, promptWorkflowOf(iiar));
    }

    @Test
    @DisplayName("plain-text-loop を選んだ会話は、利用者の文だけをモデルへ送る")
    void thePlainTextLoop_sendsOnlyTheUsersText() {
        RecordingProvider provider = new RecordingProvider();
        ChatSessionIIAR iiar = conversation(provider);
        assertNull(iiar.setAgentLoopWorkflowFile("plain-text-loop.yaml"));

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("hello", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        assertEquals(List.of("hello"), provider.received);
    }

    @Test
    @DisplayName("存在しないプロンプト構築を書いたループは断られ、会話は前のループのまま動く")
    void aLoopNamingAMissingPromptConstruction_isRefusedAndTheConversationKeepsItsLoop() {
        RecordingProvider provider = new RecordingProvider();
        ChatSessionIIAR iiar = conversation(provider);

        String refused = iiar.setAgentLoopWorkflowFile("names-a-missing-prompt-construction.yaml");

        assertNotNull(refused);
        assertTrue(refused.contains("no-such-prompt-construction.yaml"), refused);
        assertEquals("chat-session-agent-loop.yaml", iiar.getAgentLoopWorkflowFile());
        assertEquals(ChatSession.DEFAULT_PROMPT_WORKFLOW, promptWorkflowOf(iiar));

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("hello", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        assertEquals(1, provider.received.size(), "the conversation still answers, on the loop it had");
    }
}
