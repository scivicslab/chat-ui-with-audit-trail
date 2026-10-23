package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
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
 * {@code polish-files.yaml} run end to end on a plan runner, with a fixer that changes nothing and a judge that
 * answers the same complaint every time: every criterion ends as a disagreement, the last check finds nothing brought
 * in, and the file is written with its front matter put back. The texts actor of plugin-text, registered as the instance registers it, does the comparing.
 */
@DisplayName("polish-files: 直す役と判定役が合わないとき、観点ごとに報告して元の本文を書く")
class PolishFilesWorkflowTest {

    private static final String DOC = """
            ---
            id: Sample_261023_oo01
            title: "Sample"
            ---

            # Sample

            `ChatSession` listens on port 8080.
            """;

    /** Answers with one fixed reply; remembers every question. */
    private static final class Fake implements LlmProvider {
        final List<String> questions = new ArrayList<>();
        final String reply;

        Fake(String reply) {
            this.reply = reply;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            questions.add(prompt);
            emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @TempDir Path dir;
    private IIActorSystem system;
    private Fake fixer;
    private Fake judge;
    private PlanRunnerIIAR plan;

    private ActorRef<CallWatchdog> chat(String id, Fake model) {
        ChatSessionIIAR chat = new ChatSessionIIAR("project1/chat-" + id + ".chat", model, Optional.empty(), null, system);
        system.addIIActor(chat);
        ActorRef<LlmProvider> providerRef = chat.<LlmProvider>createChild(chat.getName() + ".provider", model);
        chat.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", id);
        }).join();
        ActorRef<PromptQueue> queue = chat.createChild("project1/chat-" + id + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        return chat.createChild("watchdog-" + id, new CallWatchdog());
    }

    @BeforeEach
    void setUp() throws Exception {
        system = new IIActorSystem("polish-files-test");
        fixer = new Fake("直しました。");
        judge = new Fake("REVISE: 同じ指摘です。");
        ActorRef<CallWatchdog> watchdog = chat("02", fixer);
        chat("03", judge);
        PlanRunner runner = new PlanRunner("plan", system);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
        system.addIIActor(new WorkflowPluginLoader("loader", system, List.of()));
        system.addIIActor(new ChatsIIAR("chats", new Chats(system, watchdog, null), system));
        system.addIIActor(new com.scivicslab.turingworkflow.plugins.text.TextsIIAR("texts", system));
        system.addIIActor(new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system));
        Files.createDirectories(dir.resolve("in"));
        Files.writeString(dir.resolve("in/sample.md"), DOC);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("どの観点も合わず、元の本文がフロントマター付きで書かれ、texts アクターはインスタンスのものとして残る")
    void everyCriterionDisagreesAndTheOriginalIsWritten() throws Exception {
        for (String[] p : new String[][] {
                {"dir", dir.resolve("in").toString()}, {"suffix", ".md"}, {"depth", "1"}, {"outDir", dir.resolve("out").toString()},
                {"fixer", "project1/chat-02"}, {"judge", "project1/chat-03"}, {"tries", "5"},
                {"fixerProvider", ""}, {"fixerModel", ""}, {"judgeProvider", ""}, {"judgeModel", ""},
                {"fixerNoThink", ""}, {"judgeNoThink", ""}, {"fixerEffort", ""}, {"judgeEffort", ""}}) {
            plan.callByActionName("putJson", "{\"path\":\"" + p[0] + "\",\"value\":\"" + p[1].replace("\\", "\\\\") + "\"}");
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        byte[] bytes = getClass().getResourceAsStream("/workflows/polish-files.yaml").readAllBytes();
        plan.tellNow(interp -> {
            PlanRunner r = (PlanRunner) interp;
            r.setDone(done);
            r.reset();
            r.readYaml(new ByteArrayInputStream(bytes));
            r.runUntilEnd();
            r.complete("(plan ended without finish)");
        }).join();
        String report = done.get(120, TimeUnit.SECONDS);

        assertTrue(report.startsWith("=== 文章のブラッシュアップ ==="), report);
        assertTrue(report.contains("written: " + dir.resolve("out/sample.md")), report);
        for (int i = 1; i <= 7; i++) assertTrue(report.contains("観点" + i + " "), "criterion " + i + " is reported: " + report);
        assertEquals(DOC, Files.readString(dir.resolve("out/sample.md")));
        assertTrue(judge.questions.size() >= 14, "each criterion is judged twice before the two are found not to agree");
        assertTrue(system.getIIActor("texts") != null, "the texts actor is the instance's, not the workflow's");
    }
}
