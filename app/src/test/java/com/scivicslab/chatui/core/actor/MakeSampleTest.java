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
 * {@code make-sample.yaml} run end to end on a plan runner, with a writer conversation whose model is
 * a fake that answers by what it is asked ({@code FramesAndSamplesFromBooks_261021_oo01}).
 */
@DisplayName("make-sample: 本の小節 1 つを、お手本の規則ファイル 1 本にする")
class MakeSampleTest {

    private static final String BOOK = """
            ---
            title: "x"
            ---

            ## 3.1. A Simple Java Program

            The program declares a method called mian. The term "method" is Java-speak for a function.

            ```
            void main() {}
            ```

            ## 3.2. Comments

            Comments do not show up in the executable program.
            """;

    /** Answers by what it is asked; remembers every question exactly as it arrived. */
    private static final class FakeWriter implements LlmProvider {
        final List<String> questions = new ArrayList<>();
        String judgement = "new: explain-program-by-whole-example";

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            questions.add(prompt);
            String reply;
            if (prompt.contains("same structure")) {
                reply = judgement;
            } else if (prompt.contains("misread")) {
                reply = "The program declares a method called main. The term \"method\" is Java-speak for a function.";
            } else if (prompt.contains("In one sentence")) {
                reply = "Shows a short program whole, then defines what it uses.";
            } else {
                reply = "1: nucleus\n2: definition";
            }
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
        system = new IIActorSystem("make-sample-test");
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

        PlanRunner runner = new PlanRunner("plan", system, watchdog);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
        system.addIIActor(new WorkflowPluginLoader("loader", system, List.of()));
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));

        Files.writeString(dir.resolve("book.md"), BOOK);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("book.md").toString()}, {"heading", "3.1. A Simple Java Program"},
                {"name", "explain-program-by-whole-example"}, {"samples", dir.resolve("samples").toString()},
                {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"}, {"maxQuestions", "200"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/make-sample.yaml");
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
    @DisplayName("段落の読み違いと関係、最後に用途を聞き、samples/<名前>.lisp とコードのファイルを書く")
    void theSubsectionBecomesASampleFile() throws Exception {
        String outcome = run();
        org.junit.jupiter.api.Assertions.assertNull(system.getIIActor("plan.sample"), "the workflow removes the actor it made");

        assertEquals("new explain-program-by-whole-example", outcome, "questions so far: " + writer.questions);
        assertEquals(3, writer.questions.size(), writer.questions.toString());
        Path sample = dir.resolve("samples/explain-program-by-whole-example.lisp");
        String file = Files.readString(sample);
        assertTrue(file.startsWith(";;; use: Shows a short program whole, then defines what it uses.\n"), file);
        assertTrue(file.contains("\"The program declares a method called main.\""), file);
        assertTrue(file.contains(":file \"explain-program-by-whole-example.b2.txt\" :kind code-block"), file);
        assertEquals("void main() {}\n", Files.readString(dir.resolve("samples/explain-program-by-whole-example.b2.txt")));
        for (String q : writer.questions) assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
    }

    @Test
    @DisplayName("同じ名前のお手本があれば上書きせず、失敗を報告する")
    void anExistingSampleIsNotOverwritten() throws Exception {
        Files.createDirectories(dir.resolve("samples"));
        Files.writeString(dir.resolve("samples/explain-program-by-whole-example.lisp"), "keep me\n");

        String outcome = run();

        org.junit.jupiter.api.Assertions.assertNull(system.getIIActor("plan.sample"), "the workflow removes the actor it made");

        assertTrue(outcome.startsWith("(plan"), outcome);
        assertEquals("keep me\n", Files.readString(dir.resolve("samples/explain-program-by-whole-example.lisp")));
    }

    @Test
    @DisplayName("既にあるお手本と同じ構造と判定されれば、お手本は書かず、そのお手本に also-seen を 1 行足す")
    void theSameStructureAddsOneLine() throws Exception {
        Files.createDirectories(dir.resolve("samples"));
        String known = ";;; use: Shows a program whole, then defines.\n;;; source: /b/p001.md / 3.1\n;;; shape: nucleus+definition → code\n(in-package :rst)\n";
        Files.writeString(dir.resolve("samples/explain-program-by-whole-example.lisp"), known);
        writer.judgement = "same: explain-program-by-whole-example";

        String outcome = run();

        org.junit.jupiter.api.Assertions.assertNull(system.getIIActor("plan.sample"), "the workflow removes the actor it made");

        assertEquals("same explain-program-by-whole-example", outcome, writer.questions.toString());
        String after = Files.readString(dir.resolve("samples/explain-program-by-whole-example.lisp"));
        assertTrue(after.contains(";;; also-seen: " + dir.resolve("book.md").toRealPath() + " / 3.1. A Simple Java Program\n(in-package"), after);
        assertFalse(Files.exists(dir.resolve("samples/explain-program-by-whole-example.b2.txt")));
    }
}
