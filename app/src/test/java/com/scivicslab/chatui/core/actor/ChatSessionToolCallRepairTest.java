package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BrokenToolCallRepair_261115_oo01}: a reply that tries to call a tool but writes the call so it
 * cannot run does not end the turn as if it were the answer. The reply is kept, a note saying exactly what
 * was wrong and showing the call's exact form is sent as the next step's prompt, and the turn goes on.
 * Observed on project9/chat-01 (GLM-5.3-Flash-Uncensored) at 2026-11-15 18:25: turn34 ended with
 * "llm done: 800 chars, 0 tool call(s)", and the person was shown the unrun call as the answer.
 */
class ChatSessionToolCallRepairTest {

    /** The reply of 18:25, as the person saw it. */
    static final String REPLY_1825 = """
            おっしゃるとおりです。…まず現状の3文書を読み直して、lintに言及している箇所を特定します。

            <invoke name=“read">
            doc_Base010/docs/ProjectStandard2/030_ProgrammingProject/200_documentation/100_AntiPattern/100_DocumentStanceAndSentenceEndings_261003_oo01/100_DocumentStanceAndSentenceEndings_261003_oo01.md
            doc_Base010/.../110_RewritingWithoutChangingMeaning_261003_oo01.md
            doc_Base010/.../120_YomiyasuFindingsScope_261003_oo01.md
            120の検査手順節を確認し、lint必須規定を外す改版を当てるため
            """;

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Each sendPrompt answers with the next text in order; the last one repeats. Prompts are kept. */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<String> replies;
        final List<String> prompts = new ArrayList<>();
        ScriptedProvider(List<String> replies) { this.replies = replies; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, java.util.function.Consumer<ChatEvent> emitter, ProviderContext ctx) {
            int i = Math.min(prompts.size(), replies.size() - 1);
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(replies.get(i)));
        }
    }

    private ChatSessionIIAR newSession(ScriptedProvider provider, CapturingIoLog ioLog) {
        IIActorSystem system = new IIActorSystem("chat-session-repair-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project9", "01");
        }).join();
        return iiar;
    }

    private List<ChatEvent> runTurn(ChatSessionIIAR iiar) {
        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("read the three documents again", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return emitted;
    }

    private static String answerOf(List<ChatEvent> emitted) {
        return emitted.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::content).reduce("", (a, b) -> a + b);
    }

    @Test
    void theReplyOf1825IsRepaired_theNoteSaysWhatWasWrong_andTheRewrittenCallRuns() {
        CapturingIoLog ioLog = new CapturingIoLog();
        String rewritten = "<invoke name=\"read\">\n<parameter name=\"path\">doc_a.md\ndoc_b.md</parameter>\n"
                + "<reason>read the three documents again</reason>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(List.of(REPLY_1825, rewritten, "Read them; here is the answer."));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(3, provider.prompts.size(), "the turn went on after the broken call: " + ioLog.records);
        String note = provider.prompts.get(1);
        assertTrue(note.contains("could not be run as written, so no tool ran"), note);
        assertTrue(note.contains("is not the ASCII quote"), note);
        assertTrue(note.contains("has no <parameter> tag"), note);
        assertTrue(note.contains("one \"path\" parameter, one path per line"), note);
        assertTrue(note.contains("<invoke name=\"read\">\n<parameter name=\"path\">first/file.md\nsecond/file.md</parameter>"),
                "the note shows the exact form: " + note);

        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/repair")
                && r.content().contains("1 in a row")), "the repair is logged: " + ioLog.records);
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().equals("turn1/step1/tool")),
                "nothing in the broken reply ran");
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step2/tool")
                && r.content().contains("TOOL: read") && r.content().contains("doc_a.md\\ndoc_b.md")),
                "the rewritten call ran with both paths: " + ioLog.records);

        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("Read them; here is the answer.", history.get(history.size() - 1).content());
        assertFalse(answerOf(emitted).contains("<invoke name=“read\">") && answerOf(emitted).endsWith("当てるため\n"),
                "the broken reply is not the answer");
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void threeBrokenCallsInARowAreRepaired_aFourthEndsTheTurnKeepingTheReply() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(REPLY_1825));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(4, provider.prompts.size(), "three repairs, then the fourth broken call ends the turn");
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        String answer = history.get(history.size() - 1).content();
        assertTrue(answer.contains("lintに言及している箇所を特定します"), "the reply is kept: " + answer);
        assertTrue(answer.contains("could not be run as written 4 times in a row"), answer);
        assertEquals(4, ioLog.records.stream().filter(r -> r.label().endsWith("/repair")).count(), "each one is logged");
        assertTrue(emitted.stream().anyMatch(e -> "result".equals(e.type())), "the turn ends cleanly");
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void aCallQuotedInProseIsAnAnswer_notRepaired() {
        String prose = "A call is written as <invoke name=“read\"> with the path in a parameter tag.";
        ScriptedProvider provider = new ScriptedProvider(List.of(prose));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        runTurn(iiar);

        assertEquals(1, provider.prompts.size());
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals(prose, history.get(history.size() - 1).content());
    }

    @Test
    void theRequiredArgumentsAreTheSignatureLessTheOptionalOnes() {
        assertEquals(List.of("path"), ChatSession.requiredParameters("read"));
        assertEquals(List.of("chatId", "prompt"), ChatSession.requiredParameters("ask_chat"));
        assertEquals(List.of(), ChatSession.requiredParameters("create_chat"));
        assertEquals(List.of("chatId"), ChatSession.requiredParameters("get_chat_log"));
        assertEquals(List.of("chatId", "role", "collaboratorChatId"), ChatSession.requiredParameters("set_collaborator"),
                "node is optional: without it the role is played on this instance");
        assertEquals(List.of(), ChatSession.requiredParameters("no_such_tool"));
        // Every optional argument named is one its tool's signature has, so the table cannot drift unseen.
        ChatSession.OPTIONAL_PARAMETERS.forEach((tool, optional) -> assertTrue(
                ChatSession.signatureOf(tool).containsAll(optional), tool + " " + optional + " vs " + ChatSession.signatureOf(tool)));
    }

    @Test
    void everyLoopThatCallsTheModelHasTheRepairTransitionBeforeThinkFinal() throws Exception {
        for (String loop : List.of("chat-session-agent-loop", "plain-text-loop", "plain-text-no-context-loop",
                "doc-retrieval-prompt-loop")) {
            try (java.io.InputStream in = getClass().getResourceAsStream("/workflows/" + loop + ".yaml")) {
                String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(yaml.contains("method: repairBrokenCall"), loop);
                assertTrue(yaml.indexOf("method: repairBrokenCall") < yaml.indexOf("label: think-final"), loop);
            }
        }
    }

    // ---- SquareBracketCallRepair_261116_oo01

    /** project8/chat-01 (Claude, sonnet), 2026-11-16 10:29:50 on 28010: the reply as it was shown. */
    static final String SQUARE_REPLY_1029 = "届いたかどうかを、相手の会話のログで確かめます。\n\n"
            + "[invoke name=\"get_chat_log\"]\n"
            + "[parameter name=\"chatId\"]project1/chat-01[/parameter]\n"
            + "[parameter name=\"count\"]10[/parameter]\n"
            + "<reason>Check whether the ask_chat message arrived in project1/chat-01.</reason>\n"
            + "[/invoke]";

    private static final String REAL_CALL = "<invoke name=\"get_chat_log\">\n<parameter name=\"chatId\">project1/chat-01</parameter>\n"
            + "<parameter name=\"count\">10</parameter>\n<reason>Check whether the message arrived.</reason>\n</invoke>";

    @Test
    void aCallWrittenInTheExampleFormIsRepaired_notTakenAsTheFinalAnswer() {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(List.of(SQUARE_REPLY_1029, REAL_CALL, "The log shows it arrived."));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(3, provider.prompts.size(), "the turn went on after the example-form call: " + ioLog.records);
        String note = provider.prompts.get(1);
        assertTrue(note.contains("get_chat_log"), note);
        assertTrue(note.contains("square brackets"), note);
        assertTrue(note.contains("was not run"), note);
        assertTrue(note.contains("angle brackets"), note);
        assertTrue(note.contains("<invoke name=\"get_chat_log\">\n<parameter name=\"chatId\">"), "the exact form is shown: " + note);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/repair")), "logged: " + ioLog.records);
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().equals("turn1/step1/tool")), "the example form never runs");
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step2/tool") && r.content().contains("TOOL: get_chat_log")),
                "the rewritten call ran: " + ioLog.records);
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("The log shows it arrived.", history.get(history.size() - 1).content());
        assertFalse(answerOf(emitted).contains("[invoke name=\"get_chat_log\"]"), "the example form was not the answer");
    }

    @Test
    void aCallGluedToTheEndOfTheSentenceBeforeItRuns() {
        CapturingIoLog ioLog = new CapturingIoLog();
        String reply = "式を確かめます。<invoke name=\"calc\">\n<parameter name=\"expression\">1+1</parameter>\n"
                + "<reason>check</reason>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(List.of(reply, "The result is 2."));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        runTurn(iiar);

        assertEquals(2, provider.prompts.size(), "the call was run and the turn went on: " + ioLog.records);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/tool") && r.content().contains("TOOL: calc")),
                "the call ran: " + ioLog.records);
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("The result is 2.", history.get(history.size() - 1).content());
    }

    @Test
    void plainTextThenACallOnTheLinesBelowItRuns() {
        CapturingIoLog ioLog = new CapturingIoLog();
        String reply = "式を確かめます。\n\n<invoke name=\"calc\">\n<parameter name=\"expression\">1+1</parameter>\n"
                + "<reason>check</reason>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(List.of(reply, "The result is 2."));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        runTurn(iiar);

        assertEquals(2, provider.prompts.size(), ioLog.records.toString());
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/tool") && r.content().contains("TOOL: calc")));
    }

    @Test
    void anExampleFormBlockInACodeFenceIsAnAnswer() {
        String reply = "To show a call without running it, write:\n```\n" + SQUARE_REPLY_1029 + "\n```\nThat is all.";
        ScriptedProvider provider = new ScriptedProvider(List.of(reply));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        runTurn(iiar);

        assertEquals(1, provider.prompts.size());
    }
}
