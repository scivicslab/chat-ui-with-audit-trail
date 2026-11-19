package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step at the end of every turn that decides whether the timekeeper must remind the conversation, with the
 * model's answer to the question stubbed ({@code ReminderDecidedAtTheEndOfTheTurn_261114_oo01}).
 */
class ReminderStepTest {

    /** Says one scripted thing per prompt; optionally holds its first prompt, or fails on one. */
    private static final class Scripted implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<String> replies;
        final boolean hold;
        final int failOn;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Scripted(boolean hold, int failOn, String... replies) {
            this.hold = hold;
            this.failOn = failOn;
            this.replies = List.of(replies);
        }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (hold && started.getCount() > 0) {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            if (prompts.size() == failOn) throw new IllegalStateException("model unavailable");
            emitter.accept(ChatEvent.delta(replies.get(Math.min(prompts.size() - 1, replies.size() - 1))));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private record Squad(ChatSessionIIAR chat, ActorRef<PromptQueue> queue, ActorRef<Timekeeper> keeper) {}

    private static Squad squad(IIActorSystem system, String chatId, Scripted provider) {
        ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-" + chatId + ".chat", provider,
                Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
            c.setReminderDecisionEnabled(true);   // the suite turns the step off by default
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild("project1/chat-" + chatId + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<Timekeeper> keeper = iiar.createChild("project1/chat-" + chatId + ".timekeeper",
                new Timekeeper("project1/chat-" + chatId, iiar, queue, e -> { }, 600_000, 300_000));
        return new Squad(iiar, queue, keeper);
    }

    private static void personTypes(Squad squad, String text) {
        squad.queue().tellNow(q -> q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(),
                "human")).join();
    }

    private static String decisionOf(Squad squad) {
        return squad.chat().askNow(a -> ((ChatSession) a).lastReminderDecision()).join();
    }

    private static String waitForDecision(Squad squad) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        String d;
        while ((d = decisionOf(squad)) == null && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertNotNull(d, "the turn never reached its end-of-turn step");
        return d;
    }

    private static List<PromisedReports.Pending> pending(Squad squad) {
        return squad.keeper().askNow(Timekeeper::pendingPromises).join();
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("</invoke>").toString();
    }

    private static void assertDueInAbout(long minutes, long dueAt) {
        long ms = dueAt - System.currentTimeMillis();
        assertTrue(ms > (minutes * 60_000) - 30_000 && ms <= minutes * 60_000 + 1_000,
                "due in about " + minutes + " min, but in " + ms / 1000 + " s");
    }

    private static final String PROMISE = "The survey is running. I will report back when it is done.";

    @Test
    void aPromiseWithNoStatedTimeRegistersTheDefaultFiveMinutes() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-default");
        Scripted model = new Scripted(false, 0, PROMISE, "REGISTER DEFAULT | \"I will report back when it is done.\"");
        Squad one = squad(system, "01", model);
        personTypes(one, "run the survey");

        String decision = waitForDecision(one);
        assertTrue(decision.startsWith("REGISTERED 5 min"), decision);
        List<PromisedReports.Pending> p = pending(one);
        assertEquals(1, p.size());
        assertNull(p.get(0).requestId(), "a promise to the person is registered with no request, as expect_report on its own id is");
        assertNull(p.get(0).asker());
        assertDueInAbout(5, p.get(0).dueAt());
        assertEquals(2, model.prompts.size(), "one answer, one small decision call");
        assertTrue(model.prompts.get(1).contains("FINAL ANSWER:\n" + PROMISE), "the call shows the final answer");
    }

    @Test
    void aTimeTheAnswerStatesIsUsed() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-stated");
        Scripted model = new Scripted(false, 0, "I will check again in 20 minutes.",
                "REGISTER 20 | \"I will check again in 20 minutes.\"");
        Squad one = squad(system, "01", model);
        personTypes(one, "watch it");

        assertTrue(waitForDecision(one).startsWith("REGISTERED 20 min"));
        assertDueInAbout(20, pending(one).get(0).dueAt());
    }

    @Test
    void noneRegistersNothingAndIsLoggedWithItsReason() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-none");
        Scripted model = new Scripted(false, 0, "Tokyo is the capital of Japan.", "NONE | the answer is complete");
        Squad one = squad(system, "01", model);
        personTypes(one, "capital?");

        String decision = waitForDecision(one);
        assertEquals("NONE | the answer is complete", decision);
        assertEquals(List.of(), pending(one));
        assertEquals(2, model.prompts.size(), "NONE is still an explicit decision, asked for on every turn");
    }

    @Test
    void aPromiseTheAnswerDoesNotMakeIsNotRegistered() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-invented");
        Scripted model = new Scripted(false, 0, "Tokyo is the capital of Japan.", "REGISTER DEFAULT | \"I will phone you\"");
        Squad one = squad(system, "01", model);
        personTypes(one, "capital?");

        String decision = waitForDecision(one);
        assertTrue(decision.startsWith("NONE | not registered: the quoted words are not in the answer"), decision);
        assertEquals(List.of(), pending(one));
    }

    @Test
    void anUnreadableDecisionRegistersNothingAndSaysSo() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-unreadable");
        Scripted model = new Scripted(false, 0, PROMISE, "Hmm, maybe a reminder would be good.");
        Squad one = squad(system, "01", model);
        personTypes(one, "run the survey");

        assertTrue(waitForDecision(one).startsWith("UNREADABLE"));
        assertEquals(List.of(), pending(one));
    }

    /** The turn ends, and answers, even when the decision call fails. */
    @Test
    void aFailedDecisionCallDoesNotKeepTheTurnFromEnding() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-failing");
        Scripted asker = new Scripted(false, 0, "noted");
        Scripted worker = new Scripted(false, 2, "the answer", "unused");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);

        AskChatTool.send(system, "project1", "01", "02", "do it", "project1/chat-01:s1:t1:r1");
        String decision = waitForDecision(two);
        assertTrue(decision.startsWith("SKIPPED | the model call failed"), decision);
        long deadline = System.currentTimeMillis() + 10_000;
        while (asker.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertTrue(!asker.prompts.isEmpty() && asker.prompts.get(0).contains("[reply to request project1/chat-01:s1:t1:r1"),
                "the asker still got its reply");
    }

    /** A report promised to the conversation that asked is registered for that request, so its follow-up reaches the asker. */
    @Test
    void aPromiseToTheAskerIsRegisteredForTheRequest() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-asker");
        Scripted asker = new Scripted(false, 0, "noted");
        Scripted worker = new Scripted(false, 0, "Started. I will send the full report in 30 minutes.",
                "REGISTER 30 | \"I will send the full report in 30 minutes.\"");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);

        AskChatTool.send(system, "project1", "01", "02", "do it", "project1/chat-01:s1:t1:r1");
        assertTrue(waitForDecision(two).startsWith("REGISTERED 30 min"));
        List<PromisedReports.Pending> p = pending(two);
        assertEquals(1, p.size());
        assertEquals("project1/chat-01:s1:t1:r1", p.get(0).requestId());
        assertEquals("project1/chat-01", p.get(0).asker());
        assertDueInAbout(30, p.get(0).dueAt());
    }

    /** Requests sent for a request received are watched by the timekeeper already: no model call, and the log says so. */
    @Test
    void requestsStillOutAreCoveredByTheChildReplyWatchWithNoModelCall() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-covered");
        Scripted asker = new Scripted(false, 0, "noted");
        Scripted worker = new Scripted(false, 0, invoke("ask_chat", "chatId", "03", "prompt", "side job"),
                "Delegated. I will report when chat-03 answers.", "REGISTER DEFAULT | \"I will report when chat-03 answers.\"");
        Scripted busy = new Scripted(true, 0, "ok");
        squad(system, "01", asker);
        Squad two = squad(system, "02", worker);
        Squad three = squad(system, "03", busy);
        personTypes(three, "running");
        assertTrue(busy.started.await(10, TimeUnit.SECONDS));

        AskChatTool.send(system, "project1", "01", "02", "do the work", "project1/chat-01:s1:t1:r1");
        String decision = waitForDecision(two);
        assertTrue(decision.startsWith("COVERED | covered by child-reply watch"), decision);
        assertEquals(2, worker.prompts.size(), "the request and the tool result: no third call for the decision");
        assertEquals(List.of(), pending(two));
        busy.release.countDown();
    }

    /** The tool texts the models read say five minutes, and no longer tell a model to register a reminder for its own promise. */
    @Test
    void theToolDescriptionsSayFiveMinutesAndDoNotTellTheModelToRegisterItsOwnPromise() {
        Squad one = squad(new IIActorSystem("reminder-step-tool-texts"), "01", new Scripted(false, 0, "x"));
        String tools = one.chat().askNow(a -> ((ChatSession) a).toolInstructionsText()).join()
                .replaceAll("\\s+", " ");  // where a line wraps is not what this test is about
        assertTrue(tools.contains("without a reply before it is told (default 5)"), "set_chat_timekeeper says 5");
        assertTrue(!tools.contains("(default 30)"), "no 30 is left");
        assertTrue(tools.contains("- expect_report(chatId, requestId, minutes, node)"), "the tool itself stays");
        assertTrue(tools.contains("about 20 more minutes"), "for a reply that says so");
        assertTrue(!tools.contains("give your own id as \"chatId\""), "the self reminder is no longer the model's to register");
    }

    /**
     * A request sent from a turn the person's prompt started is watched too: it is counted as out, the timekeeper
     * tells the conversation once, the notice says what the target is doing, and the reply still starts a turn of its
     * own and takes the request off the list.
     */
    @Test
    void aRequestFromAPersonStartedTurnIsWatchedOncePerRequestAndItsReplyStillStartsATurn() throws Exception {
        IIActorSystem system = new IIActorSystem("reminder-step-person-started");
        Scripted liaison = new Scripted(false, 0, invoke("ask_chat", "chatId", "02", "prompt", "do the job"),
                "Handed it out.", "Nothing to do about the notice.", "thanks");
        Scripted worker = new Scripted(true, 0, "the job is done");
        Squad one = squad(system, "01", liaison);
        Squad two = squad(system, "02", worker);
        personTypes(two, "running");
        assertTrue(worker.started.await(10, TimeUnit.SECONDS));
        long now = System.currentTimeMillis();
        Timekeeper keeper = new Timekeeper("project1/chat-01", one.chat(), one.queue(), e -> { }, Long.MAX_VALUE, 0,
                target -> new Timekeeper.TargetState(true, true, now - 12 * 60_000L));

        personTypes(one, "have chat-02 do the job");
        String decision = waitForDecision(one);
        assertTrue(decision.startsWith("COVERED | covered by child-reply watch"), "the request is counted as out: " + decision);
        assertEquals(1, one.chat().outstandingChildrenDirect().size());
        assertEquals("project1/chat-02", one.chat().outstandingChildrenDirect().get(0).target());
        assertEquals(2, liaison.prompts.size(), "the covered turn made no decision call");

        keeper.tick(System.currentTimeMillis() + 1);
        keeper.tick(System.currentTimeMillis() + 2);
        long deadline = System.currentTimeMillis() + 15_000;
        while (liaison.prompts.size() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(25);
        Thread.sleep(300);
        long notices = liaison.prompts.stream().filter(x -> x.contains("[no reply yet to request project1/chat-01:")).count();
        assertEquals(1, notices, "once per request: " + liaison.prompts);
        String notice = liaison.prompts.stream().filter(x -> x.contains("[no reply yet to request")).findFirst().orElseThrow();
        assertTrue(notice.contains("project1/chat-02 is running a turn, started 12 minutes ago."), notice);

        worker.release.countDown();
        deadline = System.currentTimeMillis() + 15_000;
        while (liaison.prompts.stream().noneMatch(x -> x.contains("[reply to request project1/chat-01:")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(liaison.prompts.stream().anyMatch(x -> x.contains("[reply to request project1/chat-01:") && x.contains("the job is done")),
                "the reply still starts a turn: " + liaison.prompts);
        deadline = System.currentTimeMillis() + 10_000;
        while (!one.chat().outstandingChildrenDirect().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertEquals(0, one.chat().outstandingChildrenDirect().size(), "the reply took the request off the list");
    }

    /** The notice names the request, how long it has been out and the target's state, and says plainly that ignoring it is valid. */
    @Test
    void theChildReplyNoticeNamesTheRequestAndSaysIgnoringItIsValid() {
        long now = 1_000_000_000_000L;
        String n = Timekeeper.noticeText("project1/chat-01:s1:t1:r1", "project1/chat-02", 7,
                new Timekeeper.TargetState(true, true, now - 12 * 60_000L), now);
        assertTrue(n.contains("project1/chat-02 is running a turn, started 12 minutes ago."), n);
        assertTrue(Timekeeper.noticeText("r", "project1/chat-02", 7, new Timekeeper.TargetState(true, false, 0), now)
                .contains("project1/chat-02 is not running a turn."));
        assertTrue(Timekeeper.noticeText("r", "project1/chat-02", 7, Timekeeper.TargetState.UNKNOWN, now)
                .contains("project1/chat-02's state could not be read."));
        assertTrue(n.startsWith("[no reply yet to request project1/chat-01:s1:t1:r1 sent to project1/chat-02, 7 minutes]"), n);
        assertTrue(n.contains("has been out 7 minutes"), n);
        assertTrue(n.contains("Ignoring this notice is a valid answer"), n);
        assertTrue(n.contains("make no tool calls"), n);
        assertTrue(n.contains("do not create extra work"), n);
    }

    @Test
    void theDefaultsAreFiveMinutes() {
        assertEquals(5, Timekeeper.DEFAULT_CHILD_REPLY_WARN_MINUTES);
        assertEquals(5, Timekeeper.DEFAULT_PROMISE_REMINDER_MINUTES);
    }
}
