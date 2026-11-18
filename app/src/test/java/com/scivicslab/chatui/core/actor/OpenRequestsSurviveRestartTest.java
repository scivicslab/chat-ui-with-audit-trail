package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The requests a conversation received and has not answered, and the requests it sent and is still waiting on,
 * survive a restart ({@code EachActorRecordsItsOwnState_261118_oo01}, step 9): chat-02, asked by chat-01, asked
 * chat-03 and ended its turn waiting; after a restart chat-02 still waits on chat-03 (so its reply watch goes on),
 * and when chat-03's reply comes, chat-02's answer reaches chat-01 by its name.
 */
class OpenRequestsSurviveRestartTest {

    /** Says one scripted thing per prompt, the last one again once the script runs out. */
    private static class Scripted implements LlmProvider {
        final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> replies;
        Scripted(String... replies) { this.replies = List.of(replies); }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String m) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(replies.get(Math.min(prompts.size() - 1, replies.size() - 1))));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("<reason>test</reason>\n</invoke>").toString();
    }

    private static ChatSessionIIAR chat(IIActorSystem system, String chatId, LlmProvider provider, SessionEntries entries) {
        String name = "project1/chat-" + chatId;
        ChatSessionIIAR iiar = new ChatSessionIIAR(name + ".chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
            if (entries != null) c.recordsTo(entries);
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild(name + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        return iiar;
    }

    private static void waitFor(java.util.function.BooleanSupplier done, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(done.getAsBoolean(), what);
    }

    /** The link back to a sender on another instance is part of the received request, recorded and put back with it. */
    @Test
    void theLinkToASenderOnAnotherInstanceIsRecordedWithTheRequest() {
        OpenRequests first = new OpenRequests();
        first.received("a|project1/chat-01:s1:t1:r1", "project1/chat-01", new java.util.concurrent.CompletableFuture<>(),
                true, new OpenRequests.RemoteLink("a", "project1/chat-01:s1:t1:r1"));
        first.received("project1/chat-03:t1:r1", "project1/chat-03", new java.util.concurrent.CompletableFuture<>(),
                false, null);
        List<String> made = new ArrayList<>();
        OpenRequests second = new OpenRequests();
        second.load(new org.json.JSONObject(first.toJson().toString()), (id, asker, urgent, link) -> {
            made.add(id + " " + asker + " " + urgent + " " + (link == null ? "local" : link.node() + ":" + link.requestId()));
            return new java.util.concurrent.CompletableFuture<>();
        });
        made.sort(null);
        assertEquals(List.of("a|project1/chat-01:s1:t1:r1 project1/chat-01 true a:project1/chat-01:s1:t1:r1",
                "project1/chat-03:t1:r1 project1/chat-03 false local"), made);
    }

    @Test
    void aWaitingRequestComesBackAndItsAnswerReachesTheAskerWhenTheReplyComes(@TempDir Path dir) throws Exception {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("open-requests").toString());
        IoLogView view = IoLogView.over(log);
        try {
            // Before the restart: chat-02 is asked, asks chat-03 (which never answers), and ends its turn waiting.
            IIActorSystem before = new IIActorSystem("open-requests-before");
            chat(before, "01", new Scripted("unused"), null);
            ChatSessionIIAR two = chat(before, "02", new Scripted(
                    invoke("ask_chat", "chatId", "03", "prompt", "sub-task"), "Asked chat-03; waiting for it."),
                    SessionEntries.ofConversation(log, view, "project1/chat-02"));
            chat(before, "03", new Scripted("unused") {
                @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                    prompts.add(p);
                    // Stays in its turn past the end of the test, so its reply never comes before the restart.
                    try { Thread.sleep(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }, null);
            String sent = AskChatTool.send(before, "project1", "01", "02", "do the work", "project1/chat-01:s1:t1:r1");
            assertTrue(sent.startsWith("sent to project1/chat-02"), sent);
            waitFor(() -> !two.outstandingChildrenDirect().isEmpty() && !two.isBusyDirect(), "chat-02 ended its turn waiting on chat-03");
            String child = two.outstandingChildrenDirect().get(0).requestId();
            before.terminate();

            // After the restart: new actors on the same log.
            IIActorSystem after = new IIActorSystem("open-requests-after");
            Scripted asker = new Scripted(invoke("accept_reply", "requestId", "project1/chat-01:s1:t1:r1"), "thanks");
            chat(after, "01", asker, null);
            ChatSessionIIAR twoAgain = chat(after, "02", new Scripted(
                    invoke("accept_reply", "requestId", child), "The work is done."),
                    SessionEntries.ofConversation(log, view, "project1/chat-02"));
            twoAgain.tell(a -> ((ChatSession) a).restoreOpenRequests()).join();

            List<OpenRequests.Outstanding> out = twoAgain.outstandingChildrenDirect();
            assertEquals(1, out.size(), "chat-02 still waits on chat-03, so the reply watch goes on: " + out);
            assertEquals(child, out.get(0).requestId());
            assertEquals("project1/chat-03", out.get(0).target());

            // chat-03's reply arrives; chat-02's turn for it answers chat-01's request, by chat-01's name.
            AskChatTool.deliverReplyTo(after, "project1/chat-02", "project1/chat-03", child, false,
                    new TurnReply("sub-task finished", null, "project1/chat-03:t1"), null);
            waitFor(() -> !asker.prompts.isEmpty(), "chat-01 got a message");
            assertTrue(asker.prompts.get(0).contains("[reply to request project1/chat-01:s1:t1:r1, from project1/chat-02:t")
                    && asker.prompts.get(0).contains("The work is done."), asker.prompts.get(0));
            after.terminate();
        } finally {
            log.store().close();
        }
    }
}
