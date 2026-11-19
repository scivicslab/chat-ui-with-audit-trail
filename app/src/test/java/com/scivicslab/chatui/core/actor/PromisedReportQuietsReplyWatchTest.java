package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * While an expect_report reminder is pending for a request the asker sent, the asker's timekeeper sends no
 * "no reply yet" notice for it; the notices come back, at the normal interval, after the reminder's follow-up was read
 * by a turn that registered no new expect_report ({@code AutomaticReplyDecision_261119_oo01}). On 28010, chat-01
 * registered a 60-minute reminder on request s47:t1378:r1 and was still told "no reply yet" 16 minutes after sending it.
 */
@Tag("AutomaticReplyDecision_261119_oo01")
class PromisedReportQuietsReplyWatchTest {

    private static final long MINUTE = 60_000L;

    /** chat-01's model: asks chat-02, then registers chat-02's promised report, and otherwise just notes things. */
    private static final class Asker implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        volatile String request;
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String m) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            String say;
            Matcher sent = Pattern.compile("as request (\\S+?)[;,]").matcher(prompt);
            if (sent.find()) {
                request = sent.group(1);
                say = "Sent.";
            } else if (prompt.contains("will remind it in")) {
                say = "Registered.";
            } else if (prompt.endsWith("please ask chat-02 to render")) {
                say = "<invoke name=\"ask_chat\">\n<parameter name=\"chatId\">02</parameter>\n"
                        + "<parameter name=\"prompt\">render the video</parameter>\n<reason>t</reason>\n</invoke>";
            } else if (prompt.endsWith("chat-02 says it will report in an hour")) {
                say = "<invoke name=\"expect_report\">\n<parameter name=\"chatId\">02</parameter>\n<parameter name=\"requestId\">"
                        + request + "</parameter>\n<parameter name=\"minutes\">60</parameter>\n<reason>t</reason>\n</invoke>";
            } else {
                say = "Noted.";
            }
            emitter.accept(ChatEvent.delta(say));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
        long notices() {
            return prompts.stream().filter(p -> p.contains("[no reply yet to request " + request)).count();
        }
    }

    /** chat-02's model: in a long render, longer than the test. */
    private static final class Busy implements LlmProvider {
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String m) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private record Squad(ChatSessionIIAR chat, ActorRef<PromptQueue> queue, ActorRef<Timekeeper> keeper) {}

    private static Squad squad(IIActorSystem system, String chatId, LlmProvider provider) {
        String name = "project1/chat-" + chatId;
        ChatSessionIIAR iiar = new ChatSessionIIAR(name + ".chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
            c.setReminderDecisionEnabled(false);
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild(name + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        ActorRef<Timekeeper> keeper = iiar.createChild(name + ".timekeeper",
                new Timekeeper(name, iiar, queue, e -> { }, Long.MAX_VALUE, 5 * MINUTE));
        return new Squad(iiar, queue, keeper);
    }

    private static void say(Squad squad, String text) throws Exception {
        squad.queue().ask(q -> {
            q.enqueue(text, null, "queue", e -> { }, squad.chat().asChatSessionRef(), "human");
            return null;
        }).get(5, TimeUnit.SECONDS);
        settle(squad);
    }

    /** Waits until the conversation has nothing running and nothing queued. */
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

    @Test
    void pendingExpectReport_quietsTheReplyWatchUntilItsFollowUpIsRead() throws Exception {
        IIActorSystem system = new IIActorSystem("promised-report-quiets-watch");
        try {
            Asker model = new Asker();
            Squad one = squad(system, "01", model);
            squad(system, "02", new Busy());
            say(one, "please ask chat-02 to render");
            assertTrue(model.request != null, "chat-01 sent the request");
            String request = model.request;
            long sent = System.currentTimeMillis();
            say(one, "chat-02 says it will report in an hour");

            // Watch rounds and turn ends before the reminder's time: no notice.
            tick(one, sent + 6 * MINUTE);
            say(one, "anything new?");
            tick(one, sent + 16 * MINUTE);
            say(one, "and now?");
            tick(one, sent + 59 * MINUTE);
            assertEquals(0, model.notices(), "no 'no reply yet' notice while the reminder is pending: " + model.prompts);

            // The reminder's answer comes back as a follow-up; chat-01 reads it and registers nothing new.
            AskChatTool.followUp(system, "project1/chat-02", "project1/chat-01", request,
                    new TurnReply("Still rendering, frame 1200 of 2592.", null, "project1/chat-02:t2"), 1);
            settle(one);
            long read = System.currentTimeMillis();

            tick(one, read + 4 * MINUTE);
            assertEquals(0, model.notices(), "the clock starts again from the follow-up");
            tick(one, read + 6 * MINUTE);
            assertEquals(1, model.notices(), "after the follow-up, the notices come back at the normal interval");
        } finally {
            system.terminate();
        }
    }
}
