package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.turingworkflow.plugins.rstframe.BookStore;
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
 * {@code book-toc.yaml} then {@code book-sections.yaml} run end to end on a plan runner
 * ({@code FrameOntology_261021_oo01}): the books actor of plugin-rst-frame, created and removed by each workflow,
 * puts the table of contents and the sections in the H2 database. No LLM.
 */
@DisplayName("book-toc / book-sections: 目次を取り、本文を節に分けて H2 に入れる")
class BookStructureWorkflowTest {

    private static final String P1 = """
            ---
            title: "x"
            ---
            ## Chapter 4 ▪ Objects and Classes

            In this chapter.

            ## 4.1. Introduction

            ## 4.1.1. Classes

            A class is a template.
            """;
    private static final String P2 = """
            It runs on into the next page file.

            ## 4.2. Using Predefined Classes

            Text of 4.2.
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
            String reply = "?";
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
        system = new IIActorSystem("book-structure-test");
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
        // the loader every instance has: the workflows create the books actor with it and remove it at the end
        system.addIIActor(new WorkflowPluginLoader("loader", system, List.of()));
        // the files actor every instance has: the books actor reads the book and opens the database through it
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));

        Files.createDirectories(dir.resolve("book/Chap04/p001"));
        Files.createDirectories(dir.resolve("book/Chap04/p006"));
        Files.writeString(dir.resolve("book/Chap04/p001/p001.md"), P1);
        Files.writeString(dir.resolve("book/Chap04/p006/p006.md"), P2);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String run(String workflow) throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("book").toString()}, {"name", "core"}, {"db", dir.resolve("db/books").toString()}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        byte[] bytes = getClass().getResourceAsStream("/workflows/" + workflow).readAllBytes();
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
    @DisplayName("目次を取ってから、本文をページファイルをまたいで節に分ける")
    void theTableOfContentsThenTheSectionsLandInTheDatabase() throws Exception {
        String toc = run("book-toc.yaml");
        assertTrue(toc.contains("{chapter=1, section=2, subsection=1}"), toc);
        assertNull(system.getIIActor("books"), "the books actor is removed at the end");
        String sections = run("book-sections.yaml");
        assertTrue(sections.startsWith("4 section(s) of core; 1 with no text of their own; 1 spanning more than one file"), sections);
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            assertEquals(4, s.outline("core").size());
            var by = new java.util.LinkedHashMap<String, String>();
            for (var x : s.sections("core")) by.put(x.number(), x.text());
            assertEquals("A class is a template.\n\nIt runs on into the next page file.", by.get("4.1.1"));
            assertEquals("Text of 4.2.", by.get("4.2"));
        }
    }

    @Test
    @DisplayName("目次の無い本の節分けは失敗として終わる")
    void sectionsWithoutATableOfContentsFail() throws Exception {
        String out = run("book-sections.yaml");
        assertFalse(out.contains("section(s)"), out);
        assertNull(system.getIIActor("books"), "the books actor is removed on the failure path too");
    }
}
