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
 * {@code book-to-rules.yaml} run end to end on a plan runner, with a writer conversation whose
 * model is a fake that answers by what it is asked ({@code FrameMakingWorkflow_261022_oo01}, step 1).
 */
@DisplayName("book-to-rules: 本の markdown を 1 本ずつ規則ファイルにする")
class BookToRulesWorkflowTest {

    private static final String PAGE_1 = """
            ## 3.1. A Simple Program

            The program declares a method. The method is called main.
            """;
    private static final String PAGE_2 = """
            ## 3.2. Comments

            Comments do not show up in the program.
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
            String reply;
            if (prompt.contains("misread")) {
                reply = prompt.substring(prompt.indexOf("The paragraph:\n") + 15, prompt.indexOf("\n\nParagraph after:"));
            } else {
                long n = prompt.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
                StringBuilder sb = new StringBuilder("1: nucleus\n");
                for (int i = 2; i <= n; i++) sb.append(i).append(": elaboration-additional\n");
                reply = sb.toString();
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
        system = new IIActorSystem("book-to-rules-test");
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

        Path book = dir.resolve("book");
        Files.createDirectories(book.resolve("p001"));
        Files.createDirectories(book.resolve("p002"));
        Files.writeString(book.resolve("p001/p001.md"), PAGE_1);
        Files.writeString(book.resolve("p002/p002.md"), PAGE_2);
        // a file that already has its rule file is left as it is
        Files.createDirectories(book.resolve("p003"));
        Files.writeString(book.resolve("p003/p003.md"), "## 3.3. Kept\n\nThis is already done.\n");
        Files.writeString(book.resolve("p003/p003.lisp"), "(kept)\n");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run() throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("book").toString()}, {"writer", "project1/chat-02"}, {"writerModel", ""}, {"writerNoThink", "true"},
                {"maxQuestions", "50"}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        InputStream yaml = getClass().getResourceAsStream("/workflows/book-to-rules.yaml");
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
    @DisplayName("規則ファイルの無い markdown を順に 1 本ずつ変換し、あるものは飛ばす")
    void everyFileWithoutARuleFileGetsOne() throws Exception {
        String outcome = run();
        org.junit.jupiter.api.Assertions.assertNull(system.getIIActor("plan.bookFiles"), "the workflow removes the actor it made");

        assertEquals(4, writer.questions.size(), "2 files x 1 paragraph x (misreadings, relations): " + writer.questions);
        String p1 = Files.readString(dir.resolve("book/p001/p001.lisp"));
        assertTrue(p1.contains("(defrule b1 -> :nucleus b1/1 :elaboration-additional b1/2)"), p1);
        assertTrue(p1.contains(":heading \"## 3.1. A Simple Program\""), p1);
        assertTrue(Files.readString(dir.resolve("book/p002/p002.lisp")).contains("Comments do not show up in the program."));
        assertEquals("(kept)\n", Files.readString(dir.resolve("book/p003/p003.lisp")));
        assertEquals(dir.resolve("book/p002/p002.lisp").toRealPath().toString(), outcome);
    }
}
