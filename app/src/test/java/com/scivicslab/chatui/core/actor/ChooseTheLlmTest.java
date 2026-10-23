package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job saying which LLM each of its roles runs on ({@code ChooseTheLlmPerRole_260915_oo01}).
 *
 * <p>Which model judged a document is part of what the run was. Left on the conversation, it has
 * to be read from that conversation's own record; named in the job's parameters, the run says it
 * itself, which is what an unattended run needs.</p>
 */
@DisplayName("PlanRunner — the LLM a role runs on")
class ChooseTheLlmTest {

    /** Remembers what it was told to be. */
    private static final class RecordingProvider implements LlmProvider {
        String model = "first-model";
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return model; }
        @Override public void setModel(String model) { this.model = model; }
        @Override public void cancel() { }
        @Override public void sendPrompt(String p, String m, Consumer<ChatEvent> e, ProviderContext c) { }
    }

    private IIActorSystem system;
    private ChatsIIAR chats;
    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("choose-llm-test");
        chats = new ChatsIIAR("chats", new Chats(system, null, null), system);
        system.addIIActor(chats);

        provider = new RecordingProvider();
        ActorRef<Object> conversationSquad = system.actorOf("project1/chat-02", new Object());
        ActorRef<LlmProvider> providerRef =
                conversationSquad.createChild("project1/chat-02.chat.provider", (LlmProvider) provider);
        assert providerRef != null;
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void theModelNamedInTheJobIsTheOneTheRoleRunsOn() {
        ActionResult result = chats.callByActionName("setModel", new org.json.JSONObject().put("chat", "project1/chat-02").put("model", "Qwen/Qwen3.8-27B").toString());

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("Qwen/Qwen3.8-27B", provider.getCurrentModel());
    }

    /** Nothing named means the conversation keeps whatever it was on. */
    @Test
    void nothingNamedLeavesTheConversationAsItWas() {
        ActionResult result = chats.callByActionName("setModel", new org.json.JSONObject().put("chat", "project1/chat-02").put("model", "").toString());

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("first-model", provider.getCurrentModel());
    }

    @Test
    void aConversationThatIsNotThereIsSaidSoRatherThanPassedOver() {
        ActionResult result = chats.callByActionName("setModel", new org.json.JSONObject().put("chat", "project1/chat-nobody").put("model", "some-model").toString());

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("project1/chat-nobody"), result.getResult());
    }

    /** Without the wiring that can change a provider kind, the step says so instead of pretending. */
    @Test
    void aProviderKindCannotBeChangedWithoutTheWayToDoIt() {
        ActionResult result = chats.callByActionName("setProvider", new org.json.JSONObject().put("chat", "project1/chat-02").put("kind", "claude").put("tools", "").toString());

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("provider"), result.getResult());
    }

    @Test
    void nothingNamedIsNotAProviderChangeAtAll() {
        assertTrue(chats.callByActionName("setProvider", new org.json.JSONObject().put("chat", "project1/chat-02").put("kind", "").put("tools", "").toString()).isSuccess());
    }
}
