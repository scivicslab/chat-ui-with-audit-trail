package com.scivicslab.chatui.core.actor;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ReplyWatchRearmedAtTheEndOfEveryTurn_261116_oo01}: a request this conversation sent that stays open is
 * reminded again and again, once per reply-wait interval, until its reply arrives. Observed on 28010: project1/chat-01
 * sent t945 to chat-57, was told once after five minutes that no reply had come, answered "still working, I will
 * wait", and was never told again.
 */
class ReplyWatchRepeatsTest {

    private static final long WARN = 300_000;   // five minutes, the default

    /** Says one scripted thing per prompt, the last one repeating; optionally holds its first prompt. */
    private static final class Scripted implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<String> replies;
        final boolean hold;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Scripted(boolean hold, String... replies) {
            this.hold = hold;
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
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
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
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild("project1/chat-" + chatId + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<Timekeeper> keeper = iiar.createChild("project1/chat-" + chatId + ".timekeeper",
                new Timekeeper("project1/chat-" + chatId, iiar, queue, e -> { }, 600_000, WARN));
        return new Squad(iiar, queue, keeper);
    }

    private static void personTypes(Squad squad, String text) {
        squad.queue().tellNow(q -> q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(),
                "human")).join();
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("</invoke>").toString();
    }

    /**
     * How many notices the conversation was given: the model calls whose prompt ends with a notice. A notice is the last
     * thing in the prompt of the turn it starts, so a notice that only appears as earlier history is not counted.
     */
    private static long notices(Scripted s) {
        return s.prompts.stream().filter(x -> x.strip().endsWith("(no new request just to ask for progress).")
                && x.contains("[no reply yet to request project1/chat-01:")).count();
    }

    /** Waits until the conversation has finished its turn and nothing waits in its queue. */
    private static void waitIdle(Squad squad) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        Thread.sleep(100);
        while (System.currentTimeMillis() < deadline) {
            boolean busy = squad.chat().isBusyDirect();
            boolean pending = squad.queue().ask(PromptQueue::hasPending).get(5, TimeUnit.SECONDS);
            if (!busy && !pending) return;
            Thread.sleep(25);
        }
        throw new AssertionError("the conversation did not become idle");
    }

    /** Ticks the conversation's own timekeeper actor at a given time and waits for what it queued to be answered. */
    private static void tickAt(Squad squad, long at) throws Exception {
        squad.keeper().ask(k -> { k.tick(at); return null; }).get(10, TimeUnit.SECONDS);
        waitIdle(squad);
    }

    @Test
    void anOpenRequestIsRemindedAgainAfterEveryNoticeTurn_andTheRemindersStopWhenTheReplyArrives() throws Exception {
        IIActorSystem system = new IIActorSystem("reply-watch-repeats");
        Scripted liaison = new Scripted(false, invoke("ask_chat", "chatId", "02", "prompt", "do the long job"),
                "Handed it out.", "Still working, I will wait.", "Still working, I will wait.", "Still working, I will wait.",
                invoke("accept_reply"), "Done.");
        Scripted worker = new Scripted(true, "the job is done");
        Squad one = squad(system, "01", liaison);
        Squad two = squad(system, "02", worker);

        personTypes(one, "have chat-02 do the long job");
        assertTrue(worker.started.await(10, TimeUnit.SECONDS));
        waitIdle(one);
        assertEquals(1, one.chat().outstandingChildrenDirect().size(), "the request is open");

        // First notice, as before: five minutes after the request was sent.
        tickAt(one, System.currentTimeMillis() + WARN + 60_000);
        assertEquals(1, notices(liaison), liaison.prompts.toString());

        // The notice turn ended with the request still open: the watch is armed again, from the end of that turn.
        tickAt(one, System.currentTimeMillis() + 60_000);
        assertEquals(1, notices(liaison), "not before another interval has passed");
        tickAt(one, System.currentTimeMillis() + WARN + 60_000);
        assertEquals(2, notices(liaison), "a second notice after the second interval: " + liaison.prompts);
        tickAt(one, System.currentTimeMillis() + WARN + 60_000);
        assertEquals(3, notices(liaison), "and a third: " + liaison.prompts);

        // The reply arrives: the request is closed, and no notice follows however long one waits.
        worker.release.countDown();
        long deadline = System.currentTimeMillis() + 15_000;
        while (!one.chat().outstandingChildrenDirect().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        waitIdle(one);
        assertTrue(one.chat().outstandingChildrenDirect().isEmpty(), "the reply closed the request");
        tickAt(one, System.currentTimeMillis() + 3 * WARN);
        tickAt(one, System.currentTimeMillis() + 6 * WARN);
        assertEquals(3, notices(liaison), "no notice after the reply: " + liaison.prompts);
    }

    @Test
    void oneWatchPerOpenRequest_noDuplicateNoticesWithinAnInterval() throws Exception {
        IIActorSystem system = new IIActorSystem("reply-watch-no-duplicates");
        Scripted liaison = new Scripted(false, invoke("ask_chat", "chatId", "02", "prompt", "do the long job"),
                "Handed it out.", "Still working, I will wait.");
        Scripted worker = new Scripted(true, "the job is done");
        Squad one = squad(system, "01", liaison);
        squad(system, "02", worker);

        personTypes(one, "have chat-02 do the long job");
        assertTrue(worker.started.await(10, TimeUnit.SECONDS));
        waitIdle(one);
        tickAt(one, System.currentTimeMillis() + WARN + 60_000);
        assertEquals(1, notices(liaison));

        // Within the next interval, however often the timekeeper looks, no notice comes.
        long armed = System.currentTimeMillis();
        tickAt(one, armed + 60_000);
        tickAt(one, armed + 120_000);
        tickAt(one, armed + 180_000);
        assertEquals(1, notices(liaison), "one watch, one notice per interval: " + liaison.prompts.size() + " prompts");

        // Turns that end for other reasons keep the one pending watch; they neither add one nor push it back.
        personTypes(one, "anything new?");
        waitIdle(one);
        personTypes(one, "and now?");
        waitIdle(one);
        assertEquals(List.of("reply watch already pending: " + one.chat().outstandingChildrenDirect().get(0).requestId()),
                lines(one));
        tickAt(one, armed + WARN + 60_000);
        assertEquals(2, notices(liaison), "the next notice comes one interval after the watch was armed: "
                + liaison.prompts.size() + " prompts");
        worker.release.countDown();
    }

    @Test
    void eachRequestsOutcomeIsLogged_reArmedAfterItsNotice_pendingOtherwise() throws Exception {
        IIActorSystem system = new IIActorSystem("reply-watch-log");
        Scripted liaison = new Scripted(false, invoke("ask_chat", "chatId", "02", "prompt", "do the long job"),
                "Handed it out.", "Still working, I will wait.");
        Scripted worker = new Scripted(true, "the job is done");
        Squad one = squad(system, "01", liaison);
        squad(system, "02", worker);

        personTypes(one, "have chat-02 do the long job");
        assertTrue(worker.started.await(10, TimeUnit.SECONDS));
        waitIdle(one);
        String id = one.chat().outstandingChildrenDirect().get(0).requestId();
        assertEquals(List.of("reply watch already pending: " + id), lines(one), "the first watch is the one the send armed");

        tickAt(one, System.currentTimeMillis() + WARN + 60_000);
        assertEquals(List.of("reply watch re-armed: " + id), lines(one), "the notice turn armed it again");
        worker.release.countDown();
    }

    @Test
    void timekeeperReArmsOnlyAWatchWhoseNoticeWasGiven_andForgetsClosedRequests() {
        Timekeeper keeper = new Timekeeper("project1/chat-01", null, null, e -> { }, 600_000, WARN);
        OpenRequests.Outstanding a = new OpenRequests.Outstanding("r-a", "project1/chat-02", 0);
        assertEquals(List.of("reply watch already pending: r-a"), keeper.rearmReplyWatches(List.of(a), 1_000));
        assertEquals(List.of(), keeper.rearmReplyWatches(List.of(), 2_000), "nothing open, nothing armed");
    }

    @Test
    void everyLoopThatCallsTheModelArmsTheWatchesAgainWhereverATurnEnds() throws Exception {
        for (String loop : List.of("chat-session-agent-loop", "plain-text-loop", "plain-text-no-context-loop",
                "doc-retrieval-prompt-loop")) {
            try (java.io.InputStream in = getClass().getResourceAsStream("/workflows/" + loop + ".yaml")) {
                String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(3, yaml.split("method: rearmReplyWatches", -1).length - 1,
                        loop + ": think-final, step-limit and think-asks-person");
                int finalAt = yaml.indexOf("label: think-final");
                assertTrue(yaml.indexOf("method: rearmReplyWatches", finalAt) < yaml.indexOf("method: decideReminder", finalAt), loop);
            }
        }
    }

    private static List<String> lines(Squad squad) {
        return squad.chat().askNow(a -> ((ChatSession) a).lastReplyWatchLines()).join();
    }
}
