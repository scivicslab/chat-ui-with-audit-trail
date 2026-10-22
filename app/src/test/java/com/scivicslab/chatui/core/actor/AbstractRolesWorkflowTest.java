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
 * {@code abstract-roles.yaml} run end to end on a plan runner, with a writer conversation whose
 * model is a fake that answers by what it is asked ({@code FrameMakingWorkflow_261022_oo01}, step 3).
 */
@DisplayName("abstract-roles: 段落を概念のクラスのスロットに割り当てる")
class AbstractRolesWorkflowTest {

    private static final String RULES = """
            (defrule document -> title sections)
            (defrule title -> "How the loop works")
            (defrule sections -> s-result s-control)
            (defrule s-result -> p-result :heading "## Handle the result")
            (defrule p-result -> :nucleus p-result/n)
            (defrule p-result/n -> "The ResultMessage marks the end of the loop; its subtype tells how it ended.")
            (defrule s-control -> p-effort :heading "## Control how the loop runs")
            (defrule p-effort -> :nucleus p-effort/n)
            (defrule p-effort/n -> "The effort level sets how much the model reasons: low, medium or high.")
            """;
    private static final String TREE = """
            (in-package :rst)
            (defsubjects loop-doc :from "x")
            (subject "1" "ResultMessage" :paragraphs "p-result")
            (subject "2" "effort level" :relation setting-of :paragraphs "p-effort")
            """;
    private static final String CLASSES = """
            (defpackage :concepts (:use :cl))
            (in-package :concepts)
            (defclass message ()
              ((carried-values :documentation "What values it carries"))
              (:documentation "Something the agent loop hands to the program"))
            (defclass result-message (message)
              ((how-it-ended :documentation "How the loop ended"))
              (:documentation "The message that marks the end of the loop"))
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
            String reply = prompt.contains("The ResultMessage marks the end") ? "how-it-ended"
                    : prompt.contains("Is effort level a kind of one of these?") ? "NEW: loop option: A value that changes how the loop runs"
                    : prompt.contains("answers a question not asked of it before") ? "loop-option"
                    : prompt.contains("The effort level sets") ? "NEW: values: Which values does it take, and what does each do"
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
        system = new IIActorSystem("abstract-roles-test");
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

        Path paper = dir.resolve("loop");
        Files.createDirectories(paper);
        Files.writeString(paper.resolve("loop.md"), "# How the loop works\n");
        Files.writeString(paper.resolve("loop.lisp"), RULES);
        Files.writeString(dir.resolve("tree.lisp"), TREE);
        Files.createDirectories(dir.resolve("concepts"));
        Files.writeString(dir.resolve("concepts/messages.lisp"), CLASSES);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("loop").toString()}, {"name", "loop-doc"}, {"tree", dir.resolve("tree.lisp").toString()},
                {"classes", dir.resolve("concepts").toString()}, {"out", dir.resolve("roles/loop-doc.lisp").toString()}, {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"}, {"maxQuestions", "50"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/abstract-roles.yaml");
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
    @DisplayName("名前の一致するクラスの段落はそのスロットへ、クラスの無い概念は一般の種類を作り、新しい問いはそこへ足す")
    void paragraphsGoToInheritedSlotsAndNewOnesAreProposed() throws Exception {
        String outcome = run();

        assertEquals(dir.resolve("roles/loop-doc.lisp").toString(), outcome, "questions so far: " + writer.questions);
        assertEquals(4, writer.questions.size(), "1 slot for ResultMessage, then where, slot and how far up for effort level: " + writer.questions);
        String out = Files.readString(dir.resolve("roles/loop-doc.lisp"));
        assertTrue(out.contains("(defclass loop-option ()\n  ((values :documentation \"Which values does it take, and what does each do\"))"), out);
        assertTrue(out.contains("(defclass effort-level (loop-option) ()"), out);
        assertTrue(out.contains("(assign \"ResultMessage\" result-message\n  (how-it-ended \"p-result\"))"), out);
        assertTrue(out.contains("(assign \"effort level\" effort-level\n  (values \"p-effort\"))"), out);
        for (String q : writer.questions) assertFalse(q.contains("Available tools"), "no tool instructions in front of a question");
    }
}
