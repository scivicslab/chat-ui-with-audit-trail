package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for {@code CliHarnessProvider_260912_oo01}'s tool split: a conversation's
 * {@link ToolSet} decides both which tools its first prompt lists and which calls it executes.
 */
class ChatSessionToolSetTest {

    /** Records every prompt; answers the first with a read call, then with plain text. */
    private static final class RecordingProvider implements LlmProvider {
        final List<String> prompts = new ArrayList<>();
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                emitter.accept(ChatEvent.delta("""
                        <invoke name="read">
                        <parameter name="path">no-such-file-for-this-test</parameter>
                        </invoke>"""));
            } else {
                emitter.accept(ChatEvent.delta("done"));
            }
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    /** A plugin tool the way plugin-web-tools adds one. */
    private static final class FakeWebTool implements ConversationTool {
        @Override public String name() { return "web_search"; }
        @Override public String description() { return "- web_search(query): search the web (fake).\n"; }
        @Override public String execute(String argumentsJson) { return "fake results"; }
    }

    private static RecordingProvider runTurn(ToolSet toolSet) {
        RecordingProvider provider = new RecordingProvider();
        IIActorSystem system = new IIActorSystem("chat-session-tool-set-test-" + toolSet.id());
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setToolSet(toolSet);
            ((ChatSession) a).setPluginTools(List.of(new FakeWebTool()));
        }).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("read something", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return provider;
    }

    @Test
    void firstStepPrompt_fullToolSet_listsEveryToolAndExecutesRead() {
        RecordingProvider p = runTurn(ToolSet.FULL);
        String first = p.prompts.get(0);
        for (String name : List.of("read", "calc", "search_docs", "list_references", "write", "ask_chat",
                "set_workflow", "run_plan", "load_skill", "set_collaborator")) {
            assertTrue(first.contains("- " + name + "("), "full prompt lists " + name);
        }
        assertTrue(first.contains("- web_search(query): search the web (fake)."), "full prompt lists the plugin tool");
        assertTrue(first.contains("read may read files under:"), first);
        assertFalse(first.contains("your own coding harness"), "a bare model gets no harness preface");
        assertEquals(2, p.prompts.size(), "read was executed and its observation sent back");
        assertTrue(p.prompts.get(1).startsWith("Tool result (read):"), p.prompts.get(1));
        assertFalse(p.prompts.get(1).contains("is not available in this conversation"), p.prompts.get(1));
    }

    @Test
    void firstStepPrompt_collaborationToolSet_listsOnlyCollaborationToolsAndRefusesRead() {
        RecordingProvider p = runTurn(ToolSet.COLLABORATION);
        String first = p.prompts.get(0);
        for (String name : ToolSet.COLLABORATION.names()) {
            assertTrue(first.contains("- " + name + "("), "collaboration prompt lists " + name);
        }
        for (String name : List.of("read", "write", "calc", "web_search")) {
            assertFalse(first.contains("- " + name + "("), "collaboration prompt must not list " + name);
        }
        assertFalse(first.contains("read may read files under:"), first);
        assertTrue(first.startsWith("You are running inside your own coding harness"),
                "a harness is told its own tools stay usable and the list is additional");
        assertEquals(2, p.prompts.size());
        assertTrue(p.prompts.get(1).contains("error: tool 'read' is not available in this conversation"),
                p.prompts.get(1));
    }

    @Test
    void parse_knownAndUnknownNames_mapOrThrow() {
        assertEquals(ToolSet.FULL, ToolSet.parse("Full"));
        assertEquals(ToolSet.COLLABORATION, ToolSet.parse(" collaboration "));
        assertTrue(ToolSet.COLLABORATION.contains("ask_chat"));
        assertFalse(ToolSet.COLLABORATION.contains("read"));
        try {
            ToolSet.parse("harness");
            assertTrue(false, "expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("harness"));
        }
    }
}
