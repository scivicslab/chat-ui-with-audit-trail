package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
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
 * Pure unit test for {@code CliHarnessProvider_260912_oo01}: a tool call the provider's own harness
 * makes inside one {@code sendPrompt} reaches the I/O log as a {@code turn{N}/step{M}/tool} entry
 * in the same {@code TOOL:/INPUT:/OBSERVATION:} form the conversation's own tools are recorded in.
 */
class ChatSessionHarnessToolIoTest {

    /** Captures what would be written to H2, without opening a database. */
    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String node, String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String tabId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(node, label, content));
        }
    }

    /** Behaves like a harness: calls its own tool, gets its result, then answers in plain text. */
    private static final class HarnessLikeProvider implements LlmProvider {
        @Override public String id() { return "fake-harness"; }
        @Override public String displayName() { return "Fake harness"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            emitter.accept(ChatEvent.toolUse("toolu_01", "Read", "{\"file_path\":\"/tmp/a.txt\"}"));
            emitter.accept(ChatEvent.toolResult("toolu_01", "hello from a.txt", false));
            emitter.accept(ChatEvent.toolUse("toolu_02", "Bash", "{\"command\":\"false\"}"));
            emitter.accept(ChatEvent.toolResult("toolu_02", "exit 1", true));
            emitter.accept(ChatEvent.delta("The file says hello."));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void stepExpectingAction_harnessToolUseAndResult_recordedAsToolStepAndTraced() {
        CapturingIoLog ioLog = new CapturingIoLog();
        HarnessLikeProvider provider = new HarnessLikeProvider();
        IIActorSystem system = new IIActorSystem("chat-session-harness-tool-io-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", "01");
        }).join();

        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("what does the file say?", null, emitted::add, chatRef,
                    new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        List<CapturingIoLog.Rec> toolRecs = ioLog.records.stream()
                .filter(r -> r.label().endsWith("/tool")).toList();
        assertEquals(2, toolRecs.size(), "one tool entry per harness tool call: " + ioLog.records);
        assertEquals("turn1/step1/tool", toolRecs.get(0).label());
        assertEquals("TOOL: Read\nINPUT:\n{\"file_path\":\"/tmp/a.txt\"}\nOBSERVATION:\nhello from a.txt",
                toolRecs.get(0).content());
        assertEquals("TOOL: Bash\nINPUT:\n{\"command\":\"false\"}\nOBSERVATION:\nerror: exit 1",
                toolRecs.get(1).content());

        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/llm")
                        && r.content().contains("RESPONSE:\nThe file says hello.")),
                "the LLM step is still recorded as before: " + ioLog.records);

        assertTrue(emitted.stream().anyMatch(e -> "thinking".equals(e.type())
                        && e.content().contains("→ Read({\"file_path\":\"/tmp/a.txt\"})")),
                "the browser trace shows the harness's call: " + emitted);
        assertTrue(emitted.stream().anyMatch(e -> "thinking".equals(e.type())
                        && e.content().startsWith("Observation (Bash): error: exit 1")),
                "the browser trace shows the harness's observation: " + emitted);
        assertTrue(emitted.stream().anyMatch(e -> "delta".equals(e.type())
                        && "The file says hello.".equals(e.content())),
                "the final answer is still delivered once: " + emitted);
    }
}
