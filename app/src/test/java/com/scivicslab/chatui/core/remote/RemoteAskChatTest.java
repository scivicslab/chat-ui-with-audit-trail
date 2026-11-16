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

    private static Squad squad(IIActorSystem system, LlmProvider provider) {
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
        // b keeps the request under its local id: the sending node in front of the sender's id. b's own end-of-turn
        // step registers a promise under that id too.
        two.keeper().ask(k -> k.expectReport("a|" + requestId, "project1/chat-01", 0)).get(5, TimeUnit.SECONDS);
        two.keeper().ask(k -> { k.tick(System.currentTimeMillis() + 60_000); return null; }).get(5, TimeUnit.SECONDS);
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[follow-up to request " + requestId)),
                "the follow-up in a's queue");
        String followUp = asker.prompts.stream().filter(p -> p.contains("[follow-up to request " + requestId)).findFirst().orElseThrow();
        assertTrue(followUp.contains("42 items"), followUp);
    }

    /** Answers each prompt by what it asks for: "job from X" is answered "answer to X". */
    private static final class Echo implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public String id() { return "echo"; }
        @Override public String displayName() { return "Echo"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "echo-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (prompt.contains("warm-up job") && started.getCount() > 0) {
                started.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("job from (\\w+)").matcher(prompt);
            String who = null;
            while (m.find()) who = m.group(1);
            emitter.accept(ChatEvent.delta(who == null ? "ok" : "answer to " + who));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static String request(String requestId, String senderNode, String prompt) {
        return new JSONObject().put("requestId", requestId).put("sender", "project1/chat-01")
                .put("senderNode", senderNode).put("target", "project1/chat-01").put("prompt", prompt)
                .put("urgent", false).toString();
    }

    /**
     * Request ids are unique only inside the instance that made them, so two nodes can send the same id to one
     * receiver. The receiver keeps them apart by (sender node, request id): a cancel from one node takes back only
     * its own request, and each reply goes back to the node that sent it.
     */
    @Test
    void twoNodesSendingTheSameRequestIdStaySeparate() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-clash");
        IIActorSystem b = new IIActorSystem("instance-b-clash");
        IIActorSystem c = new IIActorSystem("instance-c-clash");
        Scripted askerA = new Scripted(false, "ok");
        Scripted askerC = new Scripted(false, "ok");
        Echo worker = new Echo();
        squad(a, askerA);
        squad(b, worker);
        squad(c, askerC);
        portA = freePort();
        portB = freePort();
        int portC = freePort();
        remoteA = RemoteNodes.start(a, "b=127.0.0.1:" + portB, "a", portA, dir.resolve("a-ids.txt"));
        remoteB = RemoteNodes.start(b, "a=127.0.0.1:" + portA + ",c=127.0.0.1:" + portC, "b", portB, dir.resolve("b-ids.txt"));
        RemoteNodes remoteC = RemoteNodes.start(c, "b=127.0.0.1:" + portB, "c", portC, dir.resolve("c-ids.txt"));
        try {
            RemoteActorRef gateway = new RemoteActorRef(RemoteNodes.GATEWAY, new NodeInfo("b", "127.0.0.1", portB));
            String same = "project1/chat-01:t1:r1";
            // b is held by a first job, so the next two wait in its queue.
            assertTrue(gateway.send("enqueueRequest", request("project1/chat-01:t0:r9", "a", "warm-up job")).accepted());
            assertTrue(worker.started.await(15, TimeUnit.SECONDS));
            assertTrue(gateway.send("enqueueRequest", request(same, "a", "do the job from a")).accepted());
            assertTrue(gateway.send("enqueueRequest", request(same, "c", "do the job from c")).accepted());

            // c takes its request back; a's, with the same id, stays.
            assertTrue(gateway.send("withdrawRequest", new JSONObject().put("requestId", same)
                    .put("sender", "project1/chat-01").put("senderNode", "c").toString()).accepted());
            waitUntil(() -> askerC.prompts.stream().anyMatch(p -> p.contains("[cancel result for request " + same)),
                    "the cancel result on c");
            assertTrue(askerC.prompts.stream().anyMatch(p -> p.contains("[cancel result for request " + same) && p.contains(": removed]")),
                    "c's request was removed: " + askerC.prompts);

            worker.release.countDown();
            waitUntil(() -> askerA.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same)), "a's reply");
            String replyA = askerA.prompts.stream().filter(p -> p.contains("[reply to request " + same)).findFirst().orElseThrow();
            assertTrue(replyA.contains("answer to a"), "a's own request ran and its reply came to a: " + replyA);
            Thread.sleep(500);
            assertFalse(worker.prompts.stream().anyMatch(p -> p.contains("do the job from c")), "c's request never ran");
            assertFalse(askerC.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same)), "c got no reply");
        } finally {
            remoteC.close();
        }
    }

    @Test
    void twoNodesSendingTheSameRequestIdBothGetTheirOwnReplies() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-clash2");
        IIActorSystem b = new IIActorSystem("instance-b-clash2");
        IIActorSystem c = new IIActorSystem("instance-c-clash2");
        Scripted askerA = new Scripted(false, "ok");
        Scripted askerC = new Scripted(false, "ok");
        Echo worker = new Echo();
        squad(a, askerA);
        squad(b, worker);
        squad(c, askerC);
        portA = freePort();
        portB = freePort();
        int portC = freePort();
        remoteA = RemoteNodes.start(a, "b=127.0.0.1:" + portB, "a", portA, dir.resolve("a-ids.txt"));
        remoteB = RemoteNodes.start(b, "a=127.0.0.1:" + portA + ",c=127.0.0.1:" + portC, "b", portB, dir.resolve("b-ids.txt"));
        RemoteNodes remoteC = RemoteNodes.start(c, "b=127.0.0.1:" + portB, "c", portC, dir.resolve("c-ids.txt"));
        try {
            RemoteActorRef gateway = new RemoteActorRef(RemoteNodes.GATEWAY, new NodeInfo("b", "127.0.0.1", portB));
            String same = "project1/chat-01:t1:r1";
            assertTrue(gateway.send("enqueueRequest", request("project1/chat-01:t0:r9", "a", "warm-up job")).accepted());
            assertTrue(worker.started.await(15, TimeUnit.SECONDS));
            assertTrue(gateway.send("enqueueRequest", request(same, "a", "do the job from a")).accepted());
            assertTrue(gateway.send("enqueueRequest", request(same, "c", "do the job from c")).accepted());
            worker.release.countDown();

            waitUntil(() -> askerA.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same))
                    && askerC.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same)), "both replies");
            assertTrue(askerA.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same) && p.contains("answer to a")),
                    "a's reply is a's: " + askerA.prompts);
            assertTrue(askerC.prompts.stream().anyMatch(p -> p.contains("[reply to request " + same) && p.contains("answer to c")),
                    "c's reply is c's: " + askerC.prompts);
        } finally {
            remoteC.close();
        }
    }

    @Test
    void expectReportWithANodeRegistersThePromiseWithTheRemoteConversationsTimekeeper() throws Exception {
        IIActorSystem a = new IIActorSystem("instance-a-expect");
        IIActorSystem b = new IIActorSystem("instance-b-expect");
        Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "start the survey"),
                "Handed it out.",
                invoke("expect_report", "chatId", "01", "node", "b", "requestId", "project1/chat-01:t1:r1", "minutes", "1"),
                "Waiting for the report.", "noted");
        Scripted worker = new Scripted(false, "Started; I will report when it is done.", "The survey is done: 42 items.");
        Squad one = squad(a, asker);
        Squad two = squad(b, worker);
        startNodes(a, b);

        personTypes(one, "start the survey on the other instance");
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:t1:r1")), "the reply");
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("timekeeper will remind it")), "expect_report ran");
        String result = asker.prompts.stream().filter(p -> p.contains("timekeeper will remind it")).findFirst().orElseThrow();
        assertTrue(result.contains("on node b"), "the promise went to b: " + result);
        waitUntil(() -> !two.keeper().askNow(Timekeeper::pendingPromises).join().isEmpty(), "b's timekeeper holds the promise");

        two.keeper().ask(k -> { k.tick(System.currentTimeMillis() + 120_000); return null; }).get(5, TimeUnit.SECONDS);
        waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[follow-up to request project1/chat-01:t1:r1")),
                "the follow-up in a's queue");
        assertTrue(asker.prompts.stream().anyMatch(p -> p.contains("[follow-up to request project1/chat-01:t1:r1") && p.contains("42 items")));
    }

    /** Plays the AI-workspace's {@code GET /api/chat-ui/peers}; the list can change while the test runs. */
    private static final class StubWorkspace implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicReference<String> list = new java.util.concurrent.atomic.AtomicReference<>("[]");
        final java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();

        StubWorkspace() throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat-ui/peers", ex -> {
                asked.incrementAndGet();
                byte[] out = list.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        void lists(Object... nameAndPort) {
            org.json.JSONArray a = new org.json.JSONArray();
            for (int i = 0; i < nameAndPort.length; i += 2) {
                a.put(new JSONObject().put("name", nameAndPort[i]).put("host", "127.0.0.1").put("actorPort", nameAndPort[i + 1]));
            }
            list.set(a.toString());
        }

        @Override public void close() { server.stop(0); }
    }

    @Test
    void twoInstancesFindEachOtherThroughTheWorkspace_withNoPeersSetByHand() throws Exception {
        try (StubWorkspace ws = new StubWorkspace()) {
            IIActorSystem a = new IIActorSystem("instance-a-ws");
            IIActorSystem b = new IIActorSystem("instance-b-ws");
            Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "chat-ui-b", "prompt", "do the job"),
                    "Handed it out.", invoke("accept_reply"), "Done.");
            Scripted worker = new Scripted(false, "the job is done");
            Squad one = squad(a, asker);
            squad(b, worker);
            portA = freePort();
            portB = freePort();
            ws.lists("chat-ui-a", portA, "chat-ui-b", portB);
            remoteA = RemoteNodes.start(a, "", ws.url(), "chat-ui-a", portA, dir.resolve("a-ids.txt"));
            remoteB = RemoteNodes.start(b, "", ws.url(), "chat-ui-b", portB, dir.resolve("b-ids.txt"));
            assertTrue(remoteA != null && remoteB != null, "the receiving side opens with the workspace as the only source");
            assertEquals(List.of("chat-ui-b"), remoteA.others(), "a's own entry is left out");

            personTypes(one, "have the other instance do the job");
            waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")
                    && p.contains("the job is done")), "the reply in a's queue");
        }
    }

    @Test
    void anInstanceThatJoinsLaterIsReachedAfterTheListIsReadAgainForAnUnknownNode() throws Exception {
        try (StubWorkspace ws = new StubWorkspace()) {
            IIActorSystem a = new IIActorSystem("instance-a-late");
            IIActorSystem c = new IIActorSystem("instance-c-late");
            Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "chat-ui-c", "prompt", "do the job"),
                    "Handed it out.", invoke("accept_reply"), "Done.");
            Scripted worker = new Scripted(false, "the late one did it");
            Squad one = squad(a, asker);
            squad(c, worker);
            portA = freePort();
            int portC = freePort();
            ws.lists("chat-ui-a", portA);
            remoteA = RemoteNodes.start(a, "", ws.url(), "chat-ui-a", portA, dir.resolve("a-ids.txt"));
            assertEquals(List.of(), remoteA.others(), "a starts alone");

            // c starts later; the workspace lists it, but a has not read the list since.
            ws.lists("chat-ui-a", portA, "chat-ui-c", portC);
            RemoteNodes remoteC = RemoteNodes.start(c, "", ws.url(), "chat-ui-c", portC, dir.resolve("c-ids.txt"));
            try {
                personTypes(one, "have the late instance do the job");
                waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")
                        && p.contains("the late one did it")), "the reply from the instance that joined later");
            } finally {
                remoteC.close();
            }
        }
    }

    @Test
    void aNodeThatLeavesTheWorkspaceListIsUnregistered_sendsToItAnswerNodeNotKnown_andItIsReachedAgainWhenItReturns()
            throws Exception {
        try (StubWorkspace ws = new StubWorkspace()) {
            IIActorSystem a = new IIActorSystem("instance-a-drop");
            IIActorSystem b = new IIActorSystem("instance-b-drop");
            Scripted asker = new Scripted(false,
                    invoke("ask_chat", "chatId", "01", "node", "chat-ui-b", "prompt", "first job"), "Noted.",
                    invoke("ask_chat", "chatId", "01", "node", "chat-ui-b", "prompt", "second job"), "Handed it out.",
                    invoke("accept_reply"), "Done.");
            Scripted worker = new Scripted(false, "the second job is done");
            Squad one = squad(a, asker);
            squad(b, worker);
            portA = freePort();
            portB = freePort();
            ws.lists("chat-ui-a", portA, "chat-ui-b", portB);
            remoteA = RemoteNodes.start(a, "", ws.url(), "chat-ui-a", portA, dir.resolve("a-ids.txt"));
            remoteB = RemoteNodes.start(b, "", ws.url(), "chat-ui-b", portB, dir.resolve("b-ids.txt"));
            assertEquals(List.of("chat-ui-b"), remoteA.others());

            // b drops off the workspace's list; a reads the list again.
            ws.lists("chat-ui-a", portA);
            remoteA.refreshPeers();
            assertEquals(List.of(), remoteA.others(), "b is unregistered after the refresh");
            Receipt direct = remoteA.send("chat-ui-b", "readTurnState", new JSONObject().put("target", "x").toString());
            assertFalse(direct.accepted(), "a send to b is refused at once");
            assertTrue(direct.detail().contains("node not known"), direct.detail());

            personTypes(one, "have the other instance do the first job");
            waitUntil(() -> asker.prompts.size() >= 2, "the tool's answer to the first ask_chat");
            assertTrue(asker.prompts.get(1).contains("node not known: chat-ui-b"),
                    "ask_chat to b answers node not known: " + asker.prompts.get(1));
            assertFalse(worker.prompts.stream().anyMatch(p -> p.contains("first job")), "nothing reached b");

            // b is listed again; the next refresh registers it again.
            ws.lists("chat-ui-a", portA, "chat-ui-b", portB);
            remoteA.refreshPeers();
            assertEquals(List.of("chat-ui-b"), remoteA.others(), "b is registered again");

            personTypes(one, "have the other instance do the second job");
            waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")
                    && p.contains("the second job is done")), "the reply from b after it came back");
        }
    }

    @Test
    void peersSetByHandComeFirst_theWorkspaceIsNotAsked() throws Exception {
        try (StubWorkspace ws = new StubWorkspace()) {
            IIActorSystem a = new IIActorSystem("instance-a-byhand");
            IIActorSystem b = new IIActorSystem("instance-b-byhand");
            Scripted asker = new Scripted(false, invoke("ask_chat", "chatId", "01", "node", "b", "prompt", "do the job"),
                    "Handed it out.", invoke("accept_reply"), "Done.");
            Scripted worker = new Scripted(false, "the job is done");
            Squad one = squad(a, asker);
            squad(b, worker);
            portA = freePort();
            portB = freePort();
            // The workspace would send b's name to a port nobody listens on.
            ws.lists("a", portA, "b", freePort());
            remoteA = RemoteNodes.start(a, "b=127.0.0.1:" + portB, ws.url(), "a", portA, dir.resolve("a-ids.txt"));
            remoteB = RemoteNodes.start(b, "a=127.0.0.1:" + portA, ws.url(), "b", portB, dir.resolve("b-ids.txt"));

            personTypes(one, "have the other instance do the job");
            waitUntil(() -> asker.prompts.stream().anyMatch(p -> p.contains("[reply to request project1/chat-01:")
                    && p.contains("the job is done")), "the reply through the list set by hand");
            assertEquals(0, ws.asked.get(), "the workspace was never asked");
        }
    }

    @Test
    void withNoSourceOfPeersThereIsNoRemotePath() throws Exception {
        assertEquals(null, RemoteNodes.start(new IIActorSystem("instance-alone"), "28010,http://localhost:28014", "",
                "alone", freePort(), dir.resolve("alone-ids.txt")));
    }
}
