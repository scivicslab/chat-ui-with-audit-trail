package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A turn that began with a reply and ends normally without a decision on it gets one automatically, accept_reply,
 * decided from facts only: the request id, the reply's ended line and the tools the turn called
 * ({@code AutomaticReplyDecision_261119_oo01}). A reply whose sender asked a question or ran out of steps is left
 * open with a warning instead.
 */
@Tag("AutomaticReplyDecision_261119_oo01")
class AutomaticReplyDecisionTest {

    private static final String REQUEST = "project1/chat-02:s1:t1:r1";

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new CopyOnWriteArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 5L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
        List<String> decisions() {
            return records.stream().filter(r -> r.label().endsWith("/reply-decision")).map(Rec::content).toList();
        }
    }

    /** Says the scripted things in order, the last one again once the script runs out, and records every prompt. */
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

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("<reason>test</reason>\n</invoke>").toString();
    }

    private static ChatSessionIIAR chat(IIActorSystem system, String chatId, LlmProvider provider, IoLogStore ioLog) {
        String name = "project1/chat-" + chatId;
        ChatSessionIIAR iiar = new ChatSessionIIAR(name + ".chat", provider, Optional.empty(), ioLog, system);
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
        iiar.createChild(name + ".timekeeper", new Timekeeper(name, iiar, queue, e -> { }, 600_000, 300_000));
        return iiar;
    }

    /**
     * chat-02, which sent {@code REQUEST} to chat-03, gets {@code message} about it; its model writes what
     * {@code model} says. Returns chat-02's reply-decision records once its turn has ended.
     */
    private static List<String> decisionsAfter(Scripted model, Consumer<IIActorSystem> deliver) throws Exception {
        IIActorSystem system = new IIActorSystem("automatic-reply-decision");
        try {
            CapturingIoLog log = new CapturingIoLog();
            ChatSessionIIAR two = chat(system, "02", model, log);
            // chat-03 never answers within the test, so chat-02 has exactly one turn to decide in.
            chat(system, "03", new Scripted("unused") {
                @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) {
                    try { Thread.sleep(30_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            }, null);
            deliver.accept(system);
            long deadline = System.currentTimeMillis() + 20_000;
            while ((model.prompts.isEmpty() || two.isBusyDirect()) && System.currentTimeMillis() < deadline) Thread.sleep(50);
            Thread.sleep(300);
            assertTrue(!model.prompts.isEmpty() && !two.isBusyDirect(), "chat-02's turn ended");
            return log.decisions();
        } finally {
            system.terminate();
        }
    }

    private static Consumer<IIActorSystem> reply(String ended) {
        return system -> AskChatTool.deliverReplyTo(system, "project1/chat-02", "project1/chat-03", REQUEST, false,
                new TurnReply("Here is the report.", "[ended: " + ended + "; model calls: 1; tool calls: 0]",
                        "project1/chat-03:t1"), null);
    }

    @Test
    void replyEndedAnswered_noDecisionRecorded_isAcceptedAutomatically() throws Exception {
        List<String> decisions = decisionsAfter(new Scripted("Thanks.", "Thanks again."), reply("answered"));
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("AUTO ACCEPT | " + REQUEST + " | "), decisions.get(0));
    }

    @Test
    void followUpTurnWithoutANewExpectReport_isAcceptedAutomatically() throws Exception {
        List<String> decisions = decisionsAfter(new Scripted("Noted.", "Noted again."),
                system -> AskChatTool.followUp(system, "project1/chat-03", "project1/chat-02", REQUEST,
                        new TurnReply("Still rendering, about 20 more minutes.", null, "project1/chat-03:t2"), 1));
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("AUTO ACCEPT | " + REQUEST + " | "), decisions.get(0));
    }

    @Test
    void endedWithoutAReply_isClosedByAnAutomaticAccept() throws Exception {
        List<String> decisions = decisionsAfter(new Scripted("I see.", "I see."),
                system -> AskChatTool.deliverReplyTo(system, "project1/chat-02", "project1/chat-03", REQUEST, false,
                        new TurnReply(null, "the instance restarted during its turn", null), null));
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("AUTO ACCEPT | " + REQUEST + " | "), decisions.get(0));
    }

    @Test
    void replyEndedAsked_isLeftOpenWithAWarning() throws Exception {
        List<String> decisions = decisionsAfter(new Scripted("Hmm.", "Hmm."), reply("asked"));
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("LEFT OPEN | " + REQUEST + " | "), decisions.get(0));
        assertTrue(decisions.get(0).contains("asked"), decisions.get(0));
    }

    @Test
    void replyEndedStepLimit_isLeftOpenWithAWarning() throws Exception {
        List<String> decisions = decisionsAfter(new Scripted("Hmm.", "Hmm."), reply("step-limit"));
        assertEquals(1, decisions.size(), decisions.toString());
        assertTrue(decisions.get(0).startsWith("LEFT OPEN | " + REQUEST + " | "), decisions.get(0));
        assertTrue(decisions.get(0).contains("step-limit"), decisions.get(0));
    }

    /** Every bundled agent loop settles the reply in each transition that ends a turn, just before finish. */
    @Test
    void bundledAgentLoops_settleTheReplyInEveryTransitionThatEndsATurn() throws Exception {
        for (String loop : List.of("chat-session-agent-loop", "plain-text-loop", "plain-text-no-context-loop",
                "doc-retrieval-prompt-loop")) {
            String yaml;
            try (java.io.InputStream in = AutomaticReplyDecisionTest.class.getResourceAsStream("/workflows/" + loop + ".yaml")) {
                yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String settleThenFinish = "method: decideReplyAtTurnEnd\n        execution: direct\n      - actor: this\n        method: finish";
            assertEquals(3, yaml.split(java.util.regex.Pattern.quote(settleThenFinish), -1).length - 1,
                    loop + ": step-limit, think-asks-person and think-final");
        }
    }

    @Test
    void turnThatCalledAcceptReply_getsNoAutomaticDecision() throws Exception {
        assertEquals(List.of(), decisionsAfter(new Scripted(invoke("accept_reply", "requestId", REQUEST), "Done."),
                reply("answered")));
    }

    @Test
    void turnThatCalledExpectReport_getsNoAutomaticDecision() throws Exception {
        assertEquals(List.of(), decisionsAfter(new Scripted(
                invoke("expect_report", "chatId", "03", "requestId", REQUEST, "minutes", "30"), "Registered."),
                reply("answered")));
    }

    @Test
    void turnThatAskedTheReplierAgain_getsNoAutomaticDecision() throws Exception {
        assertEquals(List.of(), decisionsAfter(new Scripted(
                invoke("ask_chat", "chatId", "03", "prompt", "please fix the caption"), "Asked for a fix."),
                reply("answered")));
    }
}
