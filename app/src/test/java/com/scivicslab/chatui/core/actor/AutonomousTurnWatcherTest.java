package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test: a turn the provider produced with no prompt (as the Claude Code CLI does when a background job
 * finishes) reaches the screen and the history once the watcher ticks, and the watcher leaves a busy conversation
 * alone.
 */
class AutonomousTurnWatcherTest {

    /** A provider holding one autonomous turn until it is drained. */
    private static final class AutonomousProvider implements LlmProvider {
        volatile boolean pending = true;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {}

        @Override public boolean supportsAutonomousEvents() { return true; }
        @Override public boolean hasAutonomousActivity() { return pending; }

        @Override
        public boolean drainAutonomousActivity(Consumer<ChatEvent> emitter) {
            if (!pending) return false;
            pending = false;
            emitter.accept(ChatEvent.delta("the background job finished"));
            emitter.accept(ChatEvent.result(null, 0, 0));
            return true;
        }
    }

    private record Fixture(AutonomousProvider provider, ChatSessionIIAR session, List<ChatEvent> screen) {
    }

    private static Fixture fixture() {
        AutonomousProvider provider = new AutonomousProvider();
        IIActorSystem system = new IIActorSystem("autonomous-turn-watcher-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> ((ChatSession) a).setProviderName(providerRef.getName())).join();
        List<ChatEvent> screen = new CopyOnWriteArrayList<>();
        iiar.asChatSessionRef().tellNow(c -> c.setSseEmitter(screen::add)).join();
        return new Fixture(provider, iiar, screen);
    }

    private static List<ChatSession.HistoryEntry> history(ChatSessionIIAR session) {
        return session.asChatSessionRef().ask(c -> c.getHistory(10)).join();
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) Thread.sleep(50);
    }

    @Test
    void aTickSurfacesTheAutonomousTurnOnTheScreenAndInTheHistory() throws InterruptedException {
        Fixture f = fixture();
        new AutonomousTurnWatcher(() -> List.of(f.session())).tick();
        waitFor(() -> !history(f.session()).isEmpty());

        List<ChatSession.HistoryEntry> h = history(f.session());
        assertEquals(1, h.size());
        assertEquals("assistant", h.get(0).role());
        assertEquals("the background job finished", h.get(0).content());
        assertTrue(f.screen().stream().anyMatch(e -> "delta".equals(e.type()) && "the background job finished".equals(e.content())));
    }

    @Test
    void withNothingPendingATickChangesNothing() throws InterruptedException {
        Fixture f = fixture();
        f.provider().pending = false;
        new AutonomousTurnWatcher(() -> List.of(f.session())).tick();
        Thread.sleep(300);
        assertTrue(history(f.session()).isEmpty());
        assertTrue(f.screen().isEmpty());
    }
}
