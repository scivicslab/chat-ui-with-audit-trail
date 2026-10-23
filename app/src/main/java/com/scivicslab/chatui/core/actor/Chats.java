package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.AskChatTool;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * What a job's workflow does with the instance's conversations: asks one a question and waits for its reply, empties
 * its history, stops it, and puts it on a model, an agent loop, a provider kind, thinking or not, an effort. Each
 * conversation is named in full ({@code project1/chat-02}). Holds nothing that changes, so every job uses the one
 * instance, registered as the actor {@code chats}; what a job needs to keep (a reply) it keeps in its own state.
 */
public final class Chats {

    /**
     * Long, for the same reason {@code ChatSession}'s babysitter phases use one: the conversation runs a whole turn
     * inside the call, and a turn that searches the web and reads what it found takes minutes
     * ({@code ChainedRolesInAPlan_260913_oo01}).
     */
    static final int ASK_TIMEOUT_SECONDS = 1800;

    /**
     * How a conversation is put on another provider kind. Changing the kind builds a provider from a plugin the
     * instance was started with, which is {@code ChatUiActorSystem}'s work; without it a kind cannot be changed
     * ({@code ChooseTheLlmPerRole_260915_oo01}).
     */
    public interface ProviderChanger {
        /**
         * @param chatName the conversation's full actor name
         * @param kind     the provider kind, e.g. {@code claude}
         * @param tools    the tool set, or {@code ""} for the kind's own default
         * @return what went wrong, or {@code null} when the change was made
         */
        String change(String chatName, String kind, String tools);
    }

    private final IIActorSystem system;
    private final ActorRef<CallWatchdog> watchdog;
    private final ProviderChanger providerChanger;

    /**
     * @param watchdog        the {@link CallWatchdog} a question is registered with while it waits
     * @param providerChanger how to change a conversation's provider kind; {@code null} to refuse to
     */
    public Chats(IIActorSystem system, ActorRef<CallWatchdog> watchdog, ProviderChanger providerChanger) {
        this.system = system;
        this.watchdog = watchdog;
        this.providerChanger = providerChanger;
    }

    /**
     * Sends a prompt to a conversation and waits for its reply.
     *
     * @param waiter who waits, the name the {@link CallWatchdog} records the wait under: the job's own actor name,
     *               {@code jexl:self} in a workflow
     * @return the reply
     * @throws IllegalStateException when the conversation is not there, or gave no reply
     */
    public String ask(String chat, String prompt, String waiter) {
        require(chat, "chat");
        require(prompt, "prompt");
        require(waiter, "waiter");
        String reply = AskChatTool.askQualified(system, watchdog, waiter.strip(), chat.strip(), prompt, ASK_TIMEOUT_SECONDS);
        if (reply == null) throw new IllegalStateException("no reply from " + chat.strip());
        if (reply.startsWith("error:")) throw new IllegalStateException(reply);
        return reply;
    }

    /**
     * Empties a conversation's history, so the next prompt starts from nothing
     * ({@code SelfContainedPromptsPerCriterion_260913_oo01}): each prompt of a checklist run carries the rule and the
     * whole text, and history left to accumulate pushes the model past its window.
     */
    public String clear(String chat) {
        session(chat).tell(a -> ((ChatSession) a).clearHistory()).join();
        return "cleared the history of " + chat.strip();
    }

    /**
     * Ends a conversation: its recorded session and the history it holds. Removing its actor is not enough on its
     * own, since a running session in the I/O log is reopened at the next start; the lines it wrote stay.
     */
    public String end(String chat) {
        session(chat).tell(a -> ((ChatSession) a).clearHistory()).join();
        return "ended the conversation " + chat.strip();
    }

    /**
     * Stops whatever a conversation is in the middle of, as the Cancel button does: a job that has stopped waiting
     * has to stop the work too ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}).
     */
    public String stop(String chat) {
        session(chat).tellNow(a -> ((ChatSession) a).cancel()).join();
        return "told " + chat.strip() + " to stop";
    }

    /** Puts a conversation on a model; blank leaves it as it was ({@code ChooseTheLlmPerRole_260915_oo01}). */
    public String setModel(String chat, String model) {
        require(chat, "chat");
        if (blank(model)) return "no model named; " + chat.strip() + " stays as it was";
        provider(chat).tell(p -> p.setModel(model.strip())).join();
        return chat.strip() + " runs on " + model.strip();
    }

    /**
     * Puts a conversation on another agent loop, which also decides how each of its prompts is built
     * ({@code AgentLoopDecidesThePrompt_261020_oo01}); blank leaves it as it was.
     */
    public String setAgentLoop(String chat, String loop) {
        require(chat, "chat");
        if (blank(loop)) return "no agent loop named; " + chat.strip() + " stays as it was";
        String refused = session(chat).setAgentLoopWorkflowFile(loop.strip());
        if (refused != null) throw new IllegalStateException(refused);
        return chat.strip() + " runs " + loop.strip();
    }

    /**
     * Says whether a conversation asks its model to skip the thinking phase
     * ({@code ThinkingAndEffortAreConversationSettings_260917_oo01}); blank leaves it as it was.
     */
    public String setNoThink(String chat, String value) {
        require(chat, "chat");
        if (blank(value)) return "nothing said about thinking; " + chat.strip() + " stays as it was";
        boolean noThink = Boolean.parseBoolean(value.strip());
        session(chat).tell(a -> ((ChatSession) a).setNoThink(noThink)).join();
        return chat.strip() + (noThink ? " answers without thinking" : " thinks as it did");
    }

    /** Says how hard a conversation's harness works on an answer, where it has such a setting; blank leaves it as it was. */
    public String setEffort(String chat, String effort) {
        require(chat, "chat");
        if (blank(effort)) return "no effort named; " + chat.strip() + " stays as it was";
        provider(chat).tell(p -> p.setEffort(effort.strip())).join();
        return chat.strip() + " works at " + effort.strip();
    }

    /**
     * Puts a conversation on a provider kind ({@code openai-compat}, {@code claude}, {@code codex}); blank leaves it
     * as it was. A kind this instance was not started with is a failure, not a silent fallback.
     *
     * @param tools the tool set, or blank for the kind's own default
     */
    public String setProvider(String chat, String kind, String tools) {
        require(chat, "chat");
        if (blank(kind)) return "no provider named; " + chat.strip() + " stays as it was";
        if (providerChanger == null) {
            throw new IllegalStateException("this instance cannot change a provider kind; name a model instead, or set the provider on "
                    + chat.strip() + " before running");
        }
        String why = providerChanger.change(chat.strip(), kind.strip(), tools == null ? "" : tools.strip());
        if (why != null) throw new IllegalStateException(why);
        return chat.strip() + " talks to " + kind.strip();
    }

    private ChatSessionIIAR session(String chat) {
        require(chat, "chat");
        Object s = system.getIIActor(chat.strip() + ".chat");
        if (!(s instanceof ChatSessionIIAR c)) throw new IllegalStateException("chat not found: " + chat.strip());
        return c;
    }

    private ActorRef<LlmProvider> provider(String chat) {
        ActorRef<LlmProvider> p = system.getActor(chat.strip() + ".chat.provider");
        if (p == null) throw new IllegalStateException("chat not found: " + chat.strip());
        return p;
    }

    private static void require(String value, String what) {
        if (blank(value)) throw new IllegalArgumentException(what + " is required");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
