package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
 * The timekeeper can be stopped completely ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01},
 * {@code CancelARequestInTheQueue_261113_oo01}). On 2026-11-24 project1/chat-01 kept getting "no reply yet" notices for
 * requests sent before a restart to conversations since deleted: {@code cancel_request} said "not found" and left them
 * open, and no limit turned the notices off. Each case here starts from such a request, put back from the log as a
 * restart puts it back.
 */
@Tag("TimekeeperPausesATurnForAnUrgentRequest_261107_oo01")
class TimekeeperFullStopTest {

    private static final long MINUTE = 60_000L;
    private static final String STALE = "project1/chat-01:s46:t2482:r1";
    private static final String GONE = "project1/chat-57";

    /** Says one scripted thing per prompt, the last one again once the script runs out. */
    private static final class Scripted implements LlmProvider {
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
        long notices() {
            return prompts.stream().filter(p -> p.contains("[no reply yet to request " + STALE)).count();
        }
    }

    private record Squad(ChatSessionIIAR chat, ActorRef<PromptQueue> queue, ActorRef<Timekeeper> keeper) {}

    /**
     * chat-01 after a restart: its log holds one request it sent, before the restart, to {@link #GONE}, which no longer
     * exists; that request is put back as still out.
     */
    private static Squad restarted(IIActorSystem system, LlmProvider provider, Path dir, long sentAt) {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("log").toString());
        IoLogView view = IoLogView.over(log);
        String name = "project1/chat-01";
        SessionEntries entries = SessionEntries.ofConversation(log, view, name);
        OpenRequests before = new OpenRequests();
        before.sentWithoutAnOriginal(STALE, GONE, sentAt);
        entries.write(ChatSession.OPEN_REQUESTS_LABEL, before.toJson().toString());

        ChatSessionIIAR iiar = new ChatSessionIIAR(name + ".chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", "01");
            c.setReminderDecisionEnabled(false);
            c.recordsTo(entries);
            c.restoreOpenRequests();
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild(name + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<Timekeeper> keeper = iiar.createChild(name + ".timekeeper",
                new Timekeeper(name, iiar, queue, e -> { }, 10 * MINUTE, 5 * MINUTE));
        assertEquals(List.of(STALE), iiar.outstandingChildrenDirect().stream().map(OpenRequests.Outstanding::requestId)
                .toList(), "the stale request is back as still out");
        return new Squad(iiar, queue, keeper);
    }

    private static void say(Squad squad, String text) throws Exception {
        squad.queue().ask(q -> {
            q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(), "human");
            return null;
        }).get(5, TimeUnit.SECONDS);
        settle(squad);
    }

    private static void settle(Squad squad) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            boolean queued = !squad.queue().ask(PromptQueue::snapshot).get(5, TimeUnit.SECONDS).isEmpty();
            if (!queued && !squad.chat().isBusyDirect()) {
                Thread.sleep(200);
                if (!squad.chat().isBusyDirect()) return;
            }
        }
        throw new AssertionError("the conversation did not settle");
    }

    private static void tick(Squad squad, long at) throws Exception {
        squad.keeper().ask(k -> { k.tick(at); return null; }).get(5, TimeUnit.SECONDS);
        settle(squad);
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("<reason>test</reason>\n</invoke>").toString();
    }

    @Test
    void childReplyWarnZero_givesNoNoticeAndANonZeroLimitBringsItBack(@TempDir Path dir) throws Exception {
        IIActorSystem system = new IIActorSystem("timekeeper-warn-off");
        try {
            long sent = System.currentTimeMillis();
            Scripted model = new Scripted("Noted.");
            Squad one = restarted(system, model, dir, sent);

            one.keeper().ask(k -> { k.setLimits(null, 0L); return null; }).get(5, TimeUnit.SECONDS);
            tick(one, sent + 60 * MINUTE);
            tick(one, sent + 600 * MINUTE);
            assertEquals(0, model.notices(), "0 turns the notices off: " + model.prompts);
            Timekeeper.Watch w = one.keeper().ask(k -> k.watch(sent + 600 * MINUTE)).get(5, TimeUnit.SECONDS);
            assertEquals(0, w.childReplyWarnMinutes());

            // The same request, with a limit again: the notice comes, so the case above exercised the watch.
            one.keeper().ask(k -> { k.setLimits(null, 5L); return null; }).get(5, TimeUnit.SECONDS);
            tick(one, sent + 601 * MINUTE);
            assertEquals(1, model.notices(), "with a limit, the stale request is reported: " + model.prompts);
        } finally {
            system.terminate();
        }
    }

    @Test
    void zeroLimits_surviveARestoreOfTheTimekeeper(@TempDir Path dir) {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("log").toString());
        IoLogView view = IoLogView.over(log);
        SessionEntries entries = SessionEntries.ofConversation(log, view, "project1/chat-01");

        Timekeeper first = new Timekeeper("project1/chat-01", null, null, e -> { }, 10 * MINUTE, 5 * MINUTE);
        first.recordsTo(entries);
        first.setLimits(0L, 0L);

        Timekeeper again = new Timekeeper("project1/chat-01", null, null, e -> { }, 10 * MINUTE, 5 * MINUTE);
        again.recordsTo(entries);
        again.restore();
        assertEquals(0, again.urgentWaitMinutes(), "the pause stays off after a restart");
        assertEquals(0, again.childReplyWarnMinutes(), "the notices stay off after a restart");
    }

    @Test
    void urgentWaitZero_meansNoPauseIsDue() {
        long now = 1_000_000L;
        Timekeeper.Watch w = Timekeeper.summarize("project1/chat-01", Timekeeper.OFF, 5 * MINUTE, true, now - 60 * MINUTE,
                now - 30 * MINUTE, List.of(), java.util.Set.of(), List.of(), now);
        assertEquals(null, w.pauseInMillis(), "with the pause off, no pause is ever due");
        Timekeeper.Watch on = Timekeeper.summarize("project1/chat-01", 10 * MINUTE, 5 * MINUTE, true, now - 60 * MINUTE,
                now - 30 * MINUTE, List.of(), java.util.Set.of(), List.of(), now);
        assertTrue(on.pauseInMillis() < 0, "with a limit, the pause is overdue");
    }

    @Test
    void cancelOfARequestThisRunDidNotSend_closesItAndTheNoticesStop(@TempDir Path dir) throws Exception {
        IIActorSystem system = new IIActorSystem("timekeeper-cancel-stale");
        try {
            long sent = System.currentTimeMillis();
            Scripted model = new Scripted(invoke("cancel_request", "requestId", STALE), "Closed.");
            Squad one = restarted(system, model, dir, sent);

            say(one, "take back the old request");
            assertTrue(model.prompts.get(1).contains("closed: request " + STALE), "the tool says it closed it: "
                    + model.prompts.get(1));
            assertEquals(List.of(), one.chat().outstandingChildrenDirect(), "the request is no longer out");

            tick(one, sent + 60 * MINUTE);
            assertEquals(0, model.notices(), "no notice for a closed request: " + model.prompts);
        } finally {
            system.terminate();
        }
    }

    @Test
    void deletingTheTarget_closesTheRequestsSentToIt(@TempDir Path dir) throws Exception {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        system.createChat("project1");                      // 02
        ChatSessionIIAR one = system.getChatSession("project1", "01");

        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("log").toString());
        SessionEntries entries = SessionEntries.ofConversation(log, IoLogView.over(log), "project1/chat-01");
        OpenRequests before = new OpenRequests();
        before.sentWithoutAnOriginal(STALE, "project1/chat-02", System.currentTimeMillis());
        before.sentWithoutAnOriginal("project1/chat-01:s46:t2482:r2", "project1/chat-03", System.currentTimeMillis());
        entries.write(ChatSession.OPEN_REQUESTS_LABEL, before.toJson().toString());
        one.tell(a -> {
            ChatSession c = (ChatSession) a;
            c.recordsTo(entries);
            c.restoreOpenRequests();
        }).get(5, TimeUnit.SECONDS);
        assertEquals(2, one.outstandingChildrenDirect().size());

        ChatUiActorSystem.ChatDeletion outcome = system.deleteChat("project1", "02");
        assertEquals(ChatUiActorSystem.ChatDeletion.Kind.DELETED, outcome.kind(), outcome.reason());

        long deadline = System.currentTimeMillis() + 10_000;
        while (one.outstandingChildrenDirect().size() > 1 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(List.of("project1/chat-01:s46:t2482:r2"), one.outstandingChildrenDirect().stream()
                .map(OpenRequests.Outstanding::requestId).toList(),
                "the request to the deleted chat-02 is closed; the one to chat-03 stays");
    }
}
