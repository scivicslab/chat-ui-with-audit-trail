package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code concept-consolidate.yaml} run end to end on a plan runner, with a writer conversation whose
 * model is a fake that answers by what it is asked ({@code ConceptClasses_261022_oo01}).
 */
@DisplayName("concept-consolidate: 概念のクラスの木を小さくする")
class ConceptConsolidateWorkflowTest {

    private static final String RESULT = """
            ;;; book-file: /w/loop.lisp
            (in-package :concepts)
            (defclass configuration-setting ()
              ((values :documentation "Which values does it take"))
              (:documentation "A value that changes how the loop runs"))
            (defclass effort-level (configuration-setting) ()
              (:documentation "The effort option"))
            (defclass permission-mode (configuration-setting)
              ((tool-approval :documentation "Which tool calls need approval?"))
              (:documentation "How tools are approved"))
            (assign "effort level" effort-level
              (values "p-effort"))
            """;

    /** Answers by what it is asked; remembers every question. */
    private static final class FakeWriter implements LlmProvider {
        final List<String> questions = new ArrayList<>();

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            questions.add(prompt);
            String reply = prompt.contains("tool-approval: Which tool calls need approval?") ? "NO" : "?";
            emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @TempDir Path dir;
    private IIActorSystem system;
    private FakeWriter writer;
    private ChatSessionIIAR writerChat;
    private PlanRunnerIIAR plan;

    @BeforeEach
    void setUp() throws Exception {
        system = new IIActorSystem("concept-consolidate-test");
        writer = new FakeWriter();
        writerChat = new ChatSessionIIAR("project1/chat-02.chat", writer, Optional.empty(), null, system);
        system.addIIActor(writerChat);
        ActorRef<LlmProvider> providerRef = writerChat.<LlmProvider>createChild(writerChat.getName() + ".provider", writer);
        writerChat.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", "02");
        }).join();

        ActorRef<PromptQueue> queue = writerChat.createChild("project1/chat-02.queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<CallWatchdog> watchdog = writerChat.createChild("watchdog-for-test", new CallWatchdog());

        PlanRunner runner = new PlanRunner("plan", system);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
        system.addIIActor(new WorkflowPluginLoader("loader", system, List.of()));
        system.addIIActor(new ChatsIIAR("chats", new Chats(system, watchdog, null), system));
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));

        Files.writeString(dir.resolve("loop.result.lisp"), RESULT);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"classes", ""}, {"input", dir.resolve("loop.result.lisp").toString()}, {"out", dir.resolve("loop.smaller.lisp").toString()},
                {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"}, {"maxQuestions", "50"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/concept-consolidate.yaml");
        byte[] bytes = yaml.readAllBytes();
        plan.tellNow(interp -> {
            PlanRunner r = (PlanRunner) interp;
            r.setDone(done);
            r.reset();
            r.readYaml(new ByteArrayInputStream(bytes));
            r.runUntilEnd();
            r.complete("(plan ended without finish)");
        }).join();
        return done.get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("自分のスロットの問いを 1 つ聞き、何も言わない下位クラスを値にして、単独で読めるファイルを書く")
    void theTreeIsMadeSmallerAndWritten() throws Exception {
        String outcome = run();

        assertEquals(dir.resolve("loop.smaller.lisp").toString(), outcome, "questions so far: " + writer.questions);
        assertEquals(1, writer.questions.size(), writer.questions.toString());
        String out = Files.readString(dir.resolve("loop.smaller.lisp"));
        assertTrue(out.contains(";;; standalone"), out);
        assertTrue(out.contains("(instance \"effort level\" configuration-setting)"), out);
        assertTrue(out.contains("(defclass permission-mode (configuration-setting)"), out);
        assertTrue(out.contains("(assign \"effort level\" configuration-setting\n  (values \"p-effort\"))"), out);
        for (String q : writer.questions) assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
    }
}
