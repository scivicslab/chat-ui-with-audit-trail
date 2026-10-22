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
 * {@code fill-frame.yaml} run end to end on a plan runner, with a writer conversation whose
 * model is a fake that answers by what it is asked ({@code WorkflowFromFrame_261020_oo01}).
 */
@DisplayName("fill-frame: フレームを最後まで埋める")
class FillFrameTest {

    private static final String REQUEST = """
            (in-package :rst)
            (defdocument Test_000000_oo01)
            (defrule document -> title sections)
            (defrule title -> "Squares")
            (defrule sections -> s1)
            (defrule s1 -> p-whole concepts p-run rules running :heading "## A Simple Squares Program")
            (defrule p-whole -> :nucleus p-whole/n :example p-whole/code)
            (defrule p-whole/n -> :instruction "Say in 1 or 2 sentences what the program does")
            (defrule p-whole/code -> :file "Squares.java" :kind code-block)
            (defrule concepts -> :instruction "List the concepts" :frame "concept.lisp" :heading "### Concepts")
            (defrule p-run -> :nucleus p-run/n :example p-run/code)
            (defrule p-run/n -> :instruction "Introduce the command that runs the program")
            (defrule p-run/code -> :instruction "Show that command" :kind code-block)
            (defrule rules -> :instruction "List the rules the program keeps" :sample "Declare main. / Use println" :kind bullet)
            (defrule running -> :instruction "How to run it" :include "running.lisp" :heading "### Running")
            """;

    private static final String FRAME = """
            (defrule concept -> :nucleus concept/n :definition concept/def)
            (defrule concept/n -> :instruction "Say where the concept is written in the code"
              :sample "The program declares a method called main.")
            (defrule concept/def -> :instruction "Define the concept"
              :sample "A method is a block of code that does one task.")
            """;

    /** Answers by what it is asked; remembers every question exactly as it arrived. */
    private static final class FakeWriter implements LlmProvider {
        final List<String> questions = new ArrayList<>();
        String listReply = "ArrayList\nfor loop";

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            questions.add(prompt);
            String reply = prompt.contains("List the items") ? listReply
                    : prompt.contains("only the code") ? "```\njava Squares.java\n```"
                    : prompt.contains("only the list's items") ? "- Name the class after the file\n- Print each square"
                    : "Sentence number " + questions.size() + ".";
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
        system = new IIActorSystem("fill-frame-test");
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

        Files.writeString(dir.resolve("request.lisp"), REQUEST);
        Files.writeString(dir.resolve("concept.lisp"), FRAME);
        Files.writeString(dir.resolve("running.lisp"), "(defrule running -> :nucleus running/n)\n(defrule running/n -> :instruction \"Say how to run it\")\n");
        Files.writeString(dir.resolve("Squares.java"), "class Squares {}\n");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        return run(dir.resolve("request.lisp"), dir, "");
    }

    private String run(Path request, Path frameDir, String works) throws Exception {
        for (String[] p : new String[][] {
                {"request", request.toString()}, {"subject", dir.resolve("Squares.java").toString()},
                {"frameDir", frameDir.toString()}, {"out", dir.resolve("filled.lisp").toString()},
                {"framesRoot", ""}, {"works", works}, {"maxExamples", "3"},
                {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"},
                {"language", "Japanese"}, {"maxItems", "5"}, {"maxBlanks", "80"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/fill-frame.yaml");
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
    @DisplayName("全部の空欄が、決まった順に 1 回ずつ聞かれて埋まり、書き手は前置きなしの質問だけを受ける")
    void everyBlankIsAskedOnceInOrderAndFilled() throws Exception {
        String outcome = run();

        assertEquals(dir.resolve("filled.lisp").toString(), outcome, "questions so far: " + writer.questions);
        String filled = Files.readString(dir.resolve("filled.lisp"));
        assertNull(new FrameFiller().next(filled), "a blank is left: " + filled);
        assertFalse(filled.contains(":instruction"), filled);
        assertTrue(filled.contains("(defrule concepts -> concepts/1 concepts/2 :heading \"### Concepts\")"), filled);

        // 1 whole-document sentence, 1 list, 2 items x 2 leaves, the run paragraph's sentence and code, the bullets,
        // then the included child frame's one sentence (the include itself asks nothing)
        assertEquals(10, writer.questions.size(), writer.questions.toString());
        assertTrue(writer.questions.get(7).contains("Sentence number 7."), "the code blank sees its paragraph: " + writer.questions.get(7));
        assertTrue(filled.contains("(defrule p-run/code -> :file \"p-run_code.txt\" :kind code-block)"), filled);
        assertEquals("java Squares.java\n", Files.readString(dir.resolve("p-run_code.txt")));
        assertTrue(filled.contains("(defrule rules -> rules/1 rules/2 :kind bullet)"), filled);
        assertTrue(filled.contains("(defrule rules/2 -> \"Print each square\")"), filled);
        assertTrue(filled.contains("(defrule running -> :nucleus running/n :heading \"### Running\")"), filled);
        assertTrue(filled.contains("(defrule running/n -> \"Sentence number 10.\")"), filled);
        assertTrue(writer.questions.get(0).startsWith("Below is the material that is about to be introduced"), writer.questions.get(0));
        assertTrue(writer.questions.get(1).contains("List the items"), writer.questions.get(1));
        assertTrue(writer.questions.get(2).contains("The item: \"ArrayList\""), writer.questions.get(2));
        assertTrue(writer.questions.get(3).contains("- n: Sentence number 3."), "the same item's earlier sentence is shown: " + writer.questions.get(3));
        assertTrue(writer.questions.get(4).contains("The item: \"for loop\""), writer.questions.get(4));
        assertFalse(writer.questions.get(4).contains("Sentence number 3."), "another item's sentence must not leak in");
        for (String q : writer.questions) {
            assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
        }
    }

    @Test
    @DisplayName("汎化フレームの空欄は、roles の表と made-from がたどる元の本の段落を、お手本として質問に添えて聞かれる")
    void aBlankOfAnIntegratedFrameIsAskedWithTheBooksParagraphsOfItsPart() throws Exception {
        Path frames = dir.resolve("rst-on-lisp/frames");
        Files.createDirectories(frames.resolve("explanation"));
        Files.createDirectories(frames.resolve("subject/a"));
        Files.writeString(frames.resolve("subject/a/design.lisp"), """
                ;;; made-from: doc_X/docs/A/a1 / ch23
                (defrule design -> p-what)
                (defrule p-what -> :instruction "say what the system is")
                """);
        Path request = frames.resolve("explanation/explain.lisp");
        Files.writeString(request, """
                ;;; integrates: subject/a/design.lisp (A, 23 Design)
                ;;; roles (role: design):
                ;;;   p-what:  p-system-what
                (defrule document -> p-what p-other)
                (defrule p-what -> :nucleus p-what/n)
                (defrule p-what/n -> :instruction "Say what the program is" :sample "Squares prints squares.")
                (defrule p-other -> :instruction "Say anything else")
                """);
        Path book = dir.resolve("doc_X/docs/A/a1/a1.lisp");
        Files.createDirectories(book.getParent());
        Files.writeString(book, "(defrule ch23 -> p-system-what)\n(defrule p-system-what -> \"FileBuy is a file-buying system.\")\n");

        String outcome = run(request, frames.resolve("explanation"), dir.toString());

        assertEquals(dir.resolve("filled.lisp").toString(), outcome, "questions so far: " + writer.questions);
        assertEquals(2, writer.questions.size(), writer.questions.toString());
        assertTrue(writer.questions.get(0).contains("- [a1.lisp / p-system-what] FileBuy is a file-buying system."), writer.questions.get(0));
        assertTrue(writer.questions.get(0).contains("Model sentence (from a different document; do not copy it, write this item's own content in the same role): Squares prints squares."),
                "the :sample stays: " + writer.questions.get(0));
        assertFalse(writer.questions.get(1).contains("Paragraphs of other books"), "a blank with no part gets none: " + writer.questions.get(1));
    }

    @Test
    @DisplayName("書き手が項目を 1 つも挙げなかったら、途中の規則ファイルを完成として書かずに、失敗を報告する")
    void aListWithNoItemFailsTheRunInsteadOfInstallingAHalfFilledFile() throws Exception {
        writer.listReply = "。";

        String outcome = run();

        assertTrue(outcome.startsWith("(plan"), outcome);
        assertFalse(Files.exists(dir.resolve("filled.lisp")), "a half-filled file was installed as finished");
    }
}
