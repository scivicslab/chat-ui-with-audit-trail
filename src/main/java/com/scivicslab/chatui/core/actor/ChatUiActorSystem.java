package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.RootIIAR;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Minimal actor-system holder for the from-scratch {@code chat-ui-with-audit-trail} rebuild.
 *
 * <p>This reduced form exists to verify that the right-pane Actors tab can render an actor tree.
 * It builds one {@link IIActorSystem} whose auto-created {@link RootIIAR} carries a few
 * {@link ConversationTab} children, and exposes the tree through {@link #getActorTree()}.</p>
 *
 * <p>The conversation actors (ChatActor / SseActor / QueueActor / BtwActor / McpClientActor) are
 * intentionally not created here. They are added later as the redesign proceeds.</p>
 */
@ApplicationScoped
public class ChatUiActorSystem {

    private static final Logger LOG = Logger.getLogger(ChatUiActorSystem.class.getName());

    private IIActorSystem actorSystem;
    private final Map<String, ActorRef<ConversationTab>> tabs = new ConcurrentHashMap<>();

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
     * Creates, or returns the existing, {@link ConversationTab} for {@code tabId} as a child of ROOT.
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
        return tabRef;
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
                continue;
            }
            children.add(buildActorNode(childName, childRef));
        }
        return new ActorNode(name, type, ref.isAlive(), children);
    }
}
