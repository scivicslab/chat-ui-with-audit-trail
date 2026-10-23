package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code parallel-workers-plan.yaml} on a plan runner: {@code chats.askAll} asks two conversations at the same time
 * and the job ends with both replies under their names.
 */
@DisplayName("parallel-workers-plan: 2 つの会話に同時に聞き、答えを名前順にまとめる")
class ParallelWorkersPlanTest {

    /** Holds its answer until the other conversation has been asked too, so the two run only at the same time. */
    private static final class Fake implements LlmProvider {
        final String reply;
        final CountDownLatch both;

        Fake(String reply, CountDownLatch both) {
            this.reply = reply;
            this.both = both;
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            both.countDown();
            try {
                both.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            emitter.accept(ChatEvent.delta(reply));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private IIActorSystem system;
    private PlanRunnerIIAR plan;

    private ActorRef<CallWatchdog> chat(String id, Fake model) {
        ChatSessionIIAR c = new ChatSessionIIAR("project1/chat-" + id + ".chat", model, Optional.empty(), null, system);
        system.addIIActor(c);
        ActorRef<LlmProvider> providerRef = c.<LlmProvider>createChild(c.getName() + ".provider", model);
        c.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", id);
        }).join();
        ActorRef<PromptQueue> queue = c.createChild("project1/chat-" + id + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        return c.createChild("watchdog-" + id, new CallWatchdog());
    }

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("parallel-workers-test");
        CountDownLatch both = new CountDownLatch(2);
        ActorRef<CallWatchdog> watchdog = chat("02", new Fake("Actors share nothing.", both));
        chat("03", new Fake("Locks guard what is shared.", both));
        system.addIIActor(new ChatsIIAR("chats", new Chats(system, watchdog, null), system));
        PlanRunner runner = new PlanRunner("plan", system);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("2 つの会話が同時に答え、ジョブは両方の答えを会話の名前の下に返す")
    void bothConversationsAreAskedAtOnceAndTheirRepliesComeBackUnderTheirNames() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<String> done = new CompletableFuture<>();
        byte[] bytes = getClass().getResourceAsStream("/workflows/parallel-workers-plan.yaml").readAllBytes();
        plan.tellNow(interp -> {
            PlanRunner r = (PlanRunner) interp;
            r.setDone(done);
            r.reset();
            r.readYaml(new ByteArrayInputStream(bytes));
            r.runUntilEnd();
            r.complete("(plan ended without finish)");
        }).join();
        String out = done.get(60, TimeUnit.SECONDS);
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        org.junit.jupiter.api.Assertions.assertTrue(seconds < 10,
                "each conversation holds its answer until the other is asked: asked one after the other, this takes 20 s, here " + seconds);
        assertEquals("## project1/chat-02\nActors share nothing.\n\n## project1/chat-03\nLocks guard what is shared.", out);
    }
}
