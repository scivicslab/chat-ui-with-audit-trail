package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.turingworkflow.plugins.rstframe.FrameFiller;
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
 * {@code concept-tree.yaml} run end to end on a plan runner, with a writer conversation whose
 * model is a fake that answers by what it is asked ({@code FrameMakingWorkflow_261022_oo01}, step 2).
 */
@DisplayName("concept-tree: 本文から概念の木を作る")
class ConceptTreeWorkflowTest {

    private static final String RULES = """
            (defrule document -> title sections)
            (defrule title -> "How the loop works")
            (defrule sections -> s-turns s-control)
            (defrule s-turns -> p-turn :heading "## Turns and messages")
            (defrule p-turn -> :nucleus p-turn/n)
            (defrule p-turn/n -> "A turn is one model call and the tools it asks for.")
            (defrule s-control -> p-max :heading "## Control how the loop runs")
            (defrule p-max -> :nucleus p-max/n)
            (defrule p-max/n -> "Max turns caps how many turns a run may take.")
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
            String reply = prompt.contains("the same thing as one of these") ? "DIFFERENT"
                    : prompt.contains("Which one of these is the whole document about") ? "agent loop"
                    : prompt.startsWith("In a document, max turns is explained as belonging to") ? "HERE"
                    : prompt.startsWith("In a document, turn belongs to") ? "PART-OF"
                    : prompt.startsWith("In a document, max turns belongs to") ? "SETTING-OF"
                    : prompt.contains("How the loop works") ? "NEW: agent loop"
                    : prompt.contains("A turn is one model call") ? "NEW: turn"
                    : prompt.contains("Max turns caps") ? "NEW: max turns"
                    : "?";
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
        system = new IIActorSystem("concept-tree-test");
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
        runner.setFileScope(new FileAccessScope(dir, List.of(dir)));
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));

        Path paper = dir.resolve("loop");
        Files.createDirectories(paper);
        Files.writeString(paper.resolve("loop.md"), "# How the loop works\n");
        Files.writeString(paper.resolve("loop.lisp"), RULES);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("loop").toString()}, {"name", "loop-doc"}, {"out", dir.resolve("subjects/loop-doc.lisp").toString()},
                {"given", ""}, {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"}, {"maxQuestions", "50"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/concept-tree.yaml");
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
    @DisplayName("段落ごとに概念を聞き、作業の見出しは節点にせず、読み終えてから同じものと関係を聞いて木にする")
    void eachParagraphIsPlacedAndTheTreeIsWritten() throws Exception {
        String outcome = run();

        assertEquals(dir.resolve("subjects/loop-doc.lisp").toString(), outcome, "questions so far: " + writer.questions);
        assertEquals(10, writer.questions.size(), "3 paragraphs, 3 sameness, the root, 1 step down and 2 relation questions: " + writer.questions);
        String tree = Files.readString(dir.resolve("subjects/loop-doc.lisp"));
        assertTrue(tree.contains("(subject \"1\" \"agent loop\" :paragraphs \"title\")"), tree);
        assertTrue(tree.contains("(subject \"1.1\" \"turn\" :relation part-of :paragraphs \"p-turn\")"), tree);
        assertTrue(tree.contains("(subject \"1.2\" \"max turns\" :relation setting-of :paragraphs \"p-max\")"), tree);
        assertFalse(tree.contains("Control how the loop runs\" "), "the task heading is no node: " + tree);
        for (String q : writer.questions) assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
    }
}
