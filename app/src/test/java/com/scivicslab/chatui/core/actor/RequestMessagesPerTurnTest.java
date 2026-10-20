package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.plugins.promptbuilder.PromptBuilderActor;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A prompt construction that builds the request messages itself: what the request carries is the
 * sub-workflow's choice, for that turn only; what the conversation keeps is the question and the
 * answer ({@code AgentLoopDecidesThePrompt_261020_oo01}).
 */
@DisplayName("リクエストに載せるメッセージはターンごとにワークフローが決め、残るのは質問と答えだけ")
class RequestMessagesPerTurnTest {

    /** Keeps request messages the way the OpenAI-compatible provider does, and records each request as sent. */
    private static final class MessagesProvider implements LlmProvider {
        final List<JSONObject> kept = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        int collapsed = 0;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override public String requestMessagesJson() { return new JSONArray(kept).toString(); }

        @Override public String replaceRequestMessages(String json) {
            kept.clear();
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) kept.add(a.getJSONObject(i));
            collapsed = kept.size();
            return null;
        }

        @Override public void collapseTurn(String question, String answer) {
            while (kept.size() > collapsed) kept.remove(kept.size() - 1);
            kept.add(new JSONObject().put("role", "user").put("content", question));
            if (answer != null) kept.add(new JSONObject().put("role", "assistant").put("content", answer));
            collapsed = kept.size();
        }

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            kept.add(new JSONObject().put("role", "user").put("content", prompt));
            requests.add(requestMessagesJson());
            String answer = "answer " + requests.size();
            kept.add(new JSONObject().put("role", "assistant").put("content", answer));
            emitter.accept(ChatEvent.delta(answer));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static final class Conversation {
        final MessagesProvider provider = new MessagesProvider();
        final ChatSessionIIAR iiar;

        Conversation(String loop) {
            IIActorSystem system = new IIActorSystem("request-messages-per-turn-test");
            iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
            system.addIIActor(iiar);
            ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
            iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
            PromptBuilderActor promptBuilder = new PromptBuilderActor(iiar.getName() + ".promptBuilder", system);
            promptBuilder.setParentName(iiar.getName());
            iiar.getNamesOfChildren().add(promptBuilder.getName());
            system.addIIActor(promptBuilder);
            if (loop != null) {
                String refused = iiar.setAgentLoopWorkflowFile(loop);
                if (refused != null) throw new IllegalStateException(refused);
            }
        }

        final List<String> errors = new ArrayList<>();

        void ask(String question) {
            int before = provider.requests.size();
            ActorRef<ChatSession> ref = iiar.asChatSessionRef();
            ref.tellNow(c -> {
                c.start(question, null, event -> {
                    if ("error".equals(event.type())) errors.add(String.valueOf(event.content()));
                }, ref, new CompletableFuture<>(), null, false);
                c.runUntilEnd();
            }).join();
            assertEquals(before + 1, provider.requests.size(), "the turn sent no request; errors: " + errors);
            // the provider restores and collapses by messages told to its own actor; wait for them
            ref.ask(c -> c.requestMessagesJson()).join();
            iiar.asChatSessionRef().ask(c -> c.requestMessagesJson()).join();
        }

        /** @return role:content of each kept message, joined */
        String kept() {
            List<String> out = new ArrayList<>();
            for (JSONObject m : provider.kept) out.add(m.getString("role") + ":" + m.getString("content"));
            return String.join(" | ", out);
        }
    }

    private static String compact(String requestJson) {
        JSONArray a = new JSONArray(requestJson);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("role") + ":" + a.getJSONObject(i).getString("content"));
        return String.join(" | ", out);
    }

    @Test
    @DisplayName("文脈なしのループ: 各リクエストは質問だけで、会話が覚えているのは質問と答えだけ")
    void noContext_everyRequestIsTheQuestionAlone() {
        Conversation c = new Conversation("plain-text-no-context-loop.yaml");

        c.ask("first");
        c.ask("second");

        assertEquals("user:first", compact(c.provider.requests.get(0)));
        assertEquals("user:second", compact(c.provider.requests.get(1)), "the first exchange must not be in the request");
        assertEquals("user:first | assistant:answer 1 | user:second | assistant:answer 2", c.kept());
    }

    @Test
    @DisplayName("文脈ありの構築: 過去の質問と答えが載り、制約は system としてそのターンの送信にだけ付く")
    void withContext_pastTurnsAreInTheRequestAndTheConstraintIsForThatTurnOnly() {
        Conversation c = new Conversation(null);
        c.iiar.tellNow(a -> ((ChatSession) a).setPromptWorkflowFile("with-context-and-a-constraint.yaml")).join();

        c.ask("first");
        c.ask("second");
        c.ask("third");

        assertEquals("system:[Constraints]\n- Obey the constraint. | user:first", compact(c.provider.requests.get(0)));
        assertEquals("system:[Constraints]\n- Obey the constraint. | user:first | assistant:answer 1 | user:second",
                compact(c.provider.requests.get(1)));
        assertEquals(1, new JSONArray(c.provider.requests.get(2)).toList().stream()
                .filter(m -> "system".equals(((java.util.Map<?, ?>) m).get("role"))).count(),
                "the constraint is rebuilt each turn and does not pile up: " + c.provider.requests.get(2));
        assertEquals("user:first | assistant:answer 1 | user:second | assistant:answer 2 | user:third | assistant:answer 3",
                c.kept());
    }

    @Test
    @DisplayName("バンドルされた文脈ありの構築は、過去の質問と答えを載せて、文脈なしと食い違わない")
    void theBundledWithContextConstructionCarriesThePast() {
        Conversation c = new Conversation(null);
        c.iiar.tellNow(a -> ((ChatSession) a).setPromptWorkflowFile("prompt-construction-with-context.yaml")).join();

        c.ask("first");
        c.ask("second");

        assertEquals("user:first", compact(c.provider.requests.get(0)));
        assertEquals("user:first | assistant:answer 1 | user:second", compact(c.provider.requests.get(1)));
        assertTrue(c.kept().endsWith("user:second | assistant:answer 2"), c.kept());
    }
}
