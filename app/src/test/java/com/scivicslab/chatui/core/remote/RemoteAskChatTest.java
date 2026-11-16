package com.scivicslab.chatui.core.remote;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.actor.ChatSession;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.PromptQueue;
import com.scivicslab.chatui.core.actor.Timekeeper;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.Receipt;
import com.scivicslab.pojoactor.distributed.RemoteActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ConversationSquadsAcrossInstances_261115_oo01}: two chat-ui instances in one JVM, each with its own actor
 * system and its own remote path on its own port of 127.0.0.1. A conversation of instance a sends a request with
 * {@code ask_chat} and {@code node} to a conversation of instance b, and the reply comes back to a's queue. Both
 * conversations are called {@code project1/chat-01}: the node is what tells them apart.
 */
class RemoteAskChatTest {

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

    private static Squad squad(IIActorSystem system, Scripted provider) {
        ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-01.chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", "01");
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild("project1/chat-01.queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<Timekeeper> keeper = iiar.createChild("project1/chat-01.timekeeper",
                new Timekeeper("project1/chat-01", iiar, queue, e -> { }, 600_000, 300_000));
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

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting: " + what);
            Thread.sleep(25);
        }
    }

    @TempDir Path dir;
    private RemoteNodes remoteA;
    private RemoteNodes remoteB;
    private int portA;
    private int portB;

    private void startNodes(IIActorSystem a, IIActorSystem b) throws Exception {
        portA = freePort();
        portB = freePort();
        remoteA = RemoteNodes.start(a, "b=127.0.0.1:" + portB, "a", portA, dir.resolve("a-ids.txt"));
        remoteB = RemoteNodes.start(b, "a=127.0.0.1:" + portA, "b", portB, dir.resolve("b-ids.txt"));
    }

    @AfterEach
    void stop() {
        if (remoteA != null) remoteA.close();
        if (remoteB != null) remoteB.close();
    }

    @Test
    void aRequestGoesToTheOtherInstance_itsTurnAnswers_andTheReplyArrivesInTheSendersQueue() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a");
        IIActorSystem b = new IIActorSystem("instance-b");
        Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "do the job"),
                "Handed it out.", invoke("accept_reply"), "Done.");
        Scripted worker = new Scripted(false, "the job is done");
        Squad one = squad(a, asker);
        squad(b, worker);
        startNodes(a, b);

        personTypes(one, "have the other instance do the job");

        waitUntil(() -> worker.prompts.stream().anyMatch(p -> p.contains("do the job")), "b's turn");
        String request = worker.prompts.stream().filter(p -> p.contains("do the job")).findFirst().orElseThrow();
        assertTrue(request.contains("[request project1/chat-01:") && request.contains(" from project1/chat-01 on node a]"),
                "b's conversation reads who sent it and from which node: " + request);
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")),
                "the reply in a's queue");
        String reply = asker.prompts.stream().filter(p -> p.contains("[reply to request project1/chat-01:")).findFirst().orElseThrow();
        assertTrue(reply.contains("the job is done"), reply);
        assertTrue(asker.prompts.stream().anyMatch(p -> p.contains("sent to project1/chat-01 on node b as request")),
                "the tool said where it went: " + asker.prompts);
        waitUntil(() -> one.chat().outstandingChildrenDirect().isEmpty(), "the request closed on a");
    }

    @Test
    void theSameMessageSentTwiceRunsOnce() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-dup");
        IIActorSystem b = new IIActorSystem("instance-b-dup");
        Scripted asker = new Scripted(false, "ok");
        Scripted worker = new Scripted(false, "the job is done");
        squad(a, asker);
        squad(b, worker);
        startNodes(a, b);

        String json = new JSONObject().put("requestId", "project1/chat-01:s1:t1:r1").put("sender", "project1/chat-01")
                .put("senderNode", "a").put("target", "project1/chat-01").put("prompt", "count me once")
                .put("urgent", false).toString();
        RemoteActorRef gateway = new RemoteActorRef(RemoteNodes.GATEWAY, new NodeInfo("b", "127.0.0.1", portB));
        Receipt first = gateway.send("enqueueRequest", json, "message-1");
        Receipt second = gateway.send("enqueueRequest", json, "message-1");
        assertTrue(first.accepted() && second.accepted(), "both copies are received: " + first + " " + second);

        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:s1:t1:r1")),
                "the reply");
        Thread.sleep(500);
        assertEquals(1, worker.prompts.stream().filter(p -> p.contains("count me once")).count(),
                "the second copy was dropped: " + worker.prompts.size() + " prompts");
    }

    @Test
    void anythingOutsideTheAllowListIsRefused() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-refuse");
        IIActorSystem b = new IIActorSystem("instance-b-refuse");
        Scripted worker = new Scripted(false, "should not run");
        squad(a, new Scripted(false, "ok"));
        squad(b, worker);
        startNodes(a, b);
        NodeInfo nodeB = new NodeInfo("b", "127.0.0.1", portB);

        // An action every IIActorRef has, but not one of the seven.
        assertFalse(new RemoteActorRef(RemoteNodes.GATEWAY, nodeB).send("putJson", "{}").accepted());
        // A conversation's own actors are not reachable at all: they live in the instance's own actor system.
        assertFalse(new RemoteActorRef("project1/chat-01.queue", nodeB).send("enqueueRequest", "{}").accepted());
        assertFalse(new RemoteActorRef("project1/chat-01.chat", nodeB).send("stepExpectingAction", "").accepted());
        Thread.sleep(300);
        assertTrue(worker.prompts.isEmpty(), "nothing ran on b: " + worker.prompts);
        assertEquals(7, RemoteNodes.ALLOWED.size());
    }

    @Test
    void aWaitingRequestIsTakenBack_andTheCancelResultArrivesAsAMessage() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-cancel");
        IIActorSystem b = new IIActorSystem("instance-b-cancel");
        Scripted asker = new Scripted(false,
                invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "first job") + "\n"
                        + invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "second job"),
                "Handed both out.", "noted");
        Scripted worker = new Scripted(true, "first job done");
        Squad one = squad(a, asker);
        squad(b, worker);
        startNodes(a, b);

        personTypes(one, "hand out two jobs");
        assertTrue(worker.started.await(15, TimeUnit.SECONDS), "b runs the first job and holds");
        waitUntil(() -> one.chat().outstandingChildrenDirect().size() == 2, "two requests out");
        String second = one.chat().outstandingChildrenDirect().stream()
                .map(o -> o.requestId()).sorted().reduce((x, y) -> y).orElseThrow();
        waitUntil(() -> !one.chat().isBusyDirect(), "a's turn ended");

        String result = AskChatTool.cancel(a, "project1/chat-01", second);
        assertTrue(result.startsWith("cancel sent: request " + second), result);
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[cancel result for request " + second)),
                "the cancel result in a's queue");
        String note = asker.prompts.stream().filter(p -> p.contains("[cancel result for request " + second)).findFirst().orElseThrow();
        assertTrue(note.contains(": removed]"), note);
        waitUntil(() -> one.chat().outstandingChildrenDirect().stream().noneMatch(o -> o.requestId().equals(second)),
                "the taken-back request is no longer waited for");

        worker.release.countDown();
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("first job done")), "the first reply");
        Thread.sleep(500);
        assertFalse(worker.prompts.stream().anyMatch(p -> p.contains("second job")), "the second job never ran on b");
        assertFalse(asker.prompts.stream().anyMatch(p -> p.contains("[reply to request " + second)), "and has no reply");
    }

    @Test
    void aFollowUpFromTheOtherInstanceArrivesInTheSendersQueue() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-follow");
        IIActorSystem b = new IIActorSystem("instance-b-follow");
        Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "start the survey"),
                "Handed it out.", "noted");
        Scripted worker = new Scripted(false, "Started; I will report when it is done.", "The survey is done: 42 items.");
        Squad one = squad(a, asker);
        Squad two = squad(b, worker);
        startNodes(a, b);

        personTypes(one, "start the survey on the other instance");
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")), "the reply");
        String requestId = worker.prompts.stream().filter(p -> p.contains("start the survey")).findFirst()
                .map(p -> {
                    int at = p.indexOf("[request project1/chat-01:") + "[request ".length();
                    return p.substring(at, p.indexOf(" from ", at));
                }).orElseThrow();

        // b's conversation promised a report; its timekeeper asks it now, and the answer goes back to a as a follow-up.
        two.keeper().ask(k -> k.expectReport(requestId, "project1/chat-01", 0)).get(5, TimeUnit.SECONDS);
        two.keeper().ask(k -> { k.tick(System.currentTimeMillis() + 60_000); return null; }).get(5, TimeUnit.SECONDS);
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[follow-up to request " + requestId)),
                "the follow-up in a's queue");
        String followUp = asker.prompts.stream().filter(p -> p.contains("[follow-up to request " + requestId)).findFirst().orElseThrow();
        assertTrue(followUp.contains("42 items"), followUp);
    }
}
