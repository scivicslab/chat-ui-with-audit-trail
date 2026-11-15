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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CutOffReplyContinues_261115_oo01}: a reply the server stopped at the output limit
 * ({@code finish_reason=length}, reported by the provider as a {@code cut_off} event) does not fail the
 * turn. The partial text is kept, no tool in it runs, a note is sent as the next step's prompt, and the
 * turn goes on. Observed on GLM-5.3-Flash-Uncensored: a step wrote 83,344 characters, hit the server's
 * 32,768-token budget, and the whole turn failed with the text thrown away.
 */
class ChatSessionCutOffTest {

    private static final class CapturingIoLog extends IoLogStore {
        record Rec(String label, String content) {}
        final List<Rec> records = new ArrayList<>();
        @Override public synchronized long ensureSession(String conversationSquadId) { return 7L; }
        @Override public void record(long sessionId, String node, String label, String content) {
            records.add(new Rec(label, content));
        }
    }

    /** Each {@code sendPrompt} plays the next script in order; the last one repeats. Prompts are kept. */
    private static final class ScriptedProvider implements LlmProvider {
        private final List<Consumer<Consumer<ChatEvent>>> scripts;
        final List<String> prompts = new ArrayList<>();
        final List<Boolean> handlesCutOff = new ArrayList<>();
        ScriptedProvider(List<Consumer<Consumer<ChatEvent>>> scripts) { this.scripts = scripts; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            int i = Math.min(prompts.size(), scripts.size() - 1);
            prompts.add(prompt);
            handlesCutOff.add(ctx.handlesCutOff());
            scripts.get(i).accept(emitter);
        }
    }

    /** Writes {@code text}, then the server stops at the limit. */
    private static Consumer<Consumer<ChatEvent>> cutOffAfter(String text) {
        return emitter -> {
            if (!text.isEmpty()) emitter.accept(ChatEvent.delta(text));
            emitter.accept(ChatEvent.cutOff(String.valueOf(text.length())));
        };
    }

    private static Consumer<Consumer<ChatEvent>> answers(String text) {
        return emitter -> emitter.accept(ChatEvent.delta(text));
    }

    private ChatSessionIIAR newSession(ScriptedProvider provider, CapturingIoLog ioLog) throws Exception {
        IIActorSystem system = new IIActorSystem("chat-session-cut-off-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), ioLog, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ((ChatSession) a).setProviderName(providerRef.getName());
            ((ChatSession) a).setChatIdentity("project1", "01");
        }).join();
        return iiar;
    }

    private List<ChatEvent> runTurn(ChatSessionIIAR iiar) throws Exception {
        return runTurn(iiar, null);
    }

    /** Runs a turn under the named workflow from the test resources, or the default loop when null. */
    private List<ChatEvent> runTurn(ChatSessionIIAR iiar, String agentLoopYaml) throws Exception {
        if (agentLoopYaml != null) {
            iiar.tellNow(a -> {
                ChatSession c = (ChatSession) a;
                c.reset();
                try (java.io.InputStream in = ChatSessionCutOffTest.class.getResourceAsStream("/workflows/" + agentLoopYaml)) {
                    c.readYaml(in);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).join();
        }
        List<ChatEvent> emitted = new ArrayList<>();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("write a long document", null, emitted::add, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        return emitted;
    }

    @Test
    void cutOffOnce_thePartialIsKeptANoteIsSentAndTheTurnContinuesToAnAnswer() throws Exception {
        CapturingIoLog ioLog = new CapturingIoLog();
        // The cut-off text ends inside a tool call that never closed.
        String partial = "Starting the file.\n<invoke name=\"write\">\n<parameter name=\"path\">/tmp/a.md</parameter>\n<parameter name=\"content\">chapter one";
        ScriptedProvider provider = new ScriptedProvider(List.of(cutOffAfter(partial), answers("Done in pieces.")));
        ChatSessionIIAR iiar = newSession(provider, ioLog);

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(2, provider.prompts.size(), "the turn went on after the cut-off: " + emitted + ioLog.records);
        assertTrue(provider.handlesCutOff.stream().allMatch(b -> b), "the agent loop asks the provider to hand cut-offs back");
        String note = provider.prompts.get(1);
        assertTrue(note.contains("cut off at the output limit"), note);
        assertTrue(note.contains(partial.length() + " characters"), "says how much was written: " + note);
        assertTrue(note.contains("several file writes") && note.contains("smaller pieces"), note);

        assertTrue(emitted.stream().noneMatch(e -> "error".equals(e.type())), "a cut-off is not an error: " + emitted);
        assertTrue(ioLog.records.stream().noneMatch(r -> r.label().endsWith("/tool")),
                "the unfinished tool call was not run: " + ioLog.records);
        assertTrue(ioLog.records.stream().anyMatch(r -> r.label().equals("turn1/step1/llm") && r.content().contains("chapter one")),
                "the partial text is recorded, not discarded");

        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("Done in pieces.", history.get(history.size() - 1).content());
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void cutOffBeforeAnyText_theNoteSaysTheLimitWasSpentThinking() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(cutOffAfter(""), answers("ok")));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        runTurn(iiar);

        assertEquals(2, provider.prompts.size(), "an empty cut-off is not retried as an empty stop");
        assertTrue(provider.prompts.get(1).contains("spent on thinking"), provider.prompts.get(1));
    }

    @Test
    void cutOffTwiceThenAnAnswer_stillContinues() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                cutOffAfter("part one "), cutOffAfter("part two "), answers("finished")));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(3, provider.prompts.size());
        assertTrue(emitted.stream().noneMatch(e -> "error".equals(e.type())));
    }

    @Test
    void cutOffThreeTimesInARow_isStillContinued_aFourthEndsTheTurnKeepingWhatWasWritten() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(cutOffAfter("chunk ")));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(4, provider.prompts.size(), "three cut-offs are continued, the fourth ends the turn");
        String answer = emitted.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::content)
                .reduce("", (a, b) -> a + b);
        assertTrue(answer.contains("chunk chunk chunk chunk"), "what was written is kept: " + answer);
        assertTrue(answer.contains("cut off at the output limit 4 times in a row"), answer);
        assertTrue(emitted.stream().anyMatch(e -> "result".equals(e.type())), "the turn ends cleanly");
        assertFalse(iiar.isBusyDirect());
    }

    @Test
    void aStepThatFinishesResetsTheCount() throws Exception {
        // cut, cut, a normal step with a tool call that cannot run (unknown tool: an observation, not a failure),
        // then cut, cut, answer: six steps would exceed three only if the count had not been reset.
        String toolCall = "<invoke name=\"calc\">\n<parameter name=\"expression\">1+1</parameter>\n</invoke>";
        ScriptedProvider provider = new ScriptedProvider(List.of(
                cutOffAfter("a "), cutOffAfter("b "), answers(toolCall),
                cutOffAfter("c "), cutOffAfter("d "), answers("final")));
        ChatSessionIIAR iiar = newSession(provider, new CapturingIoLog());

        List<ChatEvent> emitted = runTurn(iiar);

        assertEquals(6, provider.prompts.size(), "the count started again after the step that finished");
        List<ChatSession.HistoryEntry> history = iiar.getHistorySnapshotDirect();
        assertEquals("final", history.get(history.size() - 1).content());
        assertTrue(emitted.stream().noneMatch(e -> "error".equals(e.type())));
    }

    @Test
    void everyLoopThatCallsTheModelHasTheCutOffTransition() throws Exception {
        // A loop without it would end a cut-off turn silently: the provider no longer reports an error.
        for (String loop : List.of("chat-session-agent-loop", "plain-text-loop", "plain-text-no-context-loop",
                "doc-retrieval-prompt-loop")) {
            try (var in = getClass().getResourceAsStream("/workflows/" + loop + ".yaml")) {
                String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(yaml.contains("method: continueAfterCutOff"), loop);
                assertTrue(yaml.indexOf("method: continueAfterCutOff") < yaml.indexOf("label: think-final"), loop);
            }
        }
    }

    // The number in the YAML reaches the action as an integer: these run the real YAML through the interpreter,
    // and the limit they name differs from the Java default (3) in each direction.
    @Test
    void theLimitIsTheNumberTheYamlNames_lowerThanTheDefault() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(cutOffAfter("chunk ")));
        runTurn(newSession(provider, new CapturingIoLog()), "cut-off-limit-1.yaml");
        assertEquals(2, provider.prompts.size(), "maxInARow: 1 continues once, the second cut-off ends the turn");
    }

    @Test
    void theLimitIsTheNumberTheYamlNames_higherThanTheDefault() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(List.of(cutOffAfter("chunk ")));
        runTurn(newSession(provider, new CapturingIoLog()), "cut-off-limit-5.yaml");
        assertEquals(6, provider.prompts.size(), "maxInARow: 5 continues five times, the sixth cut-off ends the turn");
    }
}
