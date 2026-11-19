package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What {@code accept_reply} answers says plainly what happened to the request: it was open and is now closed, its
 * reply had already closed it and the acceptance is recorded, or it was not open and nothing changed
 * ({@code TurnWithoutAnAnswerTellsTheAsker_261119_oo01}). "no longer holds the request you received open" read as if
 * the request had been closed before the call.
 */
@Tag("TurnWithoutAnAnswerTellsTheAsker_261119_oo01")
class AcceptReplyWordingTest {

    /** Answers each prompt with what the next function of its script makes of it; records every prompt. */
    private static final class Scripted implements LlmProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final List<Function<String, String>> script;
        Scripted(List<Function<String, String>> script) { this.script = script; }
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "m"; }
        @Override public void setModel(String m) { }
        @Override public void cancel() { }
        @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            prompts.add(prompt);
            emitter.accept(ChatEvent.delta(script.get(Math.min(prompts.size() - 1, script.size() - 1)).apply(prompt)));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    private static String invoke(String name, String... kv) {
        StringBuilder sb = new StringBuilder("<invoke name=\"" + name + "\">\n");
        for (int i = 0; i < kv.length; i += 2) {
            sb.append("<parameter name=\"").append(kv[i]).append("\">").append(kv[i + 1]).append("</parameter>\n");
        }
        return sb.append("<reason>test</reason>\n</invoke>").toString();
    }

    private static ChatSessionIIAR chat(IIActorSystem system, String chatId, LlmProvider provider) {
        String name = "project1/chat-" + chatId;
        ChatSessionIIAR iiar = new ChatSessionIIAR(name + ".chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setChatIdentity("project1", chatId);
            c.setReminderDecisionEnabled(false);
        }).join();
        ActorRef<PromptQueue> queue = iiar.createChild(name + ".queue", new PromptQueue());
        queue.tellNow(q -> q.setSelf(queue)).join();
        return iiar;
    }

    private static void say(IIActorSystem system, ChatSessionIIAR chat, String text) {
        ActorRef<PromptQueue> queue = system.getActor(chat.getName().replace(".chat", ".queue"));
        queue.tell(q -> q.enqueue(text, null, "queue", e -> { }, chat.asChatSessionRef(), "human"));
    }

    /** The result of the accept_reply call, as the model reads it in its next prompt. */
    private static String acceptResult(Scripted model, int prompts) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (model.prompts.size() < prompts && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue(model.prompts.size() >= prompts, "the model read the tool's result: " + model.prompts.size());
        String last = model.prompts.get(prompts - 1);
        Matcher m = Pattern.compile("(ok: [^\\n]*)").matcher(last);
        assertTrue(m.find(), last);
        return m.group(1).strip();
    }

    @Test
    void acceptReply_onARequestStillOut_saysItWasOpenAndIsNowClosed() throws Exception {
        IIActorSystem system = new IIActorSystem("accept-reply-open");
        try {
            String[] sent = {null};
            Pattern sentAs = Pattern.compile("as request (\\S+?)[;,]");
            Scripted model = new Scripted(List.of(
                    p -> invoke("ask_chat", "chatId", "03", "prompt", "a long job"),
                    p -> { Matcher m = sentAs.matcher(p); if (m.find()) sent[0] = m.group(1); return "Sent."; },
                    p -> invoke("accept_reply", "requestId", sent[0]),
                    p -> "Done."));
            ChatSessionIIAR two = chat(system, "02", model);
            chat(system, "03", new Scripted(List.of(p -> {
                try { Thread.sleep(30_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "late";
            })));
            say(system, two, "ask chat-03 for the job");
            long deadline = System.currentTimeMillis() + 10_000;
            while ((sent[0] == null || two.isBusyDirect()) && System.currentTimeMillis() < deadline) Thread.sleep(20);
            say(system, two, "we no longer need chat-03's answer");

            assertEquals("ok: request " + sent[0] + " was still open; it is now closed", acceptResult(model, 4));
        } finally {
            system.terminate();
        }
    }

    @Test
    void acceptReply_onTheReplyThisTurnBeganWith_saysItsReplyClosedItAndTheAcceptanceIsRecorded() throws Exception {
        IIActorSystem system = new IIActorSystem("accept-reply-answered");
        try {
            String request = "project1/chat-02:s1:t1:r1";
            Scripted model = new Scripted(List.of(p -> invoke("accept_reply", "requestId", request), p -> "Done."));
            chat(system, "02", model);
            chat(system, "03", new Scripted(List.of(p -> "unused")));
            AskChatTool.deliverReplyTo(system, "project1/chat-02", "project1/chat-03", request, false,
                    new TurnReply("the report", "[ended: answered; model calls: 1; tool calls: 0]", "project1/chat-03:t1"), null);

            assertEquals("ok: request " + request + " was already closed by its reply; your acceptance of that reply is recorded",
                    acceptResult(model, 2));
        } finally {
            system.terminate();
        }
    }

    @Test
    void acceptReply_onARequestThatIsNotOpen_saysNothingChanged() throws Exception {
        IIActorSystem system = new IIActorSystem("accept-reply-not-open");
        try {
            String request = "project1/chat-02:s1:t9:r9";
            Scripted model = new Scripted(List.of(p -> invoke("accept_reply", "requestId", request), p -> "Done."));
            ChatSessionIIAR two = chat(system, "02", model);
            say(system, two, "accept that old request");

            assertEquals("ok: request " + request + " was not open; nothing changed", acceptResult(model, 2));
        } finally {
            system.terminate();
        }
    }
}
