package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * {@code make-frame.yaml} run end to end on a plan runner, with a writer conversation whose model is
 * a fake that answers by what it is asked ({@code FramesAndSamplesFromBooks_261021_oo01}).
 */
@DisplayName("make-frame: お手本 1 本から、同じ名前のフレームを作る")
class MakeFrameTest {

    private static final String SAMPLE = """
            ;;; use: Shows a short program whole, then defines what it uses.
            ;;; source: /books/core/p001.md / 3.1. A Simple Java Program
            ;;; shape: nucleus → code → nucleus+definition
            (in-package :rst)
            (defdocument ShowWholeThenExplainSample)

            (defrule document -> title sections)
            (defrule title -> "3.1. A Simple Java Program")
            (defrule sections -> s1)

            (defrule s1 -> b1 b2 b3 :heading "## 3.1. A Simple Java Program" :source "/books/core/p001.md")
            (defrule b1 -> :nucleus b1/1)
            (defrule b1/1 -> "Let's look at one of the simplest Java programs:")
            (defrule b2 -> :file "show-whole-then-explain.b2.txt" :kind code-block)
            (defrule b3 -> :nucleus b3/1 :definition b3/2)
            (defrule b3/1 -> "The program declares a method called main.")
            (defrule b3/2 -> "The term \\"method\\" is Java-speak for a function.")
            """;

    /** Answers by what it is asked; remembers every question exactly as it arrived. */
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
            String reply;
            if (prompt.contains("code block")) {
                reply = "Show the whole program being explained.";
            } else if (prompt.contains("main.")) {
                reply = "1: Say where in the code the item is declared.\n2: Define the item in plain words.";
            } else {
                reply = "1: Introduce the program the section is about.";
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
        system = new IIActorSystem("make-frame-test");
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

        Files.createDirectories(dir.resolve("samples"));
        Files.writeString(dir.resolve("samples/show-whole-then-explain.lisp"), SAMPLE);
        Files.writeString(dir.resolve("samples/show-whole-then-explain.b2.txt"), "void main() {}\n");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"name", "show-whole-then-explain"}, {"samples", dir.resolve("samples").toString()},
                {"frames", dir.resolve("frames").toString()},
                {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"}, {"maxQuestions", "100"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/make-frame.yaml");
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
    @DisplayName("まとまりごとに指示を聞き、frames/<名前>.lisp を書く")
    void theSampleBecomesAFrameFile() throws Exception {
        String outcome = run();

        Path frame = dir.resolve("frames/show-whole-then-explain.lisp");
        assertEquals(frame.toString(), outcome, "questions so far: " + writer.questions);
        assertEquals(3, writer.questions.size(), writer.questions.toString());
        assertTrue(writer.questions.get(1).contains("void main() {}"), "the code file is read: " + writer.questions.get(1));
        String file = Files.readString(frame);
        assertTrue(file.contains(";;; made-from: show-whole-then-explain"), file);
        assertTrue(file.contains("(defrule b2 -> :instruction \"Show the whole program being explained.\" :kind code-block)"), file);
        assertTrue(file.contains("(defrule b3/2 -> :instruction \"Define the item in plain words.\""), file);
        for (String q : writer.questions) assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
    }

    @Test
    @DisplayName("同じ名前のフレームがあれば上書きせず、失敗を報告する")
    void anExistingFrameIsNotOverwritten() throws Exception {
        Files.createDirectories(dir.resolve("frames"));
        Files.writeString(dir.resolve("frames/show-whole-then-explain.lisp"), "keep me\n");

        String outcome = run();

        assertTrue(outcome.startsWith("(plan"), outcome);
        assertEquals("keep me\n", Files.readString(dir.resolve("frames/show-whole-then-explain.lisp")));
    }
}
