package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file written with {@code write} has the content the model wrote, ``` blocks included. On project9/chat-01
 * (GLM-5.3-Flash), session 140, turn 46, the chapters of the fcitx5 book lost every fenced source quote: the
 * parser cut the values out of the reply after deleting its fenced blocks. Both replies here are the turn's own.
 */
class WriteKeepsCodeFencesTest {

    @TempDir Path dir;

    private static String resource(String path) {
        try (InputStream in = WriteKeepsCodeFencesTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers the first prompt with {@code reply}, every later one with a plain answer. */
    private static final class OneCall implements LlmProvider {
        private final String reply;
        final List<String> prompts = new CopyOnWriteArrayList<>();
        OneCall(String reply) { this.reply = reply; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(prompts.size() == 1 ? reply : "Written."));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private void runTurn(String reply) {
        OneCall provider = new OneCall(reply);
        IIActorSystem system = new IIActorSystem("write-fences-test");
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project9", "01");
            c.setFileScope(new FileAccessScope(dir, List.of()));
        }).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        chatRef.tellNow(c -> {
            c.start("write the chapter", null, e -> { }, chatRef, new CompletableFuture<>(), null, false);
            c.runUntilEnd();
        }).join();
    }

    @Test
    void step28_glmSpelling_theFileHasTheCppBlock() throws Exception {
        runTurn(resource("/replies/glm-turn46-step28.txt"));

        assertEquals("before\n\n```cpp\nint main() { return 0; }\n```\n\nafter",
                Files.readString(dir.resolve("tmp-fence-test.md")));
    }

    @Test
    void step12_invokeSpelling_theChapterKeepsEveryFencedQuote() throws Exception {
        String reply = resource("/replies/glm-turn46-step12.txt");
        String open = "<parameter name=\"content\">";
        String content = reply.substring(reply.indexOf(open) + open.length(),
                reply.indexOf("</parameter>\n<parameter name=\"reason\">")).strip();
        // The chapter goes under the temporary directory, at the path the model gave.
        Path written = dir.resolve("doc_Base010/docs/fcitx5-internals/010_InstanceBirth_261115_oo01/"
                + "010_InstanceBirth_261115_oo01.md");

        runTurn(reply);

        String file = Files.readString(written);
        assertEquals(content, file);
        assertEquals(4, file.split("```", -1).length - 1, "both fenced quotes are in the file");
    }
}
