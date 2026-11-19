package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every turn that works for a request this conversation received, and ends with no answer, still tells the asker
 * why ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}). On 2026-11-18 and 19, chat-24 on 28012 received four
 * requests from chat-01 whose turns were cut by restarts of the instance; chat-01 heard nothing about any of them.
 */
@Tag("TurnWithoutAnAnswerTellsTheAsker_261119_oo01")
class TurnWithoutAnAnswerTellsTheAskerTest {

    private static final String REQUEST = "project1/chat-01:s5:t365:r1";

    /** Says one scripted thing per prompt, the last one again once the script runs out, and records every prompt. */
    private static class Scripted implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
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
            c.setReminderDecisionEnabled(false);
            if (entries != null) c.recordsTo(entries);
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild(name + ".queue", new PromptQueue());
        queue.tellNow(q -> {
            q.setSelf(queue);
            if (entries != null) q.recordsTo(entries, name);
        }).join();
        return iiar;
    }

    /** The message about the request that started chat-01's first turn: its prompt ends with it, after the preface. */
    private static String firstMessage(Scripted asker) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (asker.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(!asker.prompts.isEmpty(), "chat-01 heard back about its request");
        String prompt = asker.prompts.get(0);
        int at = Math.max(prompt.lastIndexOf("[request " + REQUEST), prompt.lastIndexOf("[reply to request " + REQUEST));
        return at < 0 ? prompt : prompt.substring(at).strip();
    }

    /** chat-01 asks chat-24, whose provider behaves as given; returns what chat-01 is told. */
    private static String askerIsTold(LlmProvider worker, Consumer<ChatSessionIIAR> whileRunning) throws Exception {
        IIActorSystem system = new IIActorSystem("turn-without-an-answer");
        try {
            Scripted asker = new Scripted("noted");
            chat(system, "01", asker, null);
            ChatSessionIIAR worker24 = chat(system, "24", worker, null);
            String sent = AskChatTool.send(system, "project1", "01", "24", "render the video", REQUEST);
            assertTrue(sent.startsWith("sent to project1/chat-24"), sent);
            whileRunning.accept(worker24);
            return firstMessage(asker);
        } finally {
            system.terminate();
        }
    }

    /**
     * The real path: chat-24 is in a long turn for chat-01's request when the instance stops; then it starts again.
     * As at a JVM shutdown, the I/O log closes first (on 28012 the trace shows "Database is already closed ... at VM
     * shutdown" at the moment the render was killed), so nothing the dying turn does afterwards is recorded.
     */
    @Test
    void restart_cutsTheTurnOfAReceivedRequest_askerIsToldWhenTheInstanceIsBack(@TempDir Path dir) throws Exception {
        String db = dir.resolve("restart").toString();
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(db);
        IIActorSystem before = new IIActorSystem("restart-before");
        chat(before, "01", new Scripted("unused"), null);
        ChatSessionIIAR worker = chat(before, "24", new Scripted("unused") {
            @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                // A Bash render step of several minutes: the instance stops before the step ends.
                e.accept(ChatEvent.toolUse("toolu_1", "Bash", "{\"command\":\"render 480-599\"}"));
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }, SessionEntries.ofConversation(log, IoLogView.over(log), "project1/chat-24"));
        AskChatTool.send(before, "project1", "01", "24", "render the video", REQUEST);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!worker.isBusyDirect() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertTrue(worker.isBusyDirect(), "chat-24 is in its turn for the request");
        log.store().close();
        before.terminate();

        IoLogStore reopened = new IoLogStore();
        reopened.useDatabaseAt(db);
        IoLogView view = IoLogView.over(reopened);
        try {
            IIActorSystem after = new IIActorSystem("restart-after");
            Scripted asker = new Scripted("noted");
            chat(after, "01", asker, null);
            ChatSessionIIAR again = chat(after, "24", new Scripted("unused"),
                    SessionEntries.ofConversation(reopened, view, "project1/chat-24"));
            again.tell(a -> ((ChatSession) a).restoreOpenRequests()).get(5, TimeUnit.SECONDS);
            assertTrue(again.<Boolean>ask(a -> ((ChatSession) a).isWorkingOn(REQUEST, "project1/chat-01"))
                    .get(5, TimeUnit.SECONDS), "the request came back unanswered, as chat-24's four did");
            ActorRef<PromptQueue> queue = after.getActor("project1/chat-24.queue");
            queue.tell(q -> q.restore(again.asChatSessionRef(), e -> { })).get(5, TimeUnit.SECONDS);

            assertEquals("[request " + REQUEST + " to project1/chat-24 ended without a reply: "
                    + "the instance restarted during its turn]", firstMessage(asker));
            assertTrue(again.<Boolean>ask(a -> ((ChatSession) a).isWorkingOn(REQUEST, "project1/chat-01"))
                    .get(5, TimeUnit.SECONDS) == Boolean.FALSE, "the request is closed, so it is not answered twice");
            after.terminate();
        } finally {
            if (reopened.store() != null) reopened.store().close();
        }
    }

    /** The provider says its CLI ended without a result: the turn fails, and the asker is told why. Not retried. */
    @Test
    void cliEndedWithoutAResult_askerIsToldTheTurnFailed() throws Exception {
        int[] calls = {0};
        String told = askerIsTold(new Scripted("unused") {
            @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                calls[0]++;
                e.accept(ChatEvent.delta("Rendering frames 480-599."));
                throw new IllegalStateException("the claude CLI process ended without a result (exit code 143)");
            }
        }, w -> { });
        assertEquals("[request " + REQUEST + " to project1/chat-24 ended without a reply: its turn failed: "
                + "the claude CLI process ended without a result (exit code 143)]", told);
        assertEquals(1, calls[0], "a CLI that ended is not sent the same prompt again");
    }

    /** A turn cancelled while it works for a request tells the asker it was cancelled. */
    @Test
    void cancelledTurn_askerIsToldItWasCancelled() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        String told = askerIsTold(new Scripted("unused") {
            @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                inside.countDown();
                try { cancelled.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            @Override public void cancel() { cancelled.countDown(); }
        }, w -> {
            try {
                assertTrue(inside.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
            w.tellNow(a -> ((ChatSession) a).cancel());
        });
        assertEquals("[request " + REQUEST + " to project1/chat-24 ended without a reply: its turn was cancelled]", told);
    }

    /** A turn whose model wrote nothing, even after the retry, has no answer to send; the asker is told so. */
    @Test
    void emptyAnswer_askerIsToldThereWasNoAnswer() throws Exception {
        String told = askerIsTold(new Scripted("unused") {
            @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                prompts.add(p);
            }
        }, w -> { });
        assertEquals("[request " + REQUEST + " to project1/chat-24 ended without a reply: its turn ended without an answer]", told);
    }
}
