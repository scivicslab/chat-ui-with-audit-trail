package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.turingworkflow.plugins.rstframe.BookStore;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The step workflows on a plan runner: {@code book-judge.yaml} (step 5, no model) and {@code book-review.yaml}
 * (step 7, against a local endpoint that answers as the GPU broker does), after the table of contents and the sections.
 */
@DisplayName("book-judge / book-review / book-frame-collect / book-frame-prune: 段5・段7と具象フレームの材料をワークフローで回す")
class BookStepsWorkflowTest {

    @TempDir Path dir;
    private IIActorSystem system;
    private PlanRunnerIIAR plan;
    private HttpServer endpoint;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        system = new IIActorSystem("book-steps-test");
        PlanRunner runner = new PlanRunner("plan", system);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
        system.addIIActor(new WorkflowPluginLoader("loader", system, List.of()));
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));

        // answers every question with the review of two relations, as an OpenAI-compatible endpoint
        endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (asked) {
                asked.add(new JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content"));
            }
            String question = new JSONObject(body).getJSONArray("messages").getJSONObject(0).getString("content");
            String answer = question.contains("behavior   what the concept does")
                    ? "concurrent programming | property | 1\nconcurrent programming | behavior | 2\nmultithreading | property | 1"
                    : question.contains("Name the questions about")
                    ? "Q1: What is concurrent programming?\nQ2: Nothing answers this\np1/1 | Q1\np1/2 | Q1"
                    : question.contains("First name the section's main concept C")
                    ? "concept: concurrent programming\np1 | kind | threads\np2 | kind | processes"
                    : "R1 | is-a\nR2 | drop";
            byte[] out = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                    .put("message", new JSONObject().put("role", "assistant").put("content", answer)))).toString()
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        endpoint.start();

        Path b = dir.resolve("book/Chap10/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 10 ▪ Concurrency\n\n## 10.1. Running Threads\n\n"
                + "## 10.1.1. Threads\n\nMultithreading is one way to program concurrently.\n");
    }

    @AfterEach
    void tearDown() {
        endpoint.stop(0);
        system.terminate();
    }

    private String run(String workflow) throws Exception {
        for (String[] p : new String[][] {
                {"book", dir.resolve("book").toString()}, {"name", "core"}, {"db", dir.resolve("db/books").toString()},
                {"url", "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/v1/chat/completions"}, {"model", "fake"},
                {"parallel", "2"}, {"sections", ""}, {"min", "2"}}) {
            plan.callByActionName("putJson", new JSONObject().put("path", p[0]).put("value", p[1]).toString());
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
    @DisplayName("段5は概念を判定し、段7は関係を見直して判定を H2 に残す")
    void step5JudgesAndStep7ReviewsWhatIsInTheDatabase() throws Exception {
        run("book-toc.yaml");
        run("book-sections.yaml");
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            s.saveSectionRules("core", new BookStore.SectionRules("10.1.1", """
                    (defrule s1 -> p1 :heading "## 10.1.1. Threads")
                    (defrule p1 -> :nucleus p1/1 :example p1/2)
                    (defrule p1/1 -> "Multithreading is one way to program concurrently.")
                    (defrule p1/2 -> "Java has threads.")
                    """, Map.of(), 2));
            s.saveListedConcepts("core", "10.1.1", List.of(new BookStore.ListedConcept("multithreading", List.of(1)),
                    new BookStore.ListedConcept("concurrent programming", List.of(1)), new BookStore.ListedConcept("Java", List.of(2))));
        }
        String judged = run("book-judge.yaml");
        assertTrue(judged.contains("judged: 2 concept(s) kept, 1 dropped"), judged);
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            s.saveRelations("core", "10.1.1", List.of(
                    new BookStore.Relation("part-of", "multithreading", "concurrent programming", List.of(1)),
                    new BookStore.Relation("feature", "Java", "concurrent programming", List.of(2))));
        }
        String reviewed = run("book-review.yaml");
        assertEquals("1 section(s) of core reviewed: {drop=1, is-a=1}, 0 failed", reviewed);
        assertTrue(asked.get(0).contains("R1: multithreading | part-of | concurrent programming"), asked.get(0));
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            List<BookStore.Relation> after = s.relations("core").get("10.1.1");
            assertEquals("is-a", after.get(0).reviewed().kind());
            assertNull(after.get(1).reviewed(), "Java feature of concurrent programming is dropped");
        }
        String collected = run("book-frame-collect.yaml");
        assertEquals("1 section(s) of core asked, 3 rule(s) collected, 0 failed", collected,
                "the list is the two concepts of the relation that stands; Java's relation was dropped");
        assertEquals("1 section(s) of core: 1 concept(s) stay, 1 taken off (fewer than 2 rule(s))", run("book-frame-prune.yaml"));
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            var list = s.frameConcepts("core").get("10.1.1");
            assertEquals(List.of("multithreading", "concurrent programming"), list.stream().map(BookStore.FrameConcept::concept).toList());
            assertEquals(List.of(false, true), list.stream().map(BookStore.FrameConcept::kept).toList());
        }
        assertEquals("1 concept(s) of core given 1 item(s), 0 failed", run("book-frame-items.yaml"),
                "only the concept that stays on the list is asked; a question no rule answers is no item");
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            var items = s.frameItems("core").get("10.1.1").get("concurrent programming");
            assertEquals("What is concurrent programming?", items.get(0).question());
        }
        assertEquals("1 concrete frame(s) of core placed: 0 into frames already made, 1 new; 1 generalized frame(s) in the registry, 0 failed; "
                + "0 relation(s) between generalized frames from core: {}; 0 generalized frame(s) named, 0 failed; 0 generalized frame(s) given 0 item(s), 0 failed", run("book-generalize.yaml"),
                "one concrete frame starts the registry with a new id; a frame of one concrete frame keeps its items");
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            assertEquals(List.of("F0001 concurrent programming"), s.registry().stream().map(f -> f.id() + " " + f.name()).toList());
            assertEquals("What is concurrent programming?", s.registryItems().get("F0001").get(0).question());
            // a second block, so the section's blocks have something to say of each other
            s.saveSectionRules("core", new BookStore.SectionRules("10.1.1", """
                    (defrule s1 -> p1 p2 :heading "## 10.1.1. Threads")
                    (defrule p1 -> :nucleus p1/1 :example p1/2)
                    (defrule p1/1 -> "Multithreading is one way to program concurrently.")
                    (defrule p1/2 -> "Java has threads.")
                    (defrule p2 -> "Running processes side by side is another.")
                    """, Map.of(), 2));
        }
        assertEquals("1 section(s) of core given a pattern: {partition=1}, 0 failed", run("book-patterns.yaml"));
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            var p = s.patterns("core").get("10.1.1");
            assertEquals("concurrent programming", p.concept(), "the concept the model names");
            assertEquals(List.of("p1", "p2"), p.members().stream().map(m -> m.block()).toList());
        }
        assertNull(system.getIIActor("plan.books"), "the workflow removes the actor it made");
    }
}
