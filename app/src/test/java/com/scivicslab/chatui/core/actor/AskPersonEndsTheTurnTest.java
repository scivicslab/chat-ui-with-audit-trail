package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AskThePersonEndsTheTurn_261119_oo01}: a reply that calls {@code ask_person} ends the turn, with the
 * question as its answer, and no other call in that reply runs. Observed on project9/chat-01
 * (GLM-5.3-Flash-Uncensored), session 140, turn 44, step 6: the reply asked the person "どうしますか？ A/B/C"
 * and appended a bash call in GLM's spelling; the loop ran the call and the turn went on past 29 steps.
 */
class AskPersonEndsTheTurnTest {

    /** Step 6 of turn 44, as GLM wrote it: the question to the person, then a bash call glued to it. */
    static final String STEP6 = resource("/replies/glm-turn44-step6.txt");

    /** The question that closes step 6's prose. */
    static final String QUESTION = "どうしますか？ A/B/C、または章立て自体の修正からでもどうぞ。";

    /** The bash call step 6 appended, exactly as written. */
    static final String BASH_CALL = STEP6.substring(STEP6.indexOf("<tool_call>bash"));

    /** Step 6 as GLM would write it under the new instruction: the question asked with ask_person, the bash call still there. */
    static final String STEP6_WITH_ASK_PERSON = STEP6.replace(BASH_CALL,
            "<tool_call>ask_person<arg_key>question</arg_key><arg_value>" + QUESTION + "</arg_value></tool_call>"
                    + BASH_CALL);

    private static String resource(String path) {
        try (InputStream in = AskPersonEndsTheTurnTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new CopyOnWriteArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Each sendPrompt answers with the next text in order; the last one repeats. Prompts are kept. */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<String> replies;
        final List<String> prompts = new CopyOnWriteArrayList<>();
        ScriptedProvider(String... replies) { this.replies = List.of(replies); }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            int i = Math.min(prompts.size(), replies.size() - 1);
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(replies.get(i)));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static ChatSessionIIAR newSession(IIActorSystem system, String name, String chatId,
                                              ScriptedProvider provider, CapturingIoLog ioLog) {
        ChatSessionIIAR iiar = new ChatSessionIIAR(name, provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project9", chatId);
        }).join();
        return iiar;
    }

    private static ChatSessionIIAR newSession(ScriptedProvider provider, CapturingIoLog ioLog) {
        return newSession(new IIActorSystem("ask-person-test"), "chat", "01", provider, ioLog);
    }

    private record Turn(List<ChatEvent> emitted, TurnReply reply) {
        String answer() {
            return emitted.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::content).reduce("", String::concat);
        }
    }

    private static Turn runTurn(ChatSessionIIAR iiar, String source, String requestId) throws Exception {
        List<ChatEvent> emitted = new CopyOnWriteArrayList<>();
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            // A key, as PromptQueue always gives one: done carries the answer and its ending under it.
            c.start("write the TeXbook of fcitx5", null, emitted::add, chatRef, done,
                    "result-" + System.nanoTime(), false, List.of(), source, requestId);
            c.runUntilEnd();
        }).join();
        return new Turn(emitted, done.get(5, TimeUnit.SECONDS));
    }

    private static Turn runTurn(ChatSessionIIAR iiar) throws Exception {
        return runTurn(iiar, "screen", null);
    }

    @Test
    void step6WithAskPersonEndsTheTurn_theBashCallDoesNotRun_andTheAnswerEndsWithTheQuestion() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(STEP6_WITH_ASK_PERSON, "Started on chapter 1.");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        Turn turn = runTurn(iiar);

        assertEquals(1, provider.prompts.size(), "no step after the question: " + ioLog.records);
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().endsWith("/tool")), "bash did not run: " + ioLog.records);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/ask-person")
                && r.content().contains("QUESTION: " + QUESTION) && r.content().contains("NOT RUN: bash")),
                "the dropped call is recorded: " + ioLog.records);
        String answer = turn.answer();
        assertTrue(answer.strip().endsWith(QUESTION), "the answer ends with the question: " + answer);
        assertTrue(answer.contains("私のおすすめは**C案**です"), "the prose before the question is kept: " + answer);
        assertFalse(answer.contains("<tool_call>") || answer.contains("arg_key"), "no call markup in the answer: " + answer);
        assertEquals(1, answer.split(java.util.regex.Pattern.quote(QUESTION), -1).length - 1,
                "the question, already in the prose, is not added twice");
        assertTrue(turn.reply().ending().startsWith("[ended: asked;"), turn.reply().ending());
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void aReplyThatIsOnlyAnInvokeOfAskPersonEndsTheTurnWithTheQuestionAsTheAnswer() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        String ask = "<invoke name=\"ask_person\">\n<parameter name=\"question\">Write chapter 1 first, or all six?</parameter>\n"
                + "<reason>the order is the person's choice</reason>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(ask, "unused");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        Turn turn = runTurn(iiar);

        assertEquals(1, provider.prompts.size());
        assertEquals("Write chapter 1 first, or all six?", turn.answer().strip());
        assertEquals("Write chapter 1 first, or all six?", iiar.getHistorySnapshotDirect()
                .get(iiar.getHistorySnapshotDirect().size() - 1).content());
    }

    @Test
    void askPersonFirstAndAReadAfterIt_inInvokeForm_runsNothing() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        String reply = "Two ways are open.\n\n<invoke name=\"ask_person\">\n<parameter name=\"question\">A or B?</parameter>\n</invoke>\n"
                + "<invoke name=\"read\">\n<parameter name=\"path\">no-such-file</parameter>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(reply, "unused");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        Turn turn = runTurn(iiar);

        assertEquals(1, provider.prompts.size());
        assertEquals("Two ways are open.\n\nA or B?", turn.answer().strip());
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/ask-person")
                && r.content().contains("NOT RUN: read")), ioLog.records.toString());
    }

    @Test
    void askPersonWithoutAQuestionIsABrokenCall_andTheTurnGoesOnToRepairIt() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider("<tool_call>ask_person</tool_call>", "Finished without asking.");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        Turn turn = runTurn(iiar);

        assertEquals(2, provider.prompts.size(), "the call without a question was sent back for repair");
        assertTrue(provider.prompts.get(1).contains("question"), provider.prompts.get(1));
        assertEquals("Finished without asking.", turn.answer().strip());
    }

    @Test
    void aTurnThatAnswersARequestRepliesWithTheQuestion_endedAsked() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(STEP6_WITH_ASK_PERSON);
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        Turn turn = runTurn(iiar, ChatSession.ASK_CHAT_SOURCE_PREFIX + "project9/chat-02", "project9/chat-02:t3:r1");

        assertNotNull(turn.reply().reply());
        assertTrue(turn.reply().reply().strip().endsWith(QUESTION), turn.reply().reply());
        assertTrue(turn.reply().ending().contains("ended: asked"), turn.reply().ending());
    }

    @Test
    void withoutAskPerson_step6AsWrittenStillRunsItsBashCall_onlyTheInstructionGuardsThatCase() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(STEP6, "Done.");
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        runTurn(iiar);

        assertEquals(2, provider.prompts.size(), "the loop does not read a question from prose");
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/tool")
                && r.content().contains("TOOL: bash")), ioLog.records.toString());
    }

    @Test
    void theInstructionComesBeforeTheToolList_andBothToolSetsHaveAskPerson() {
        for (ToolSet set : ToolSet.values()) {
            ChatSessionIIAR iiar = newSession(new ScriptedProvider("x"), new CapturingIoLog());
            String text = iiar.asChatSessionRef().ask(c -> {
                c.setToolSet(set);
                return c.toolInstructionsText();
            }).join();
            int rule = text.indexOf("Asking the person ends your turn.");
            int list = text.indexOf("Available tools:");
            assertTrue(rule > 0 && rule < list, set + ": the rule is read before the tool list");
            assertTrue(text.contains("put no other tool call in that reply"), set.toString());
            assertTrue(text.indexOf("- ask_person(question):") > list, set + ": ask_person is in the list");
        }
    }

    @Test
    void aLoopWithoutThinkAsksPersonStillEndsTheTurnAtTheQuestion() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider provider = new ScriptedProvider(STEP6_WITH_ASK_PERSON, "unused");
        ChatSessionIIAR iiar = newSession(provider, ioLog);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.reset();
            try (InputStream in = AskPersonEndsTheTurnTest.class.getResourceAsStream("/workflows/step-limit-2.yaml")) {
                c.readYaml(in);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).join();

        Turn turn = runTurn(iiar);

        assertEquals(1, provider.prompts.size());
        assertTrue(turn.answer().strip().endsWith(QUESTION), turn.answer());
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().endsWith("/tool")));
    }

    @Test
    void everyBundledAgentLoopEndsTheTurnAtAQuestion() {
        for (String loop : List.of("chat-session-agent-loop.yaml", "plain-text-loop.yaml",
                "plain-text-no-context-loop.yaml", "doc-retrieval-prompt-loop.yaml")) {
            String yaml = resource("/workflows/" + loop);
            int action = yaml.indexOf("label: think-action");
            int asks = yaml.indexOf("label: think-asks-person");
            int cutOff = yaml.indexOf("label: think-cut-off");
            assertTrue(action > 0 && asks > action && asks < cutOff, loop + ": think-asks-person follows think-action");
            String block = yaml.substring(asks, cutOff);
            assertTrue(block.contains("method: askedThePerson") && block.contains("method: finish"), loop);
        }
    }

    /** A {@link ConversationControls} that knows only who the superior is. */
    private static ConversationControls controlsWithSuperior(String superior) {
        return (ConversationControls) Proxy.newProxyInstance(ConversationControls.class.getClassLoader(),
                new Class<?>[]{ConversationControls.class},
                (proxy, method, args) -> "superiorToAsk".equals(method.getName()) ? superior : null);
    }

    @Test
    void aWorkerTurnThatAnswersNoRequest_sendsTheQuestionToItsSuperior() throws Exception {
        IIActorSystem system = new IIActorSystem("ask-person-superior-test");
        ScriptedProvider bossModel = new ScriptedProvider("Take C.");
        ChatSessionIIAR boss = newSession(system, "project9/chat-01.chat", "01", bossModel, new CapturingIoLog());
        ActorRef<PromptQueue> bossQueue = boss.createChild("project9/chat-01.queue", new PromptQueue());
        bossQueue.tellNow(q -> q.setSelf(bossQueue)).join();

        CapturingIoLog ioLog = new CapturingIoLog();
        ScriptedProvider workerModel = new ScriptedProvider(STEP6_WITH_ASK_PERSON, "Going ahead with C.");
        ChatSessionIIAR worker = newSession(system, "project9/chat-02.chat", "02", workerModel, ioLog);
        worker.tellNow(a -> ((ChatSession) a).setConversationControls(controlsWithSuperior("01"))).join();
        ActorRef<PromptQueue> workerQueue = worker.createChild("project9/chat-02.queue", new PromptQueue());
        workerQueue.tellNow(q -> q.setSelf(workerQueue)).join();

        Turn turn = runTurn(worker, Timekeeper.SOURCE, null);

        assertTrue(turn.reply().ending().contains("ended: asked"), turn.reply().ending());
        long deadline = System.currentTimeMillis() + 10_000;
        while (workerModel.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertEquals(1, bossModel.prompts.size(), "the superior got the question as one request");
        String got = bossModel.prompts.get(0);
        assertTrue(got.contains("[question from project9/chat-02") && got.contains(QUESTION), got);
        // The next turn begins with the superior's answer (and goes on to record a decision on it, as any reply turn does).
        assertTrue(workerModel.prompts.size() >= 2, "the worker's turn ended at the question; the answer began the next");
        assertTrue(workerModel.prompts.get(1).contains("Take C."), "the superior's answer came back: " + workerModel.prompts.get(1));
    }

    @Test
    void aWorkerTurnThePersonBegan_keepsTheQuestionOnItsOwnScreen() throws Exception {
        IIActorSystem system = new IIActorSystem("ask-person-screen-test");
        ScriptedProvider bossModel = new ScriptedProvider("unused");
        ChatSessionIIAR boss = newSession(system, "project9/chat-01.chat", "01", bossModel, new CapturingIoLog());
        ActorRef<PromptQueue> bossQueue = boss.createChild("project9/chat-01.queue", new PromptQueue());
        bossQueue.tellNow(q -> q.setSelf(bossQueue)).join();
        ChatSessionIIAR worker = newSession(system, "project9/chat-02.chat", "02",
                new ScriptedProvider(STEP6_WITH_ASK_PERSON), new CapturingIoLog());
        worker.tellNow(a -> ((ChatSession) a).setConversationControls(controlsWithSuperior("01"))).join();

        Turn turn = runTurn(worker, "screen", null);

        assertTrue(turn.answer().strip().endsWith(QUESTION));
        Thread.sleep(300);
        assertTrue(bossModel.prompts.isEmpty(), "the person who began the turn reads the question");
    }
}
