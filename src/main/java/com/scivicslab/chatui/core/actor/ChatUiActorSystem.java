package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.openaicompat.OpenAiCompatProvider;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.RootIIAR;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Actor-system holder for {@code chat-ui-with-audit-trail}.
 *
 * <p>Builds one {@link IIActorSystem} whose auto-created {@link RootIIAR} carries a
 * {@link ConversationTab} per open conversation, and exposes the tree through
 * {@link #getActorTree()} for the Actors tab.</p>
 *
 * <p>Each {@link ConversationTab} owns one {@link ChatSessionIIAR} (wrapping a
 * {@link ChatSession}) and one {@link PromptQueue}, wired per
 * {@code ChatSessionIIAR_260810_oo01} "ConversationTab への接続" — stage 1: no agent loop,
 * no StallMonitor, {@code openai-compat} only (see {@code ChatSessionPorting_260823_oo01}).</p>
 */
@ApplicationScoped
public class ChatUiActorSystem {

    private static final Logger LOG = Logger.getLogger(ChatUiActorSystem.class.getName());

    // Field initializers (not just @ConfigProperty defaultValue) so plain `new ChatUiActorSystem()`
    // in a pure unit test — no CDI container, per this project's testing policy — still has usable
    // values; CDI overwrites these when the bean is actually managed.
    @ConfigProperty(name = "chat-ui.servers")
    List<String> servers = List.of("http://localhost:8000");

    @ConfigProperty(name = "chat-ui.default-model", defaultValue = "default")
    String defaultModel = "default";

    private IIActorSystem actorSystem;
    private final Map<String, ActorRef<ConversationTab>> tabs = new ConcurrentHashMap<>();
    private final Map<String, ChatSessionIIAR> chatSessions = new ConcurrentHashMap<>();

    /**
     * Initializes the actor system and seeds a small tree so the Actors tab has something to show.
     */
    @PostConstruct
    void init() {
        actorSystem = new IIActorSystem("chat-ui");
        createTab("alpha");
        createTab("beta");
        LOG.info("Actor system initialised with " + tabs.size() + " conversation tabs");
    }

    /**
     * Creates, or returns the existing, {@link ConversationTab} for {@code tabId} as a child of
     * ROOT, together with its {@link ChatSessionIIAR} and {@link PromptQueue}.
     *
     * @param tabId conversation tab identifier
     * @return the tab's actor reference
     */
    public synchronized ActorRef<ConversationTab> createTab(String tabId) {
        ActorRef<ConversationTab> existing = tabs.get(tabId);
        if (existing != null) {
            return existing;
        }
        ActorRef<ConversationTab> tabRef =
                actorSystem.getRoot().createChild("tab-" + tabId, new ConversationTab());
        tabs.put(tabId, tabRef);

        // ChatSessionIIAR — manual IIActorRef bridge, since ConversationTab is a plain POJO and
        // cannot call addChildActor itself (ChatSessionIIAR_260810_oo01 "ConversationTab への接続").
        OpenAiCompatProvider provider = new OpenAiCompatProvider(servers, defaultModel);
        ChatSessionIIAR chatSessionIIAR = new ChatSessionIIAR(
                tabRef.getName() + ".chat", provider, Optional.empty(), null, actorSystem);
        chatSessionIIAR.setParentName(tabRef.getName());
        tabRef.getNamesOfChildren().add(chatSessionIIAR.getName());
        actorSystem.addIIActor(chatSessionIIAR);
        chatSessions.put(tabId, chatSessionIIAR);

        // provider child — created by the generating side, not by ChatSession itself
        // (ChatSessionPorting_260823_oo01 "なぜ init を無くしたか").
        LlmProvider providerAsLlmProvider = provider;
        ActorRef<LlmProvider> providerRef = chatSessionIIAR.<LlmProvider>createChild(
                chatSessionIIAR.getName() + ".provider", providerAsLlmProvider);
        chatSessionIIAR.tell(a -> ((ChatSession) a).setProviderName(providerRef.getName()));

        // PromptQueue — plain createChild, same as any other ConversationTab sibling.
        ActorRef<PromptQueue> promptQueueRef =
                tabRef.createChild(tabRef.getName() + ".queue", new PromptQueue());
        chatSessionIIAR.tell(a -> ((ChatSession) a).setPromptQueueName(promptQueueRef.getName()));

        return tabRef;
    }

    /**
     * Returns the {@link ChatSessionIIAR} for {@code tabId}, or {@code null} if none was created.
     *
     * @param tabId conversation tab identifier
     * @return the session's IIActorRef, or {@code null}
     */
    public ChatSessionIIAR getChatSession(String tabId) {
        return chatSessions.get(tabId);
    }

    /**
     * Returns the {@link ConversationTab} for {@code tabId}, or {@code null} if none was created.
     *
     * @param tabId conversation tab identifier
     * @return the tab's actor reference, or {@code null}
     */
    public ActorRef<ConversationTab> getTab(String tabId) {
        return tabs.get(tabId);
    }

    /**
     * Builds the actor tree from ROOT for the Actors tab.
     *
     * @return the root {@link ActorNode}
     */
    public ActorNode getActorTree() {
        if (actorSystem == null) {
            return new ActorNode("chat-ui", "IIActorSystem", false, List.of());
        }
        RootIIAR root = actorSystem.getRoot();
        return buildActorNode(root.getName(), root);
    }

    private ActorNode buildActorNode(String name, ActorRef<?> ref) {
        String type;
        try {
            @SuppressWarnings("unchecked")
            ActorRef<Object> r = (ActorRef<Object>) ref;
            type = r.askNow(o -> o.getClass().getSimpleName()).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            type = "?";
        }
        List<ActorNode> children = new ArrayList<>();
        for (String childName : new TreeSet<>(ref.getNamesOfChildren())) {
            ActorRef<?> childRef = actorSystem.getActor(childName);
            if (childRef == null) {
                // ChatSessionIIAR etc. live in iiActors, not actors (ChatSessionIIAR_260810_oo01
                // "getActorTree() に必要な追随修正").
                childRef = actorSystem.getIIActor(childName);
            }
            if (childRef == null) {
                continue;
            }
            children.add(buildActorNode(childName, childRef));
        }
        return new ActorNode(name, type, ref.isAlive(), children);
    }
}
