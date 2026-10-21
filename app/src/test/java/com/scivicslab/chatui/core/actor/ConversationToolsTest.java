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
    private static final class Scripted implements LlmProvider {
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
        @Override public String createChat(String projectId) {
            created.add(projectId);
            return "06";
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

        String sent = AskChatTool.send(system, "project1", "01", "02", "question");
        assertTrue(sent.startsWith("sent to project1/chat-02"), sent);

        long deadline = System.currentTimeMillis() + 20_000;
        while (caller.prompts.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, callee.prompts.size(), "the target got the prompt");
        assertTrue(callee.prompts.get(0).endsWith("question"), callee.prompts.get(0));
        assertEquals(1, caller.prompts.size(), "the reply came to the caller as a new message");
        String reply = caller.prompts.get(0);
        assertTrue(reply.contains("[reply from project1/chat-02 to your ask_chat]\nthe answer"), reply);
        assertTrue(reply.endsWith("[ended: answered; model calls: 1; tool calls: 0]"), reply);

        ActorRef<CallWatchdog> watchdog = two.createChild("watchdog-for-test", new CallWatchdog());
        String viaPlan = AskChatTool.askQualified(system, watchdog, "project1/chat-01", "project1/chat-02",
                "question", 30);
        assertEquals("the answer", viaPlan);
    }

    @Test
    void askChatToAConversationThatDoesNotExistIsRefusedAtOnce() {
        IIActorSystem system = new IIActorSystem("conversation-tools-ask-missing");
        chat(system, "01", new Scripted(List.of("unused"), List.of()), new FakeControls());
        assertEquals("error: chat not found: 09", AskChatTool.send(system, "project1", "01", "09", "question"));
        assertEquals("error: cannot ask_chat yourself", AskChatTool.send(system, "project1", "01", "01", "question"));
    }
}
