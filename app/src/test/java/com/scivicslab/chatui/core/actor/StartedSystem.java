package com.scivicslab.chatui.core.actor;

/** Starts a {@link ChatUiActorSystem} without Quarkus, for tests outside this package. */
public final class StartedSystem {

    private StartedSystem() {}

    /** @return a system after its start-up ({@code init}), with project1 and its chat-01 */
    public static ChatUiActorSystem start() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        return system;
    }
}
