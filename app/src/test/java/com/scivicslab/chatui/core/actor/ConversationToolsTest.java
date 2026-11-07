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
        @Override public List<ProviderOption> providerOptions() {
            return List.of(
                    new ProviderOption("openai-compat", "openai-compat", com.scivicslab.chatui.agent.ToolSet.FULL, "Local LLM"),
                    new ProviderOption("claude", "claude", com.scivicslab.chatui.agent.ToolSet.HARNESS, "Claude Code"),
                    new ProviderOption("claude:full", "claude", com.scivicslab.chatui.agent.ToolSet.FULL,
                            "Claude Code (harness tools off)"));
        }
        @Override public void setProvider(String projectId, String chatId, String kind,
                                          com.scivicslab.chatui.agent.ToolSet toolSet) {
            providerCalls.add(projectId + "/" + chatId + "=" + kind + ":" + toolSet.id());
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
        assertTrue(result.contains("error: 'gpt-9' is not one of the models of the default provider; available:\n"
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
        Scripted caller = new Scripted(List.of("thanks"), List.of());
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
        while (caller.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, callee.prompts.size(), "the target got the prompt");
        assertTrue(callee.prompts.get(0).endsWith("[request project1/chat-01:s7:t3:r1 from project1/chat-01]\nquestion"),
                callee.prompts.get(0));
        assertEquals(1, caller.prompts.size(), "the reply came to the caller as a new message");
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

    private static ActorRef<PromptQueue> queueOf(ChatSessionIIAR chat) {
        ActorRef<PromptQueue> q = chat.createChild(chat.getName().replace(".chat", ".queue"), new PromptQueue());
        q.tellNow(x -> x.setSelf(q)).join();
        return q;
    }

    /**
     * chat-02 is asked by chat-01, hands the work to chat-03 and chat-04, and writes its report in the
     * turn after both replies are back. chat-01 gets exactly one reply, and it is that report
     * ({@code ReportReachesTheAskerAfterDelegation_261107_oo01}).
     */
    @Test
    void theAskerGetsOneReplyAndItIsTheReportWrittenAfterEveryChildRequestReturned() throws Exception {
        IIActorSystem system = new IIActorSystem("conversation-tools-delegation");
        FakeControls controls = new FakeControls();
        Scripted asker = new Scripted(List.of("thanks"), List.of());
        Scripted supervisor = new Scripted(List.of(
                invoke("ask_chat", "chatId", "03", "prompt", "job A"),
                invoke("ask_chat", "chatId", "04", "prompt", "job B"),
                "sent both",
                "one back",
                "final report"), List.of());
        ChatSessionIIAR one = chat(system, "01", asker, controls);
        ChatSessionIIAR two = chat(system, "02", supervisor, controls);
        ChatSessionIIAR three = chat(system, "03", new Scripted(List.of("A done"), List.of()), controls);
        ChatSessionIIAR four = chat(system, "04", new Scripted(List.of("B done"), List.of()), controls);
        for (ChatSessionIIAR c : List.of(one, two, three, four)) queueOf(c);

        AskChatTool.send(system, "project1", "01", "02", "do the work", "project1/chat-01:s7:t3:r1");

        long deadline = System.currentTimeMillis() + 20_000;
        while (supervisor.prompts.size() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        while (asker.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);
        assertEquals(5, supervisor.prompts.size(), "the supervisor answered both replies: " + supervisor.prompts);
        assertEquals(1, asker.prompts.size(), "one reply only: " + asker.prompts);
        String reply = asker.prompts.get(0);
        assertTrue(reply.contains("[reply to request project1/chat-01:s7:t3:r1, from project1/chat-02:t3]\nfinal report"),
                reply);
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

    @Test
    void askChatToAConversationThatDoesNotExistIsRefusedAtOnce() {
        IIActorSystem system = new IIActorSystem("conversation-tools-ask-missing");
        chat(system, "01", new Scripted(List.of("unused"), List.of()), new FakeControls());
        assertEquals("error: chat not found: 09", AskChatTool.send(system, "project1", "01", "09", "question", "project1/chat-01:t1:r1"));
        assertEquals("error: cannot ask_chat yourself", AskChatTool.send(system, "project1", "01", "01", "question", "project1/chat-01:t1:r1"));
    }
}
