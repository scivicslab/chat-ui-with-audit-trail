package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * The prompts waiting in a conversation's queue survive a restart ({@code EachActorRecordsItsOwnState_261118_oo01},
 * step 10, decision 4): the queue records one entry per event (enqueued, with the whole prompt and its images;
 * started; cancelled), and at start-up puts back, in their order, the prompts that were enqueued and neither started
 * nor cancelled. A waiting ask_chat request put back this way is answered to its asker after the restart.
 */
class QueueSurvivesRestartTest {

    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";

    private static void hold(ActorRef<PromptQueue> queue, ChatSessionIIAR chat, String text, List<String> images) throws Exception {
        queue.tell(q -> q.enqueue(text, null, "queue", e -> { }, chat.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, false, images)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void heldPromptsComeBackInTheirOrderWithTheirImagesAndARemovedOneDoesNot(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("held"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            ActorRef<PromptQueue> queue = first.getPromptQueue("project1", "02");
            ChatSessionIIAR chat = first.getChatSession("project1", "02");
            hold(queue, chat, "first held", List.of());
            hold(queue, chat, "second held", List.of(IMAGE));
            hold(queue, chat, "third held", List.of());
            assertTrue(queue.ask(q -> q.removeAt(1)).get(5, TimeUnit.SECONDS));

            ChatUiActorSystem second = systems.start();
            List<PromptQueue.QueueEntry> back = second.getPromptQueue("project1", "02")
                    .ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("first held", "third held"), back.stream().map(PromptQueue.QueueEntry::prompt).toList(),
                    "the two not removed, in their order: " + back);
            assertTrue(back.stream().noneMatch(PromptQueue.QueueEntry::auto), "still held");

            // A restart with nothing changed puts back the same, not twice as many.
            ChatUiActorSystem third = systems.start();
            assertEquals(2, third.getPromptQueue("project1", "02").ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void anImageGoesIntoTheRecordAsItIs(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("image"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            hold(first.getPromptQueue("project1", "02"), first.getChatSession("project1", "02"), "look", List.of(IMAGE));
            List<String> events = SessionEntries.ofConversation(systems.log, IoLogView.over(systems.log), "project1/chat-02")
                    .all(PromptQueue.QUEUE_LABEL);
            assertEquals(1, events.size(), events.toString());
            org.json.JSONObject e = new org.json.JSONObject(events.get(0));
            assertEquals("enqueued", e.getString("event"));
            assertEquals(IMAGE, e.getJSONArray("images").getString(0), "the data URL as it was");
        }
    }

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
        queue.tellNow(q -> {
            q.setSelf(queue);
            q.setLogging(system, name + ".log");
            if (entries != null) q.recordsTo(entries, name);
        }).join();
        return iiar;
    }

    @Test
    void aWaitingRequestIsPutBackAndItsAnswerReachesTheAskerAfterTheRestart(@TempDir Path dir) throws Exception {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("queued-request").toString());
        IoLogView view = IoLogView.over(log);
        try {
            IIActorSystem before = new IIActorSystem("queue-before");
            chat(before, "01", new Scripted("unused"), null);
            ChatSessionIIAR two = chat(before, "02", new Scripted("unused") {
                @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                    prompts.add(p);
                    try { Thread.sleep(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }, SessionEntries.ofConversation(log, view, "project1/chat-02"));
            ActorRef<PromptQueue> queue2 = before.getActor("project1/chat-02.queue");
            // chat-02 is busy with a long turn, so chat-01's request waits in its queue.
            queue2.tell(q -> q.enqueue("a long job", null, "queue", e -> { }, two.asChatSessionRef(), "human"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!two.isBusyDirect() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            AskChatTool.send(before, "project1", "01", "02", "please report", "project1/chat-01:s1:t1:r1");
            assertEquals(1, queue2.ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS).size(), "the request waits");
            before.terminate();

            IIActorSystem after = new IIActorSystem("queue-after");
            Scripted asker = new Scripted(QueueSurvivesRestartTestHelp.accept("project1/chat-01:s1:t1:r1"), "thanks");
            chat(after, "01", asker, null);
            ChatSessionIIAR twoAgain = chat(after, "02", new Scripted("Here is the report."),
                    SessionEntries.ofConversation(log, view, "project1/chat-02"));
            ActorRef<PromptQueue> queueAgain = after.getActor("project1/chat-02.queue");
            queueAgain.tell(q -> q.restore(twoAgain.asChatSessionRef(), e -> { })).get(5, TimeUnit.SECONDS);

            deadline = System.currentTimeMillis() + 20_000;
            while (asker.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
            assertTrue(!asker.prompts.isEmpty() && asker.prompts.get(0).contains("[reply to request project1/chat-01:s1:t1:r1")
                    && asker.prompts.get(0).contains("Here is the report."), "chat-01 got the answer: " + asker.prompts);
            after.terminate();
        } finally {
            if (log.store() != null) log.store().close();
        }
    }
}

/** The decision a reply turn records. */
final class QueueSurvivesRestartTestHelp {
    static String accept(String requestId) {
        return "<invoke name=\"accept_reply\">\n<parameter name=\"requestId\">" + requestId
                + "</parameter>\n<reason>test</reason>\n</invoke>";
    }
}
