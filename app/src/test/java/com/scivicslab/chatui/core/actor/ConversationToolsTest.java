package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.OneShotReply;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.logging.RecentEntriesAccumulator;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversation tools ({@code get_chat_status}, {@code list_chat_models}, {@code set_chat_model},
 * {@code get_chat_log}) and the ending line {@code ask_chat} adds, run through two real
 * conversations in one actor system: {@code project1/chat-01} calls, {@code project1/chat-02} is
 * looked at.
 */
class ConversationToolsTest {

    /** Says one scripted thing per prompt; the first reply is the tool call under test. */
    private static class Scripted implements LlmProvider {
        final List<String> prompts = new ArrayList<>();
        final List<String> replies;
        final List<ModelEntry> models;
        String model = "gemma";
        Scripted(List<String> replies, List<ModelEntry> models) { this.replies = replies; this.models = models; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return models; }
        @Override public String getCurrentModel() { return model; }
        @Override public void setModel(String m) { this.model = m; }
        @Override public void cancel() {}
        String requestMessages = "[{\"role\":\"user\",\"content\":\"earlier\"}]";
        @Override public String requestMessagesJson() { return requestMessages; }
        @Override public String replaceRequestMessages(String json) {
            if (json.contains("\"tool\"")) return "element 0: role must be system, user or assistant";
            requestMessages = json;
            return null;
        }
        @Override public OneShotReply completeFromMessages(String json, String m, Boolean noThink) {
            return new OneShotReply("echo:" + json, json, null);
        }
        @Override
        public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(replies.get(Math.min(prompts.size() - 1, replies.size() - 1))));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    /** Records what the tools asked of the actor system. */
    private static final class FakeControls implements ConversationControls {
        final List<String> setCalls = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> providerCalls = new ArrayList<>();
        /** The id createChat hands out; "02" makes the new conversation one that exists in the test's actor system. */
        String createdId = "06";
        @Override public String createChat(String projectId) {
            created.add(projectId);
            return createdId;
        }
        /** The providers this fake instance offers; a test replaces it to leave one out. */
        List<ProviderOption> options = List.of(
                new ProviderOption("openai-compat", "openai-compat", com.scivicslab.chatui.agent.ToolSet.FULL, "Local LLM"),
                new ProviderOption("claude", "claude", com.scivicslab.chatui.agent.ToolSet.HARNESS, "Claude Code"),
                new ProviderOption("claude:full", "claude", com.scivicslab.chatui.agent.ToolSet.FULL,
                        "Claude Code (harness tools off)"));
        @Override public List<ProviderOption> providerOptions() { return options; }
        @Override public void setProvider(String projectId, String chatId, String kind,
                                          com.scivicslab.chatui.agent.ToolSet toolSet) {
            providerCalls.add(projectId + "/" + chatId + "=" + kind + ":" + toolSet.id());
        }
        final Organization organization = new Organization();
        @Override public String listChats(String projectId, String callerChatId) { return ""; }
        @Override public String setSuperior(String projectId, String chatId, String superiorChatId) {
            String r = organization.setSuperior(chatId, superiorChatId);
            return r == null ? "ok" : "error: refused — " + r;
        }
        @Override public String roomFor(String projectId, String superiorChatId) { return organization.roomFor(superiorChatId); }
        @Override public String positionOf(String projectId, String chatId) { return null; }
        final List<String> nameCalls = new ArrayList<>();
        @Override public String setChatName(String projectId, String chatId, String name) {
            if (chatId.equals("99")) return "error: unknown conversation: " + projectId + "/chat-99";
            nameCalls.add(projectId + "/" + chatId + "=" + name);
            return name == null ? "ok: name cleared" : "ok: name set to " + name;
        }
        final List<String> timekeeperCalls = new ArrayList<>();
        @Override public long[] setTimekeeperLimits(String projectId, String chatId, Long urgent, Long warn) {
            if ((urgent != null && urgent < 1) || (warn != null && warn < 1)) {
                throw new IllegalArgumentException("a limit is a whole number of minutes, at least 1");
            }
            timekeeperCalls.add(projectId + "/" + chatId + "=" + urgent + "," + warn);
            return new long[] {urgent == null ? 10 : urgent, warn == null ? 30 : warn};
        }
        @Override public ChatUiActorSystem.ChatDeletion deleteChat(String projectId, String chatId) {
            deleted.add(projectId + "/" + chatId);
            return switch (chatId) {
                case "02" -> new ChatUiActorSystem.ChatDeletion(ChatUiActorSystem.ChatDeletion.Kind.DELETED, "");
                case "03" -> new ChatUiActorSystem.ChatDeletion(ChatUiActorSystem.ChatDeletion.Kind.BUSY,
                        "project1/chat-03 is answering");
                default -> new ChatUiActorSystem.ChatDeletion(ChatUiActorSystem.ChatDeletion.Kind.UNKNOWN,
                        "no such conversation: project1/chat-" + chatId);
            };
        }
        @Override public boolean setModel(String projectId, String chatId, String model) {
            setCalls.add(projectId + "/" + chatId + "=" + model);
            return true;
        }
        @Override public List<RecentEntriesAccumulator.Entry> getChatLogEntries(String projectId, String chatId) {
            return chatId.equals("02")
                    ? List.of(new RecentEntriesAccumulator.Entry(1_700_000_000_000L, "agent", "step", "hello from 02"))
                    : null;
        }
    }

    private static ChatSessionIIAR chat(IIActorSystem system, String chatId, Scripted provider, FakeControls controls) {
        ChatSessionIIAR iiar = new ChatSessionIIAR("project1/chat-" + chatId + ".chat", provider,
                Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
            c.setConversationControls(controls);
        }).join();
        return iiar;
    }

    /** chat-01 makes {@code toolCall}; returns the prompt chat-01's model saw next (the tool result). */
    private static String callFromChat01(String toolCall, List<LlmProvider.ModelEntry> modelsOf02, FakeControls controls) {
        IIActorSystem system = new IIActorSystem("conversation-tools-" + Math.abs(toolCall.hashCode()));
        Scripted caller = new Scripted(List.of(toolCall, "done"), List.of());
        Scripted callee = new Scripted(List.of("unused"), modelsOf02);
        ChatSessionIIAR one = chat(system, "01", caller, controls);
        chat(system, "02", callee, controls);
        ActorRef<ChatSession> ref = one.asChatSessionRef();
        ref.tellNow(c -> {
            c.start("go", null, e -> {}, ref, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        assertEquals(2, caller.prompts.size(), "the tool ran and its result went back to the model");
        return caller.prompts.get(1);
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("</invoke>").toString();
    }

    private static final List<LlmProvider.ModelEntry> MODELS = List.of(
            new LlmProvider.ModelEntry("gemma", "chat", "broker"),
            new LlmProvider.ModelEntry("qwen-tensorfold", "chat", "broker"));

    @Test
    void getChatStatusReportsTheOtherConversation() {
        String result = callFromChat01(invoke("get_chat_status", "chatId", "02"), MODELS, new FakeControls());
        assertTrue(result.startsWith("Tool result (get_chat_status):"), result);
        assertTrue(result.contains("chat: project1/chat-02"), result);
        assertTrue(result.contains("model: gemma"), result);
        assertTrue(result.contains("busy: false"), result);
    }

    @Test
    void listChatModelsListsTheOtherConversationsModels() {
        String result = callFromChat01(invoke("list_chat_models", "chatId", "02"), MODELS, new FakeControls());
        assertTrue(result.contains("gemma (chat, broker)"), result);
        assertTrue(result.contains("qwen-tensorfold (chat, broker)"), result);
    }

    @Test
    void setChatModelSetsAListedNameThroughTheControls() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("set_chat_model", "chatId", "02", "model", "qwen-tensorfold"),
                MODELS, controls);
        assertTrue(result.contains("ok: project1/chat-02 is set to qwen-tensorfold"), result);
        assertEquals(List.of("project1/02=qwen-tensorfold"), controls.setCalls);
    }

    @Test
    void setChatModelRefusesAnUnlistedNameAndChangesNothing() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("set_chat_model", "chatId", "02", "model", "qwen-old-name"),
                MODELS, controls);
        assertTrue(result.contains("error: 'qwen-old-name' is not one of this conversation's models"), result);
        assertTrue(controls.setCalls.isEmpty(), "nothing was set");
    }

    @Test
    void setChatModelRefusesAnotherProject() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("set_chat_model", "chatId", "project2/02", "model", "gemma"),
                MODELS, controls);
        assertTrue(result.contains("error: refused — project2/02 belongs to another project"), result);
        assertTrue(controls.setCalls.isEmpty());
    }

    @Test
    void createChatAddsAConversationToTheCallersProjectAndNamesIt() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("create_chat"), MODELS, controls);
        assertTrue(result.contains("ok: created project1/chat-06"), result);
        assertEquals(List.of("project1"), controls.created);
    }

    @Test
    void createChatWithNoProviderPutsTheNewConversationOnClaudeCode() {
        FakeControls controls = new FakeControls();
        controls.createdId = "02";
        String result = callFromChat01(invoke("create_chat"), MODELS, controls);
        assertTrue(result.contains("ok: created project1/chat-02 (address it as \"02\") on claude,"), result);
        assertEquals(List.of("project1/02=claude:harness"), controls.providerCalls);
    }

    @Test
    void createChatWithNoProviderRefusesWhenClaudeIsNotConfiguredAndMakesNothing() {
        FakeControls controls = new FakeControls();
        controls.options = List.of(controls.options.get(0));
        String result = callFromChat01(invoke("create_chat"), MODELS, controls);
        assertTrue(result.contains("error: refused — this instance has no claude provider, which create_chat uses "
                + "when no provider is named; name one of: openai-compat"), result);
        assertTrue(controls.created.isEmpty());
        assertTrue(controls.providerCalls.isEmpty());
    }

    /**
     * Streams a response, then the harness's retraction of it, then the retry's response, as the Claude Code
     * provider does when the CLI withdraws a response and asks the model to answer again
     * (CliHarnessProvider_260912_oo01). Every later prompt is answered with "done".
     */
    private static final class Retracting extends Scripted {
        Retracting() { super(List.of("done"), List.of()); }
        @Override
        public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            if (prompts.size() == 1) {
                emitter.accept(ChatEvent.delta(invoke("create_chat", "reason", "first draft")));
                emitter.accept(new ChatEvent("retraction", "u-a", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));
                emitter.accept(ChatEvent.delta("Retrying.\n\n" + invoke("create_chat", "reason", "second draft")));
            } else {
                emitter.accept(ChatEvent.delta("done"));
            }
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    @Test
    void aWithdrawnResponseIsNotParsedForToolCalls() {
        FakeControls controls = new FakeControls();
        IIActorSystem system = new IIActorSystem("conversation-tools-retraction");
        Retracting caller = new Retracting();
        ChatSessionIIAR one = chat(system, "01", caller, controls);
        ActorRef<ChatSession> ref = one.asChatSessionRef();
        ref.tellNow(c -> {
            c.start("go", null, e -> {}, ref, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();

        assertEquals(List.of("project1"), controls.created,
                "only the retried response's call ran: one conversation was made, not two");
        assertEquals(2, caller.prompts.size(), "one tool result went back to the model");
        assertEquals(1, caller.prompts.get(1).split("Tool result \\(create_chat\\)", -1).length - 1, caller.prompts.get(1));
    }

    @Test
    void listProvidersNamesEachProviderByTheValueToPassOn() {
        String result = callFromChat01(invoke("list_providers"), MODELS, new FakeControls());
        assertTrue(result.contains("openai-compat (Local LLM)\nclaude (Claude Code)\n"
                + "claude:full (Claude Code (harness tools off))"), result);
    }

    @Test
    void createChatPutsTheNewConversationOnTheProviderAndModelAskedFor() {
        FakeControls controls = new FakeControls();
        controls.createdId = "02";
        String result = callFromChat01(invoke("create_chat", "provider", "Claude", "model", "qwen-tensorfold"),
                MODELS, controls);
        assertTrue(result.contains("ok: created project1/chat-02 (address it as \"02\") on claude, model qwen-tensorfold"),
                result);
        assertEquals(List.of("project1/02=claude:harness"), controls.providerCalls);
        assertEquals(List.of("project1/02=qwen-tensorfold"), controls.setCalls);
        assertTrue(controls.deleted.isEmpty());
    }

    @Test
    void createChatRefusesAnUnknownProviderBeforeMakingAnything() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("create_chat", "provider", "gpt"), MODELS, controls);
        assertTrue(result.contains("error: unknown provider 'gpt'; choose one of: openai-compat, claude, claude:full"),
                result);
        assertTrue(controls.created.isEmpty());
        assertTrue(controls.providerCalls.isEmpty());
    }

    @Test
    void createChatWithAnUnknownModelLeavesNoConversationBehind() {
        FakeControls controls = new FakeControls();
        controls.createdId = "02";
        String result = callFromChat01(invoke("create_chat", "model", "gpt-9"), MODELS, controls);
        assertTrue(result.contains("error: 'gpt-9' is not one of the models of claude; available:\n"
                + "gemma\nqwen-tensorfold\n(no conversation was created)"), result);
        assertEquals(List.of("project1/02"), controls.deleted);
        assertTrue(controls.setCalls.isEmpty());
    }

    @Test
    void setChatProviderPutsAConversationOfTheCallersProjectOnAnotherProvider() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("set_chat_provider", "chatId", "02", "provider", "claude:full"),
                MODELS, controls);
        assertTrue(result.contains("ok: project1/chat-02 is now on claude:full"), result);
        assertEquals(List.of("project1/02=claude:full"), controls.providerCalls);
    }

    @Test
    void setChatProviderRefusesAnotherProjectAndAnUnknownProvider() {
        FakeControls controls = new FakeControls();
        String other = callFromChat01(invoke("set_chat_provider", "chatId", "project2/02", "provider", "claude"),
                MODELS, controls);
        assertTrue(other.contains("error: refused — project2/02 belongs to another project"), other);
        String unknown = callFromChat01(invoke("set_chat_provider", "chatId", "02", "provider", "codex"),
                MODELS, controls);
        assertTrue(unknown.contains("error: unknown provider 'codex'"), unknown);
        assertTrue(controls.providerCalls.isEmpty());
    }

    @Test
    void deleteChatDeletesAConversationOfTheCallersProject() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("delete_chat", "chatId", "02"), MODELS, controls);
        assertTrue(result.contains("ok: deleted project1/chat-02"), result);
        assertEquals(List.of("project1/02"), controls.deleted);
    }

    @Test
    void deleteChatPassesOnTheReasonItWasRefused() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("delete_chat", "chatId", "03"), MODELS, controls);
        assertTrue(result.contains("error: project1/chat-03 is answering"), result);
    }

    @Test
    void deleteChatRefusesTheCallerItselfAndAnotherProjectWithoutAskingTheActorSystem() {
        FakeControls controls = new FakeControls();
        String self = callFromChat01(invoke("delete_chat", "chatId", "01"), MODELS, controls);
        assertTrue(self.contains("error: refused — a conversation cannot delete itself"), self);
        String other = callFromChat01(invoke("delete_chat", "chatId", "project2/02"), MODELS, controls);
        assertTrue(other.contains("error: refused — project2/02 belongs to another project"), other);
        assertTrue(controls.deleted.isEmpty(), "the actor system was not asked");
    }

    @Test
    void getChatLogReadsTheOtherConversationsLog() {
        String result = callFromChat01(invoke("get_chat_log", "chatId", "02"), MODELS, new FakeControls());
        assertTrue(result.contains("agent step hello from 02"), result);
    }

    @Test
    void getChatLogOfAConversationThatDoesNotExistSaysSo() {
        String result = callFromChat01(invoke("get_chat_log", "chatId", "09"), MODELS, new FakeControls());
        assertTrue(result.contains("error: chat not found: 09"), result);
    }

    @Test
    void getChatStatusOfAConversationThatDoesNotExistSaysSo() {
        String result = callFromChat01(invoke("get_chat_status", "chatId", "09"), MODELS, new FakeControls());
        assertTrue(result.contains("error: chat not found: 09"), result);
    }


    @Test
    void getRequestMessagesReadsTheArrayTheOtherConversationWillSend() {
        String result = callFromChat01(invoke("get_request_messages", "chatId", "02"), MODELS, new FakeControls());
        assertTrue(result.contains("\"content\":\"earlier\""), result);
    }

    @Test
    void setRequestMessagesReplacesTheArray() {
        String result = callFromChat01(invoke("set_request_messages", "chatId", "02",
                "messages", "[{\"role\":\"user\",\"content\":\"new\"}]"), MODELS, new FakeControls());
        assertTrue(result.contains("ok: the request history of 02 was replaced"), result);
    }

    @Test
    void setRequestMessagesRefusesAMalformedArrayAndSaysWhy() {
        String result = callFromChat01(invoke("set_request_messages", "chatId", "02",
                "messages", "[{\"role\":\"tool\",\"content\":\"x\"}]"), MODELS, new FakeControls());
        assertTrue(result.contains("error: element 0"), result);
    }

    @Test
    void setRequestMessagesRefusesAnotherProject() {
        String result = callFromChat01(invoke("set_request_messages", "chatId", "project2/02",
                "messages", "[]"), MODELS, new FakeControls());
        assertTrue(result.contains("belongs to another project"), result);
    }

    @Test
    void completeMessagesReturnsTheReplyOfTheOneShotCall() {
        String result = callFromChat01(invoke("complete_messages", "chatId", "02",
                "messages", "[{\"role\":\"user\",\"content\":\"ping\"}]"), MODELS, new FakeControls());
        assertTrue(result.contains("echo:[{"), result);
    }

    @Test
    void requestMessagesToolsOfAConversationThatDoesNotExistSaySo() {
        String result = callFromChat01(invoke("get_request_messages", "chatId", "09"), MODELS, new FakeControls());
        assertTrue(result.contains("error: chat not found: 09"), result);
    }

    /**
     * {@code ask_chat} as a tool returns as soon as the prompt is queued; the target's reply, with the
     * line saying how its turn ended, then comes to the caller as a new message in its own queue
     * ({@code AskChatWithoutWaiting_261022_oo01}). The plan runners' call still waits and gets the
     * reply alone.
     */
    @Test
    void askChatSendsWithoutWaitingAndTheReplyArrivesInTheCallersQueue() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-ask");
        FakeControls controls = new FakeControls();
        // The reply's turn records its decision first (ReplyDecisionAndPromisedReport_261108_oo01).
        Scripted caller = new Scripted(List.of(invoke("accept_reply"), "thanks"), List.of());
        Scripted callee = new Scripted(List.of("the answer"), List.of());
        ChatSessionIIAR one = chat(system, "01", caller, controls);
        ChatSessionIIAR two = chat(system, "02", callee, controls);
        ActorRef<PromptQueue> queue1 = one.createChild("project1/chat-01.queue", new PromptQueue());
        queue1.tellNow(q -> q.setSelf(queue1)).join();
        ActorRef<PromptQueue> queue2 = two.createChild("project1/chat-02.queue", new PromptQueue());
        queue2.tellNow(q -> q.setSelf(queue2)).join();

        String sent = AskChatTool.send(system, "project1", "01", "02", "question", "project1/chat-01:s7:t3:r1");
        assertTrue(sent.startsWith("sent to project1/chat-02 as request project1/chat-01:s7:t3:r1"), sent);

        long deadline = System.currentTimeMillis() + 20_000;
        while (caller.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, callee.prompts.size(), "the target got the prompt");
        assertTrue(callee.prompts.get(0).endsWith("[request project1/chat-01:s7:t3:r1 from project1/chat-01]\nquestion"),
                callee.prompts.get(0));
        assertEquals(2, caller.prompts.size(), "the reply came to the caller as a new message, then accept_reply's result");
        String reply = caller.prompts.get(0);
        // chat-02 has no I/O log in this test, so its turn id has no session part.
        assertTrue(reply.contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t1]\nthe answer"),
                reply);
        assertTrue(reply.endsWith("[ended: answered; model calls: 1; tool calls: 0]"), reply);

        // Both of chat-02's lines say which turn they belong to and which request they answer.
        List<ChatSession.HistoryEntry> asked = two.asChatSessionRef().ask(ChatSession::historySnapshot)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, asked.size(), asked.toString());
        for (ChatSession.HistoryEntry e : asked) {
            assertEquals("project1/chat-02:t1", e.turnId(), e.toString());
            assertEquals("project1/chat-01:s7:t3:r1", e.requestId(), e.toString());
        }
        // The reply, in chat-01, names the request it answers too.
        List<ChatSession.HistoryEntry> asker = one.asChatSessionRef().ask(ChatSession::historySnapshot)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("project1/chat-01:s7:t3:r1", asker.get(0).requestId(), asker.toString());

        ActorRef<CallWatchdog> watchdog = two.createChild("watchdog-for-test", new CallWatchdog());
        String viaPlan = AskChatTool.askQualified(system, watchdog, "project1/chat-01", "project1/chat-02",
                "question", 30);
        assertEquals("the answer", viaPlan);
    }

    /** Answers each prompt after a pause: a short one for the asked prompt, a long one for the next. */
    private static final class Slow extends Scripted {
        Slow() { super(List.of("the answer", "the next turn's answer"), List.of()); }
        @Override
        public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            try {
                Thread.sleep(prompts.isEmpty() ? 1_000 : 8_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.sendPrompt(prompt, m, emitter, ctx);
        }
    }

    /**
     * The asked conversation has another prompt queued behind the asked one, so it starts that
     * prompt's turn as soon as the asked turn ends, and the turn holds its actor for 8 seconds. The
     * reply must still come back at once: it travels in {@code done}, not in a question put to that
     * actor afterwards, which would wait behind the whole next turn
     * ({@code AskChatWithoutWaiting_261022_oo01}).
     */
    @Test
    void theReplyArrivesWhileTheAskedConversationRunsItsNextQueuedTurn() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-ask-next-turn");
        FakeControls controls = new FakeControls();
        Scripted caller = new Scripted(List.of("unused"), List.of());
        Slow callee = new Slow();
        chat(system, "01", caller, controls);
        ChatSessionIIAR two = chat(system, "02", callee, controls);
        ActorRef<PromptQueue> queue2 = two.createChild("project1/chat-02.queue", new PromptQueue());
        queue2.tellNow(q -> q.setSelf(queue2)).join();
        ActorRef<CallWatchdog> watchdog = two.createChild("watchdog-for-next-turn", new CallWatchdog());

        CompletableFuture<String> asked = CompletableFuture.supplyAsync(() -> AskChatTool.askQualified(
                system, watchdog, "project1/chat-01", "project1/chat-02", "question", 30));
        long deadline = System.currentTimeMillis() + 5_000;
        while (callee.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        // While the asked turn is still answering, another prompt joins chat-02's queue.
        queue2.tell(q -> q.enqueue("next", null, "queue", e -> {}, two.asChatSessionRef(), "human"));

        long started = System.currentTimeMillis();
        String reply = asked.get(30, java.util.concurrent.TimeUnit.SECONDS);
        long waited = System.currentTimeMillis() - started;
        assertEquals("the answer", reply);
        assertTrue(waited < 4_000, "the reply did not wait for the next turn; waited " + waited + " ms");
    }

    @Test
    void askChatFromATurnNumbersItsRequestsFromThatTurn() {
        IIActorSystem system = new IIActorSystem("conversation-tools-request-ids");
        FakeControls controls = new FakeControls();
        Scripted caller = new Scripted(List.of(
                invoke("ask_chat", "chatId", "02", "prompt", "first"),
                invoke("ask_chat", "chatId", "02", "prompt", "second"),
                "done"), List.of());
        ChatSessionIIAR one = chat(system, "01", caller, controls);
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("ok"), List.of()), controls);
        ActorRef<PromptQueue> queue1 = one.createChild("project1/chat-01.queue", new PromptQueue());
        queue1.tellNow(q -> q.setSelf(queue1)).join();
        ActorRef<PromptQueue> queue2 = two.createChild("project1/chat-02.queue", new PromptQueue());
        queue2.tellNow(q -> q.setSelf(queue2)).join();
        ActorRef<ChatSession> ref = one.asChatSessionRef();
        ref.tellNow(c -> {
            c.start("go", null, e -> {}, ref, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
        // The model saw each ask_chat's result as the next prompt: numbered 1 and 2 within turn 1.
        assertTrue(caller.prompts.get(1).contains("as request project1/chat-01:t1:r1;"), caller.prompts.get(1));
        assertTrue(caller.prompts.get(2).contains("as request project1/chat-01:t1:r2;"), caller.prompts.get(2));
    }

    /** Gives a conversation its queue, wired both ways as {@code ChatUiActorSystem.createChat} does. */
    private static ActorRef<PromptQueue> queueOf(ChatSessionIIAR chat) {
        ActorRef<PromptQueue> q = chat.createChild(chat.getName().replace(".chat", ".queue"), new PromptQueue());
        q.tellNow(x -> x.setSelf(q)).join();
        chat.tellNow(a -> ((ChatSession) a).setPromptQueueName(q.getName())).join();
        return q;
    }

    /**
     * chat-02 is asked by chat-01, hands the work to chat-03 and chat-04, and answers in three turns:
     * after sending both requests, after the first reply, and after the second. chat-01 gets exactly
     * one reply, sent when both replies are back, and it carries all three answers, each under its
     * turn, so a report written before the last reply came back is not lost
     * ({@code ReportReachesTheAskerAfterDelegation_261107_oo01}).
     */
    @Test
    void theAskerGetsOneReplyCarryingTheAnswerOfEveryTurnForItsRequest() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-delegation");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of(invoke("accept_reply"), "thanks"), List.of());
        // Each turn a reply starts records its decision first (ReplyDecisionAndPromisedReport_261108_oo01).
        Scripted supervisor = new Scripted(List.of(
                invoke("ask_chat", "chatId", "03", "prompt", "job A"),
                invoke("ask_chat", "chatId", "04", "prompt", "job B"),
                "sent both",
                invoke("accept_reply"),
                "one back",
                invoke("accept_reply"),
                "final report"), List.of());
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", supervisor, controls);
        ChatSessionIIAR three = chat(system, "03", new Scripted(List.of("A done"), List.of()), controls);
        ChatSessionIIAR four = chat(system, "04", new Scripted(List.of("B done"), List.of()), controls);
        for (ChatSessionIIAR c : List.of(one, two, three, four)) queueOf(c);

        AskChatTool.send(system, "project1", "01", "02", "do the work", "project1/chat-01:s7:t3:r1");

        long deadline = System.currentTimeMillis() + 20_000;
        while (supervisor.prompts.size() < 7 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        while (asker.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);
        assertEquals(7, supervisor.prompts.size(), "the supervisor answered both replies: " + supervisor.prompts);
        assertEquals(1, asker.prompts.stream().filter(p -> p.contains("[reply to request ")).count(),
                "one reply only: " + asker.prompts);
        String reply = asker.prompts.get(0);
        assertTrue(reply.contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t3]\n"
                + "[answer of turn project1/chat-02:t1]\nsent both\n\n"
                + "[answer of turn project1/chat-02:t2]\none back\n\n"
                + "[answer of turn project1/chat-02:t3]\nfinal report"), reply);
    }

    /**
     * chat-01 asks chat-02, and chat-02's reply starts a turn of chat-01's in which the model only writes
     * text. The agent loop does not end that turn: it asks, in the same turn, for a decision on the reply
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}). It asks once; the second answer ends the turn.
     */
    @Test
    @org.junit.jupiter.api.Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
    void aReplyTurnThatRecordsNoDecisionIsAskedForOneInTheSameTurn() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-undecided");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of("noted"), List.of());
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("I will report later"), List.of()), controls);
        queueOf(one);
        queueOf(two);

        AskChatTool.send(system, "project1", "01", "02", "run the tests", "project1/chat-01:t1:r1");

        long deadline = System.currentTimeMillis() + 20_000;
        while (asker.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);
        assertEquals(2, asker.prompts.size(), "asked once for a decision, then the turn ended: " + asker.prompts);
        assertTrue(asker.prompts.get(1).contains("This turn began with project1/chat-02's reply to request "
                + "project1/chat-01:t1:r1, and you have recorded no decision on it."), asker.prompts.get(1));
    }

    /**
     * chat-02 promises a report; chat-01 registers it with expect_report on chat-02's timekeeper. When the
     * time comes, the timekeeper reminds chat-02, and the answer of the turn the reminder starts reaches
     * chat-01 as a follow-up to the same request, numbered ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    @Test
    @org.junit.jupiter.api.Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
    void aPromisedReportIsRemindedAndItsAnswerReachesTheAskerAsAFollowUp() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-promised-report");
        FakeControls controls = new FakeControls();
        String request = "project1/chat-01:t1:r1";
        Scripted asker = new Scripted(List.of(
                invoke("expect_report", "chatId", "02", "requestId", request, "minutes", "1"),
                "registered",
                invoke("accept_reply", "requestId", request),
                "done"), List.of());
        Scripted promiser = new Scripted(List.of("I will report later", "REPORT: all green"), List.of());
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", promiser, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        ActorRef<Timekeeper> keeper = system.actorOf("project1/chat-02.timekeeper",
                new Timekeeper("project1/chat-02", two, queue2, e -> {}, 600_000, 1_800_000));

        AskChatTool.send(system, "project1", "01", "02", "run the tests", request);
        long deadline = System.currentTimeMillis() + 20_000;
        while (asker.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(asker.prompts.get(1).contains("reminder 1"), "the tool said which reminder: " + asker.prompts.get(1));

        keeper.tell(k -> k.tick(System.currentTimeMillis() + 120_000));
        while (asker.prompts.size() < 4 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);

        assertTrue(promiser.prompts.get(1).contains("[reminder 1 for request " + request + " from project1/chat-01]"),
                promiser.prompts.get(1));
        assertTrue(asker.prompts.get(2).contains("[follow-up to request " + request + ", from project1/chat-02:t2, reminder 1]\n"
                + "REPORT: all green"), asker.prompts.get(2));
        assertEquals(4, asker.prompts.size(), "the follow-up was accepted, and nothing more was asked: " + asker.prompts);
        assertTrue(asker.prompts.stream().noneMatch(p -> p.contains("recorded no decision")), asker.prompts.toString());
    }

    /**
     * A turn that began with chat-02's reply is paused for an urgent request before it records a decision.
     * The turn that resumes it still owes that decision, and is asked for it
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    @Test
    @org.junit.jupiter.api.Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
    void aReplyTurnPausedBeforeItsDecisionIsAskedForItWhenItResumes() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-undecided-paused");
        FakeControls controls = new FakeControls();
        Paced asker = new Paced(List.of("noted-1", "urgent answer", "noted-3", "noted-4"), java.util.Set.of(1));
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("I will report later"), List.of()), controls);
        ActorRef<PromptQueue> queue1 = queueOf(one);
        queueOf(two);
        Timekeeper keeper = new Timekeeper("project1/chat-01", one, queue1, e -> {}, 0, Long.MAX_VALUE);

        AskChatTool.send(system, "project1", "01", "02", "run the tests", "project1/chat-01:t1:r1");
        waitUntil(() -> asker.entered.size() == 1);
        queue1.ask(q -> q.enqueue("[urgent request x from y]\nhow far?", null, "queue", e -> {}, one.asChatSessionRef(),
                "agent:ask_chat:project1/chat-09", null, new CompletableFuture<>(), false, true, List.of(), null, true))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        keeper.tick(System.currentTimeMillis() + 1);

        waitUntil(() -> asker.prompts.size() >= 4);
        Thread.sleep(500);
        assertEquals(4, asker.prompts.size(), asker.prompts.toString());
        assertTrue(asker.prompts.get(1).endsWith("how far?"), "the urgent request came next, not a decision note: "
                + asker.prompts.get(1));
        assertTrue(asker.prompts.get(2).contains("[resume paused turn "), asker.prompts.get(2));
        assertTrue(asker.prompts.get(3).contains("This turn began with project1/chat-02's reply to request "
                + "project1/chat-01:t1:r1, and you have recorded no decision on it."), asker.prompts.get(3));
    }

    /**
     * The turn a reminder starts is paused for an urgent request. The turn that resumes it writes the
     * report, and the report still reaches the asker as the reminder's follow-up
     * ({@code ReplyDecisionAndPromisedReport_261108_oo01}).
     */
    @Test
    @org.junit.jupiter.api.Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
    void aReminderTurnPausedForAnUrgentRequestStillSendsItsFollowUp() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-reminder-paused");
        FakeControls controls = new FakeControls();
        String request = "project1/chat-01:t1:r1";
        Scripted asker = new Scripted(List.of(
                invoke("expect_report", "chatId", "02", "requestId", request, "minutes", "1"),
                "registered",
                invoke("accept_reply", "requestId", request),
                "done"), List.of());
        Paced promiser = new Paced(List.of("I will report later", "REPORT part", "urgent answer", "REPORT: all green"),
                java.util.Set.of(2));
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", promiser, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        Timekeeper keeper = new Timekeeper("project1/chat-02", two, queue2, e -> {}, 0, Long.MAX_VALUE);
        system.actorOf("project1/chat-02.timekeeper", keeper);

        AskChatTool.send(system, "project1", "01", "02", "run the tests", request);
        waitUntil(() -> asker.prompts.size() >= 2);
        keeper.tick(System.currentTimeMillis() + 120_000);
        waitUntil(() -> promiser.entered.size() == 2);
        queue2.ask(q -> q.enqueue("[urgent request x from y]\nhow far?", null, "queue", e -> {}, two.asChatSessionRef(),
                "agent:ask_chat:project1/chat-09", null, new CompletableFuture<>(), false, true, List.of(), null, true))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        keeper.tick(System.currentTimeMillis() + 1);

        waitUntil(() -> asker.prompts.size() >= 4);
        Thread.sleep(500);
        assertEquals(4, promiser.prompts.size(), promiser.prompts.toString());
        assertTrue(promiser.prompts.get(3).contains("[resume paused turn "), promiser.prompts.get(3));
        assertTrue(asker.prompts.size() >= 3, asker.prompts.toString());
        String followUp = asker.prompts.get(2);
        assertTrue(followUp.contains("[follow-up to request " + request + ", from project1/chat-02:")
                && followUp.contains(", reminder 1]\nREPORT: all green"), followUp);
    }

    @Test
    void anItemRemovedFromTheQueueTellsItsAskerThereWillBeNoReply() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-removed-item");
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("unused"), List.of()), new FakeControls());
        ActorRef<PromptQueue> queue = queueOf(two);
        CompletableFuture<TurnReply> done = new CompletableFuture<>();
        // Held (auto off), so it waits in the queue until it is removed.
        queue.tellNow(q -> q.enqueue("held", null, "queue", e -> {}, two.asChatSessionRef(),
                "agent:ask_chat:project1/chat-01", "k", done, false, false, List.of(), "project1/chat-01:t1:r1")).join();
        assertTrue(queue.ask(q -> q.removeAt(0)).get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(TurnReply.NONE, done.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    /** Urgent items go ahead of the waiting ones that are not, and keep their own order. */
    @Test
    void anUrgentItemGoesAheadOfTheWaitingOnesAndBehindEarlierUrgentOnes() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-urgent-order");
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("unused"), List.of()), new FakeControls());
        ActorRef<PromptQueue> queue = queueOf(two);
        java.util.function.BiFunction<String, Boolean, Integer> put = (text, urgent) -> {
            try {
                // Held (auto off), so nothing is dispatched while the order is checked.
                return queue.ask(q -> q.enqueue(text, null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                        new CompletableFuture<>(), false, false, List.of(), null, urgent))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        assertEquals(0, put.apply("C", false));
        assertEquals(0, put.apply("D", false));
        assertEquals(2, put.apply("A", true), "A passed C and D");
        assertEquals(2, put.apply("B", true), "B passed C and D, and stayed behind A");
        List<PromptQueue.QueueEntry> order = queue.ask(PromptQueue::snapshot).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(List.of("A", "B", "C", "D"), order.stream().map(PromptQueue.QueueEntry::prompt).toList());
        assertEquals(List.of(true, true, false, false), order.stream().map(PromptQueue.QueueEntry::urgent).toList());
    }

    /**
     * An urgent request reads its heading, the result says how many items it passed, and its reply
     * also goes in ahead: here, ahead of a held item that would otherwise keep it from being sent.
     */
    @Test
    void anUrgentRequestAndItsReplyBothGoAhead() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-urgent-reply");
        FakeControls controls = new FakeControls();
        Scripted caller = new Scripted(List.of(invoke("accept_reply"), "thanks"), List.of());
        Scripted callee = new Scripted(List.of("about half done"), List.of());
        ChatSessionIIAR one = chat(system, "01", caller, controls);
        ChatSessionIIAR two = chat(system, "02", callee, controls);
        ActorRef<PromptQueue> queue1 = queueOf(one);
        queueOf(two);
        queue1.ask(q -> q.enqueue("held", null, "queue", e -> {}, one.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, false, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);

        String sent = AskChatTool.sendUrgent(system, "project1", "01", "02", "how far are you?", "project1/chat-01:s7:t3:r1");
        assertTrue(sent.startsWith("sent to project1/chat-02 as urgent request project1/chat-01:s7:t3:r1, ahead of 0 waiting"),
                sent);

        long deadline = System.currentTimeMillis() + 20_000;
        while (caller.prompts.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(callee.prompts.get(0).endsWith("[urgent request project1/chat-01:s7:t3:r1 from project1/chat-01]\nhow far are you?"),
                callee.prompts.get(0));
        assertEquals(2, caller.prompts.size(), "the reply went ahead of the held item and was sent, then accept_reply's result");
        assertTrue(caller.prompts.get(0).contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t1]\nabout half done"),
                caller.prompts.get(0));
    }

    /** Answers one scripted line per prompt; the prompts whose number is in {@code slow} take 3 seconds. */
    private static final class Paced extends Scripted {
        final java.util.Set<Integer> slow;
        /** The prompts of the calls that have begun; a slow call's prompt is here before its reply is recorded. */
        final List<String> entered = new java.util.concurrent.CopyOnWriteArrayList<>();
        Paced(List<String> replies, java.util.Set<Integer> slow) { super(replies, List.of()); this.slow = slow; }
        @Override
        public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            entered.add(prompt);
            if (slow.contains(prompts.size() + 1)) {
                try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            super.sendPrompt(prompt, m, emitter, ctx);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    /**
     * chat-02 is working on chat-01's request when an urgent request arrives. Its timekeeper pauses the
     * turn, the urgent request is answered, and a resumed turn finishes the work; chat-01's one reply
     * comes from that resumed turn ({@code TimekeeperPausesATurnForAnUrgentRequest_261107_oo01}).
     */
    @Test
    void theTimekeeperPausesATurnForAnUrgentRequestAndTheWorkResumesAfterIt() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-timekeeper-pause");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of(invoke("accept_reply"), "thanks"), List.of());
        Paced worker = new Paced(List.of("unfinished", "about half done", "all done"), java.util.Set.of(1));
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        Timekeeper keeper = new Timekeeper("project1/chat-02", two, queue2, e -> {}, 0, Long.MAX_VALUE);

        AskChatTool.send(system, "project1", "01", "02", "do the long job", "project1/chat-01:s7:t3:r1");
        waitUntil(() -> two.isBusyDirect());
        queue2.ask(q -> q.enqueue("[urgent request x from y]\nhow far?", null, "queue", e -> {}, two.asChatSessionRef(),
                "agent:ask_chat:project1/chat-09", null, new CompletableFuture<>(), false, true, List.of(), null, true))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        // A moment past the item's queueing time: the limit is "waited longer than", here 0 ms.
        keeper.tick(System.currentTimeMillis() + 1);

        waitUntil(() -> asker.prompts.size() >= 2);
        assertEquals(3, worker.prompts.size(), worker.prompts.toString());
        assertTrue(worker.prompts.get(1).endsWith("how far?"), "the urgent request came next: " + worker.prompts);
        assertTrue(worker.prompts.get(2).contains("[resume paused turn project1/chat-02:t1, for request project1/chat-01:s7:t3:r1]"),
                worker.prompts.get(2));
        assertTrue(worker.prompts.get(2).endsWith("do the long job"), "the resume message repeats the work: " + worker.prompts.get(2));
        assertEquals(2, asker.prompts.size(), "the reply, then accept_reply's result: " + asker.prompts);
        assertTrue(asker.prompts.get(0).contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t3]\nall done"),
                asker.prompts.get(0));
    }

    /**
     * A message from the target's superior goes ahead of the work already waiting in the target's queue,
     * without the superior asking for urgency ({@code ConversationReportingLinesTests_261108_oo01}).
     */
    @Test
    @org.junit.jupiter.api.Tag("ConversationReportingLinesTests_261108_oo01")
    void aMessageFromTheSuperiorGoesAheadOfWaitingWork() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-superior-first");
        FakeControls controls = new FakeControls();
        Scripted boss = new Scripted(List.of("ok"), List.of());
        Paced worker = new Paced(List.of("w1", "w2", "w3"), java.util.Set.of(1));
        ChatSessionIIAR one = chat(system, "01", boss, controls);
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        Organization org = new Organization();
        org.setSuperior("02", "01");
        system.actorOf(ChatUiActorSystem.organizationActorName("project1"), org);

        queue2.ask(q -> q.enqueue("the long job", null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, true, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        waitUntil(() -> two.isBusyDirect());
        queue2.ask(q -> q.enqueue("other waiting work", null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, true, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);

        String sent = AskChatTool.send(system, "project1", "01", "02", "decision: use plan B", "project1/chat-01:t1:r1");

        assertTrue(sent.startsWith("sent to project1/chat-02 as request"), sent);
        List<String> order = queue2.ask(q -> q.snapshot().stream().map(PromptQueue.QueueEntry::prompt).toList())
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, order.size(), order.toString());
        assertTrue(order.get(0).startsWith("[request project1/chat-01:t1:r1 from project1/chat-01, your superior]")
                && order.get(0).endsWith("decision: use plan B"), order.toString());
        assertEquals("other waiting work", order.get(1));
    }

    /**
     * A turn answering an urgent request is itself paused for a second urgent request. Its resume message
     * names that first urgent request and repeats it, so the resumed turn answers it and not the long job
     * that was paused before it.
     */
    @Test
    void aPausedUrgentTurnResumesWithItsOwnQuestion() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-timekeeper-urgent-paused");
        FakeControls controls = new FakeControls();
        Paced worker = new Paced(List.of("x1", "x2", "x3", "x4", "x5", "x6"), java.util.Set.of(1, 2));
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        Timekeeper keeper = new Timekeeper("project1/chat-02", two, queue2, e -> {}, 0, Long.MAX_VALUE);

        queue2.ask(q -> q.enqueue("do the long job", null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, true, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        waitUntil(() -> two.isBusyDirect());
        queue2.ask(q -> q.enqueue("[urgent request q-A from project1/chat-09]\nhow far is the job?", null, "queue", e -> {},
                two.asChatSessionRef(), "agent:ask_chat:project1/chat-09", null, new CompletableFuture<>(), false, true,
                List.of(), "q-A", true)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        keeper.tick(System.currentTimeMillis() + 1);
        // The urgent request's turn has begun its slow model call.
        waitUntil(() -> worker.entered.stream().anyMatch(p -> p.endsWith("how far is the job?")));
        queue2.ask(q -> q.enqueue("[urgent request q-B from project1/chat-08]\nanother question", null, "queue", e -> {},
                two.asChatSessionRef(), "agent:ask_chat:project1/chat-08", null, new CompletableFuture<>(), false, true,
                List.of(), "q-B", true)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        keeper.tick(System.currentTimeMillis() + 1);

        waitUntil(() -> worker.prompts.size() >= 5);
        assertTrue(worker.prompts.get(1).endsWith("how far is the job?"), worker.prompts.toString());
        String resumedUrgent = worker.prompts.stream()
                .filter(p -> p.contains(", for request q-A]")).findFirst().orElse(null);
        assertTrue(resumedUrgent != null, "a resume message carries q-A: " + worker.prompts);
        assertTrue(resumedUrgent.endsWith("how far is the job?"), resumedUrgent);
        assertTrue(!resumedUrgent.contains("do the long job"), resumedUrgent);
    }

    /**
     * The message that resumes a paused turn is queued as urgent, so that it comes before work that
     * waited, but it is not an urgent request: a turn is not paused for it. Counting it made a slow model
     * pause every resumed turn for the next resume message, and the pausing never stopped.
     */
    @Test
    void aResumeMessageAtTheFrontDoesNotPauseTheRunningTurn() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-timekeeper-resume-not-urgent");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of(invoke("accept_reply"), "thanks"), List.of());
        Paced worker = new Paced(List.of("all done", "nothing left"), java.util.Set.of(1));
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        Timekeeper keeper = new Timekeeper("project1/chat-02", two, queue2, e -> {}, 0, Long.MAX_VALUE);

        AskChatTool.send(system, "project1", "01", "02", "do the long job", "project1/chat-01:s7:t3:r1");
        waitUntil(() -> two.isBusyDirect());
        queue2.ask(q -> q.enqueue("[resume paused turn project1/chat-02:t0]\ngo on", null, "queue", e -> {},
                two.asChatSessionRef(), ChatSession.RESUME_SOURCE, null, new CompletableFuture<>(), false, true,
                List.of(), null, true)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(null, queue2.ask(PromptQueue::frontUrgentQueuedAt).get(5, java.util.concurrent.TimeUnit.SECONDS),
                "a resume message is not an urgent request");
        keeper.tick(System.currentTimeMillis() + 1);

        waitUntil(() -> !asker.prompts.isEmpty());
        assertTrue(asker.prompts.get(0).contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t1]\nall done"),
                "the running turn finished without a pause: " + asker.prompts);
    }

    @Test
    void anUrgentRequestBehindAResumeMessageStillCounts() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-timekeeper-urgent-behind-resume");
        FakeControls controls = new FakeControls();
        Paced worker = new Paced(List.of("done", "a", "b"), java.util.Set.of(1));
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        queue2.ask(q -> q.enqueue("first", null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, true, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        waitUntil(() -> two.isBusyDirect());
        queue2.ask(q -> q.enqueue("[resume paused turn project1/chat-02:t0]\ngo on", null, "queue", e -> {},
                two.asChatSessionRef(), ChatSession.RESUME_SOURCE, null, new CompletableFuture<>(), false, true,
                List.of(), null, true)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        long before = System.currentTimeMillis();
        queue2.ask(q -> q.enqueue("[urgent request x from y]\nhow far?", null, "queue", e -> {}, two.asChatSessionRef(),
                "agent:ask_chat:project1/chat-09", null, new CompletableFuture<>(), false, true, List.of(), null, true))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        Long since = queue2.ask(PromptQueue::frontUrgentQueuedAt).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(since != null && since >= before, "the urgent request's own time counts: " + since);
        // The urgent request went ahead of the resume message, which stays ahead of ordinary work.
        queue2.ask(q -> q.enqueue("ordinary", null, "queue", e -> {}, two.asChatSessionRef(), "human", null,
                new CompletableFuture<>(), false, true, List.of(), null, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        List<String> order = queue2.ask(q -> q.snapshot().stream().map(PromptQueue.QueueEntry::prompt).toList())
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(3, order.size(), order.toString());
        assertTrue(order.get(0).endsWith("how far?") && order.get(1).startsWith("[resume paused turn")
                && order.get(2).equals("ordinary"), order.toString());
    }

    /**
     * chat-01 asks chat-49, which asks chat-51; chat-51 answers "I will report later." and chat-49 calls
     * expect_report on that reply. The request chat-49 received stays open, so chat-01 gets one reply, written
     * after chat-51's reported result arrived, and never has to call expect_report itself
     * (ReplyDecisionAndPromisedReport_261108_oo01).
     */
    @Test
    @org.junit.jupiter.api.Tag("ReplyDecisionAndPromisedReportTests_261108_oo01")
    void aReportExpectedFromTheWorkerKeepsTheManagersRequestOpenUntilItArrives() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-reopened-request");
        FakeControls controls = new FakeControls();
        String task = "project1/chat-01:t1:r1";
        String toWorker = "project1/chat-49:t1:r1";
        Scripted liaison = new Scripted(List.of(invoke("accept_reply", "requestId", task), "done"), List.of());
        Scripted manager = new Scripted(List.of(
                invoke("ask_chat", "chatId", "51", "prompt", "check it"),
                "asked chat-51",
                invoke("expect_report", "chatId", "51", "requestId", toWorker, "minutes", "1"),
                "waiting for chat-51",
                invoke("accept_reply", "requestId", toWorker),
                "FINAL: chat-51 reports Result 42"), List.of());
        Scripted worker = new Scripted(List.of("I will report later.", "Result 42"), List.of());
        ChatSessionIIAR one = chat(system, "01", liaison, controls);
        ChatSessionIIAR two = chat(system, "49", manager, controls);
        ChatSessionIIAR three = chat(system, "51", worker, controls);
        queueOf(one);
        queueOf(two);
        ActorRef<PromptQueue> queue3 = queueOf(three);
        Timekeeper keeper = new Timekeeper("project1/chat-51", three, queue3, e -> {}, 600_000, 1_800_000);
        system.actorOf("project1/chat-51.timekeeper", keeper);

        AskChatTool.send(system, "project1", "01", "49", "run the check", task);
        waitUntil(() -> manager.prompts.size() >= 4);
        keeper.tick(System.currentTimeMillis() + 120_000);
        waitUntil(() -> manager.prompts.size() >= 6 && liaison.prompts.size() >= 2);
        Thread.sleep(300);

        long replies = liaison.prompts.stream().filter(p -> p.contains("[reply to request " + task)).count();
        assertEquals(1, replies, "chat-01 gets one reply: " + liaison.prompts);
        assertTrue(liaison.prompts.get(0).contains("Result 42"), "and it carries the reported result: " + liaison.prompts.get(0));
        assertEquals(2, liaison.prompts.size(), "chat-01 only accepted that reply; it never needed expect_report: " + liaison.prompts);
        assertTrue(manager.prompts.get(4).contains("[follow-up to request " + toWorker + ", from project1/chat-51:t2, reminder 1]"),
                manager.prompts.get(4));
    }

    /** A request with no reply past the limit is reported to the conversation that sent it, once. */
    @Test
    void theTimekeeperTellsItsConversationOnceAboutARequestWithNoReply() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-timekeeper-warn");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of("thanks"), List.of());
        Scripted supervisor = new Scripted(List.of(
                invoke("ask_chat", "chatId", "03", "prompt", "slow job"), "handed out", "noted", "report"), List.of());
        Paced slow = new Paced(List.of("done at last"), java.util.Set.of(1));
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", supervisor, controls);
        ChatSessionIIAR three = chat(system, "03", slow, controls);
        queueOf(one);
        ActorRef<PromptQueue> queue2 = queueOf(two);
        queueOf(three);
        Timekeeper keeper = new Timekeeper("project1/chat-02", two, queue2, e -> {}, Long.MAX_VALUE, 0);

        AskChatTool.send(system, "project1", "01", "02", "get it done", "project1/chat-01:s7:t3:r1");
        waitUntil(() -> !two.outstandingChildrenDirect().isEmpty() && !two.isBusyDirect());
        keeper.tick(System.currentTimeMillis() + 1);
        keeper.tick(System.currentTimeMillis() + 2);
        waitUntil(() -> !asker.prompts.isEmpty());

        long notices = supervisor.prompts.stream().filter(p -> p.contains("[no reply yet to request project1/chat-02:t1:r1 sent to project1/chat-03")).count();
        assertEquals(1, notices, supervisor.prompts.toString());
    }

    /** What watchers see live carries the ids too: a "turn" event at the start, and the result at the end. */
    @Test
    void theTurnAndResultEventsCarryTheTurnAndRequestIds() {
        IIActorSystem system = new IIActorSystem("conversation-tools-live-ids");
        ChatSessionIIAR two = chat(system, "02", new Scripted(List.of("an answer"), List.of()), new FakeControls());
        List<ChatEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        ActorRef<ChatSession> ref = two.asChatSessionRef();
        ref.tellNow(c -> {
            c.start("[request project1/chat-01:t4:r1 from project1/chat-01]\nquestion", null, events::add, ref,
                    new CompletableFuture<>(), "project1/chat-01:t4:r1", false, List.of(),
                    "agent:ask_chat:project1/chat-01", "project1/chat-01:t4:r1");
            c.runUntilEnd();
        }).join();
        ChatEvent turn = events.stream().filter(e -> "turn".equals(e.type())).findFirst().orElseThrow();
        assertEquals("project1/chat-02:t1", turn.turnId());
        assertEquals("project1/chat-01:t4:r1", turn.requestId());
        ChatEvent result = events.stream().filter(e -> "result".equals(e.type())).findFirst().orElseThrow();
        assertEquals("project1/chat-02:t1", result.turnId());
        assertEquals("project1/chat-01:t4:r1", result.requestId());
    }

    /**
     * A request a job's workflow step sends, from outside any turn, is named <sender>:q<n>. Its prompt is sent as
     * the step built it; the id is kept in the record.
     */
    @Test
    void aRequestFromOutsideAnyTurnGetsAnIdOfItsOwn() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-outside-turn");
        Scripted callee = new Scripted(List.of("done"), List.of());
        ChatSessionIIAR two = chat(system, "02", callee, new FakeControls());
        queueOf(two);
        ActorRef<CallWatchdog> watchdog = two.createChild("watchdog-outside-turn", new CallWatchdog());
        assertEquals("done", AskChatTool.askQualified(system, watchdog, "job-7", "project1/chat-02", "go", 30));
        assertTrue(callee.prompts.get(0).endsWith("go") && !callee.prompts.get(0).contains("[request "),
                callee.prompts.get(0));
        List<ChatSession.HistoryEntry> asked = two.asChatSessionRef().ask(ChatSession::historySnapshot)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(asked.get(0).requestId().matches("job-7:q\\d+"), asked.toString());
    }

    @Test
    void setChatTimekeeperChangesTheLimitsAndKeepsAnOmittedOne() {
        FakeControls controls = new FakeControls();
        String result = callFromChat01(invoke("set_chat_timekeeper", "chatId", "02", "urgentWaitMinutes", "1"),
                MODELS, controls);
        assertTrue(result.contains("ok: project1/chat-02 now pauses a turn for an urgent request after 1 minute(s), "
                + "and reports a request with no reply after 30 minute(s)"), result);
        assertEquals(List.of("project1/02=1,null"), controls.timekeeperCalls);
    }

    @Test
    void setChatTimekeeperRefusesAnotherProjectAndALimitBelowAMinute() {
        FakeControls controls = new FakeControls();
        String other = callFromChat01(invoke("set_chat_timekeeper", "chatId", "project2/02", "urgentWaitMinutes", "1"),
                MODELS, controls);
        assertTrue(other.contains("error: refused — project2/02 belongs to another project"), other);
        String zero = callFromChat01(invoke("set_chat_timekeeper", "chatId", "02", "childReplyWarnMinutes", "0"),
                MODELS, controls);
        assertTrue(zero.contains("error: a limit is a whole number of minutes, at least 1"), zero);
        String none = callFromChat01(invoke("set_chat_timekeeper", "chatId", "02"), MODELS, controls);
        assertTrue(none.contains("error: give urgentWaitMinutes, childReplyWarnMinutes, or both"), none);
    }

    @Test
    void setChatNameNamesAConversationOfThisProjectAndClearsIt() {
        FakeControls controls = new FakeControls();
        String named = callFromChat01(invoke("set_chat_name", "chatId", "02", "name", "  worker 1-3:\n  articles A-F "),
                MODELS, controls);
        assertTrue(named.contains("ok: project1/chat-02 is now named \"worker 1-3: articles A-F\""), named);
        String own = callFromChat01(invoke("set_chat_name", "chatId", "01", "name", "platoon leader"), MODELS, controls);
        assertTrue(own.contains("ok: project1/chat-01 is now named \"platoon leader\""), own);
        String cleared = callFromChat01(invoke("set_chat_name", "chatId", "02", "name", ""), MODELS, controls);
        assertTrue(cleared.contains("ok: project1/chat-02 has no name now"), cleared);
        assertEquals(List.of("project1/02=worker 1-3: articles A-F", "project1/01=platoon leader", "project1/02=null"),
                controls.nameCalls);
    }

    @Test
    void setChatNameRefusesAnotherProjectAnUnknownConversationAndAParagraph() {
        FakeControls controls = new FakeControls();
        String other = callFromChat01(invoke("set_chat_name", "chatId", "project2/02", "name", "x"), MODELS, controls);
        assertTrue(other.contains("error: refused — project2/02 belongs to another project"), other);
        String unknown = callFromChat01(invoke("set_chat_name", "chatId", "99", "name", "x"), MODELS, controls);
        assertTrue(unknown.contains("error: unknown conversation: project1/chat-99"), unknown);
        String tooLong = callFromChat01(invoke("set_chat_name", "chatId", "02", "name", "y".repeat(81)), MODELS, controls);
        assertTrue(tooLong.contains("error: a name is at most 80 characters; this one has 81"), tooLong);
        assertTrue(controls.nameCalls.isEmpty(), controls.nameCalls.toString());
    }

    /** Keeps what the end of each turn put into the history, so a test can read a paused turn's progress. */
    private static final class Collapsing extends Scripted {
        final List<String> collapsedAnswers = new java.util.concurrent.CopyOnWriteArrayList<>();
        Collapsing(List<String> replies) { super(replies, List.of()); }
        @Override public void collapseTurn(String question, String answer) { collapsedAnswers.add(answer); }
    }

    /** Answers complete_messages after 3 seconds, so a tool call can still be running when a pause is asked for. */
    private static final class SlowOneShot extends Scripted {
        SlowOneShot() { super(List.of("unused"), List.of()); }
        @Override public OneShotReply completeFromMessages(String json, String m, Boolean noThink) {
            try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new OneShotReply("STEP-A-DONE", json, null);
        }
    }

    /**
     * A turn paused while a tool is running keeps that tool's result in its progress: the pause takes
     * effect before the next step, which is where the result used to be picked up.
     */
    @Test
    void aTurnPausedWhileAToolRunsKeepsThatToolsResult() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-pause-during-tool");
        FakeControls controls = new FakeControls();
        Collapsing worker = new Collapsing(List.of(
                invoke("complete_messages", "chatId", "03", "messages", "[{\"role\":\"user\",\"content\":\"go\"}]"),
                "never reached"));
        ChatSessionIIAR two = chat(system, "02", worker, controls);
        chat(system, "03", new SlowOneShot(), controls);
        queueOf(two);
        ActorRef<ChatSession> ref = two.asChatSessionRef();
        CompletableFuture<Void> turn = CompletableFuture.runAsync(() -> ref.tellNow(c -> {
            c.start("do step A", null, e -> {}, ref, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join());
        waitUntil(() -> worker.prompts.size() == 1);
        Thread.sleep(500);                      // the tool is now running in chat-03
        ref.tellNow(ChatSession::pauseForUrgent).join();
        turn.get(20, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(1, worker.prompts.size(), "no step after the tool: " + worker.prompts);
        String progress = worker.collapsedAnswers.get(worker.collapsedAnswers.size() - 1);
        assertTrue(progress.contains("paused before it finished"), progress);
        assertTrue(progress.contains("Tool result (complete_messages)") && progress.contains("STEP-A-DONE"), progress);
    }

    @Test
    void askChatToAConversationThatDoesNotExistIsRefusedAtOnce() {
        IIActorSystem system = new IIActorSystem("conversation-tools-ask-missing");
        chat(system, "01", new Scripted(List.of("unused"), List.of()), new FakeControls());
        assertEquals("error: chat not found: 09", AskChatTool.send(system, "project1", "01", "09", "question", "project1/chat-01:t1:r1"));
        assertEquals("error: cannot ask_chat yourself", AskChatTool.send(system, "project1", "01", "01", "question", "project1/chat-01:t1:r1"));
    }
}
