package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for images attached via {@link ChatSession#start(String, String, Consumer,
 * ActorRef, CompletableFuture, String, boolean, List)} reaching the {@link ProviderContext}
 * passed to the provider on the turn's first LLM call — mirrors {@link ChatSessionNoThinkTest}'s
 * fake-provider approach.
 */
class ChatSessionImagesTest {

    private static final class RecordingProvider implements LlmProvider {
        volatile List<String> lastImages;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}

        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            lastImages = ctx.imageDataUrls();
            emitter.accept(ChatEvent.delta("final answer, no tool call"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static RecordingProvider runTurn(List<String> images) {
        RecordingProvider provider = new RecordingProvider();
        IIActorSystem system = new IIActorSystem("chat-session-images-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef =
                iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();

        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("what is this?", null, event -> {}, chatRef, new CompletableFuture<>(), null, false, images);
            c.runUntilEnd();
        }).join();
        return provider;
    }

    @Test
    void start_withImages_reachesProviderContext() {
        RecordingProvider provider = runTurn(List.of("data:image/png;base64,QUJD"));
        assertEquals(List.of("data:image/png;base64,QUJD"), provider.lastImages);
    }

    @Test
    void start_withoutImages_reachesProviderAsEmpty() {
        RecordingProvider provider = runTurn(List.of());
        assertTrue(provider.lastImages.isEmpty());
    }
}
