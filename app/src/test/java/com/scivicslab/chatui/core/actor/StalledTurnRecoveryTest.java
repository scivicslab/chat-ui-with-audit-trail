package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stalled-turn recovery ({@code tasks/stalled-turn-recovery.md}) against a provider whose call never answers, as the
 * Claude Code CLI's did on 2026-11-23 after the usage limit: no output at all, until it is cancelled or killed.
 *
 * <p>Before the fix there was no recovery, only the screen's Cancel: one cancel ended the stalled turn, the queue
 * started the next request about 2 ms later, and the request the cancelled turn was working on was gone. Pressed
 * again, each cancel stopped a turn that had just started and dropped its request too.</p>
 */
class StalledTurnRecoveryTest {

    private static final long THRESHOLD = 10 * 60_000;

    /**
     * Answers each prompt with "done: " and its last line, except while {@link #stall} is set: then the call
     * produces nothing and waits. What ends a waiting call is set per test: a cancel, a kill, or nothing.
     */
    static final class StallingProvider implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        volatile boolean stall = true;
        volatile boolean cancelEndsTheCall = true;
        volatile boolean killEndsTheCall = true;
        volatile boolean speaksBeforeStalling = false;
        /** Refuses each call as Claude Code does at the usage limit: the usage_limit event, the error, the end. */
        volatile boolean refusesForTheLimit = false;
        private volatile Semaphore gate = new Semaphore(0);
        final List<Semaphore> gates = new CopyOnWriteArrayList<>();

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() { if (cancelEndsTheCall) gate.release(); }
        /** The kill a recovery uses when a cancel does not end the turn. */
        public void forceStop() { if (killEndsTheCall) gate.release(); }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (refusesForTheLimit) {
                // Made with the record's constructor, the way ChatEvent.usageLimit makes it.
                emitter.accept(new ChatEvent("usage_limit", "You have hit your limit", null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null));
                emitter.accept(ChatEvent.error("You have hit your limit"));
                emitter.accept(ChatEvent.result(null, 0, 0));
                return;
            }
            if (stall) {
                Semaphore mine = new Semaphore(0);
                gate = mine;
                gates.add(mine);
                if (speaksBeforeStalling) {
                    ctx.onActivity().run();
                    emitter.accept(ChatEvent.thinking("thinking..."));
                }
                try { mine.tryAcquire(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return;
            }
            String[] lines = prompt.strip().split("\n");
            emitter.accept(ChatEvent.delta("done: " + lines[lines.length - 1].strip()));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }

        void releaseAll() { for (Semaphore s : gates) s.release(10); }

        long count(String request) { return prompts.stream().filter(p -> p.contains(request)).count(); }

        /** The requests the calls were for, in order: "A", "B", "C". */
        List<String> order() {
            List<String> out = new ArrayList<>();
            for (String p : prompts) {
                for (String r : List.of("request A", "request B", "request C")) if (p.contains(r)) out.add(r.substring(8));
            }
            return out;
        }
    }

    private final List<IIActorSystem> systems = new ArrayList<>();
    private StallingProvider provider;
    private ActorRef<PromptQueue> queue;
    private ActorRef<ChatSession> chat;
    private Map<String, ChatSessionIIAR> sessions;
    private final AtomicLong advance = new AtomicLong();

    @AfterEach
    void releaseStuckCalls() {
        if (provider != null) provider.releaseAll();
    }

    /** Conversation project1/chat-57 on {@link #provider}, with its queue. */
    private void conversation(StallingProvider p) {
        provider = p;
        IIActorSystem system = new IIActorSystem("stalled-turn-recovery-test-" + systems.size());
        systems.add(system);
        ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-57.chat", p, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", p);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        queue = iiar.createChild(iiar.getName() + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        chat = iiar.asChatSessionRef();
        sessions = Map.of("project1/chat-57", iiar);
    }

    private StalledTurnRecovery recovery(long autoRecoverMillis, long stopWaitMillis) {
        return new StalledTurnRecovery(() -> sessions, () -> System.currentTimeMillis() + advance.get(),
                THRESHOLD, autoRecoverMillis, stopWaitMillis);
    }

    /** Queues A, B and C, each with its own key so its answer reaches its done. */
    private List<CompletableFuture<TurnReply>> queueABC() {
        List<CompletableFuture<TurnReply>> dones = new ArrayList<>();
        for (String r : List.of("A", "B", "C")) {
            CompletableFuture<TurnReply> done = new CompletableFuture<>();
            dones.add(done);
            queue.tellNow(q -> q.enqueue("request " + r, null, "queue", e -> { }, chat, "human", "key-" + r,
                    done, false, true, List.of(), null, false)).join();
        }
        return dones;
    }

    private static void waitFor(BooleanSupplier condition, java.util.function.Supplier<String> what) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), what.get());
    }

    private List<String> waiting() {
        return queue.askNow(PromptQueue::snapshot).join().stream().map(PromptQueue.QueueEntry::prompt).toList();
    }

    private static String answerOf(CompletableFuture<TurnReply> done) throws Exception {
        return done.get(10, TimeUnit.SECONDS).reply();
    }

    @Test
    void detection_aCallSilentForTheThreshold_isStalled_andOneSilentForLess_isNot() throws Exception {
        conversation(new StallingProvider());
        StalledTurnRecovery recovery = recovery(0, 5_000);
        queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        advance.set(THRESHOLD - 2_000);
        assertEquals(List.of(), recovery.stalled(), "2 s before the threshold it is not stalled");

        advance.set(THRESHOLD);
        List<StalledTurnRecovery.Stalled> stalled = recovery.stalled();
        assertEquals(1, stalled.size(), "at the threshold it is stalled: " + stalled);
        assertEquals("project1", stalled.get(0).projectId());
        assertEquals("silent", stalled.get(0).reason());
        assertEquals("57", stalled.get(0).chatId());
        assertEquals(2, stalled.get(0).queued(), "B and C wait");
    }

    @Test
    void detection_aCallThatProducedAnything_isNotStalled_howeverLongItTakes() throws Exception {
        StallingProvider p = new StallingProvider();
        p.speaksBeforeStalling = true;
        conversation(p);
        StalledTurnRecovery recovery = recovery(0, 5_000);
        queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);
        Thread.sleep(200);

        advance.set(THRESHOLD * 10);
        assertEquals(List.of(), recovery.stalled(), "a call that thinks is alive, not stalled");
    }

    @Test
    void oneRecovery_bringsTheConversationBack_theStalledRequestRunsAgainFirst_andNothingIsLost() throws Exception {
        conversation(new StallingProvider());
        StalledTurnRecovery recovery = recovery(0, 5_000);
        List<CompletableFuture<TurnReply>> dones = queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        provider.stall = false;
        StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "57");

        assertEquals("recovered", outcome.outcome(), outcome.toString());
        assertEquals("cancel", outcome.how(), outcome.toString());
        waitFor(() -> provider.prompts.size() == 4, () -> "A again, then B and C: " + provider.order());
        assertEquals(List.of("A", "A", "B", "C"), provider.order(), "A runs again first, then B and C in order");
        assertEquals("done: request A", answerOf(dones.get(0)), "A's asker gets A's answer, not a cancellation");
        assertEquals("done: request B", answerOf(dones.get(1)));
        assertEquals("done: request C", answerOf(dones.get(2)));
        assertEquals(List.of(), waiting());
    }

    /**
     * What the person did on 2026-11-23, with recovery in place of Cancel: called again and again while the
     * request kept stalling. Every call puts the same request back at the front; B and C never start in between,
     * never move, and are never dropped; once the calls answer again, A, B and C are all answered in order.
     */
    @Test
    void recoveryCalledThreeTimesInARow_losesNoRequest_keepsTheOrder_andStartsNothingElseInBetween() throws Exception {
        conversation(new StallingProvider());
        StalledTurnRecovery recovery = recovery(0, 5_000);
        List<CompletableFuture<TurnReply>> dones = queueABC();

        for (int i = 1; i <= 3; i++) {
            int calls = i;
            waitFor(() -> provider.prompts.size() == calls, () -> "call " + calls + " is A's: " + provider.order());
            assertEquals(java.util.Collections.nCopies(calls, "A"), provider.order(),
                    "only A has run; B did not start in between: " + provider.order());
            List<String> before = waiting();
            assertEquals(List.of("request B", "request C"), before, "B and C wait, in order, before recovery " + i);
            if (i == 3) provider.stall = false;
            StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "57");
            assertEquals("recovered", outcome.outcome(), "recovery " + i + ": " + outcome);
        }

        waitFor(() -> provider.prompts.size() == 6, () -> "A, then B and C: " + provider.order());
        assertEquals(List.of("A", "A", "A", "A", "B", "C"), provider.order());
        assertEquals("done: request A", answerOf(dones.get(0)), "A was not dropped");
        assertEquals("done: request B", answerOf(dones.get(1)), "B was not dropped");
        assertEquals("done: request C", answerOf(dones.get(2)), "C was not dropped");
        assertEquals(List.of(), waiting());
    }

    @Test
    void aCancelThatDoesNotEndTheTurn_isFollowedByAKill_andTheRequestRunsAgain() throws Exception {
        StallingProvider p = new StallingProvider();
        p.cancelEndsTheCall = false;
        conversation(p);
        StalledTurnRecovery recovery = recovery(0, 300);
        List<CompletableFuture<TurnReply>> dones = queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        provider.stall = false;
        StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "57");

        assertEquals("recovered", outcome.outcome(), outcome.toString());
        assertEquals("kill", outcome.how(), outcome.toString());
        assertEquals("done: request A", answerOf(dones.get(0)));
        waitFor(() -> provider.prompts.size() == 4, () -> provider.order().toString());
        assertEquals(List.of("A", "A", "B", "C"), provider.order());
    }

    @Test
    void aCallThatNeitherTheCancelNorTheKillEnds_isAbandoned_andTheRequestRunsAgain() throws Exception {
        StallingProvider p = new StallingProvider();
        p.cancelEndsTheCall = false;
        p.killEndsTheCall = false;
        conversation(p);
        // The step looks at the abandon flag once a second, so the wait after the kill is longer than that.
        StalledTurnRecovery recovery = recovery(0, 3_000);
        List<CompletableFuture<TurnReply>> dones = queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        provider.stall = false;
        StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "57");

        assertEquals("recovered", outcome.outcome(), outcome.toString());
        assertEquals("kill", outcome.how(), outcome.toString());
        assertEquals("done: request A", answerOf(dones.get(0)));
        waitFor(() -> provider.prompts.size() == 4, () -> provider.order().toString());
        assertEquals(List.of("A", "A", "B", "C"), provider.order());
    }

    @Test
    void automaticRecovery_isOffByDefault_andDoesNothingHoweverLongTheTurnIsStalled() throws Exception {
        conversation(new StallingProvider());
        StalledTurnRecovery recovery = recovery(0, 5_000);
        queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        advance.set(THRESHOLD * 100);
        assertEquals(List.of(), recovery.tick(), "auto-recover-minutes 0: nothing is recovered");
        Thread.sleep(300);
        assertEquals(1, provider.prompts.size(), "the stalled call is left as it is: " + provider.order());
        assertEquals(List.of("request B", "request C"), waiting());
    }

    @Test
    void automaticRecovery_recoversATurnStalledThatLongPastTheThreshold_andNotSooner() throws Exception {
        conversation(new StallingProvider());
        long auto = 5 * 60_000;
        StalledTurnRecovery recovery = recovery(auto, 5_000);
        List<CompletableFuture<TurnReply>> dones = queueABC();
        waitFor(() -> provider.prompts.size() == 1, () -> "A started: " + provider.prompts);

        advance.set(THRESHOLD + auto - 2_000);
        assertEquals(List.of(), recovery.tick(), "2 s before threshold + 5 min: not yet");
        assertEquals(1, provider.prompts.size());

        provider.stall = false;
        advance.set(THRESHOLD + auto);
        List<StalledTurnRecovery.Outcome> outcomes = recovery.tick();
        assertEquals(1, outcomes.size(), outcomes.toString());
        assertEquals("recovered", outcomes.get(0).outcome());
        assertEquals("done: request A", answerOf(dones.get(0)));
        waitFor(() -> provider.prompts.size() == 4, () -> provider.order().toString());
        assertEquals(List.of("A", "A", "B", "C"), provider.order());
    }

    @Test
    void recoveringAConversationWithNoTurnRunning_changesNothing() throws Exception {
        StallingProvider p = new StallingProvider();
        p.stall = false;
        conversation(p);
        StalledTurnRecovery recovery = recovery(0, 5_000);

        StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "chat-57");

        assertEquals("not-running", outcome.outcome());
        assertEquals("none", outcome.how());
        assertEquals("not-found", recovery.recover("project1", "99").outcome());
        List<CompletableFuture<TurnReply>> dones = queueABC();
        assertEquals("done: request C", answerOf(dones.get(2)), "the queue was released and runs");
    }

    /**
     * With the failed result ending the turn, a usage-limit refusal ends it at once, as an error. Were the request
     * failed and the next one started, every request in the queue would be failed in turn within seconds while the
     * limit lasts. The request goes back to the front instead, the queue is held, no asker is told anything yet, and
     * the conversation is listed as held for the usage limit.
     */
    @Test
    void aUsageLimitRefusal_keepsTheRequest_holdsTheQueue_andFailsNoWaitingRequest() throws Exception {
        StallingProvider p = new StallingProvider();
        p.refusesForTheLimit = true;
        conversation(p);
        StalledTurnRecovery recovery = recovery(0, 5_000);
        List<CompletableFuture<TurnReply>> dones = queueABC();
        waitFor(() -> provider.prompts.size() >= 1, () -> "A was tried: " + provider.prompts);
        Thread.sleep(1_000);

        assertEquals(List.of("A"), provider.order(), "only A was tried; B and C were not failed after it");
        waitFor(() -> waiting().size() == 3, () -> "A is back at the front: " + waiting());
        assertEquals(List.of("request A", "request B", "request C"), waiting());
        for (CompletableFuture<TurnReply> done : dones) assertTrue(!done.isDone(), "no asker has been answered yet");
        List<StalledTurnRecovery.Stalled> stalled = recovery.stalled();
        assertEquals(1, stalled.size(), stalled.toString());
        assertEquals("usage-limit", stalled.get(0).reason());
        assertEquals(3, stalled.get(0).queued());

        provider.refusesForTheLimit = false;
        provider.stall = false;
        StalledTurnRecovery.Outcome outcome = recovery.recover("project1", "57");
        assertEquals("recovered", outcome.outcome(), outcome.toString());
        assertEquals("release", outcome.how(), outcome.toString());
        assertEquals("done: request A", answerOf(dones.get(0)));
        assertEquals("done: request B", answerOf(dones.get(1)));
        assertEquals("done: request C", answerOf(dones.get(2)));
        assertEquals(List.of("A", "A", "B", "C"), provider.order());
        assertEquals(List.of(), recovery.stalled());
    }

    @Test
    void aQueueHeldForTheUsageLimit_isReleasedAutomatically_afterTheAutoRecoverTime_andHeldAgainIfStillRefused()
            throws Exception {
        StallingProvider p = new StallingProvider();
        p.refusesForTheLimit = true;
        conversation(p);
        long auto = 5 * 60_000;
        StalledTurnRecovery recovery = recovery(auto, 5_000);
        queueABC();
        waitFor(() -> waiting().size() == 3 && provider.prompts.size() == 1, () -> "held: " + waiting());

        advance.set(auto - 2_000);
        assertEquals(List.of(), recovery.tick(), "not yet");
        advance.set(auto);
        List<StalledTurnRecovery.Outcome> outcomes = recovery.tick();
        assertEquals(1, outcomes.size(), outcomes.toString());
        assertEquals("release", outcomes.get(0).how());
        waitFor(() -> provider.prompts.size() == 2 && waiting().size() == 3,
                () -> "A tried again, refused, held again: " + provider.order() + " " + waiting());
        assertEquals(List.of("A", "A"), provider.order(), "B and C were still not failed");
    }
}
