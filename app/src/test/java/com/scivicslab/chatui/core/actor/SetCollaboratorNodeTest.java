package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.scivicslab.chatui.agent.SetCollaboratorTool;
import com.scivicslab.chatui.core.iolog.SessionEntries;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import org.junit.jupiter.api.Test;

/**
 * {@code set_collaborator} with {@code node}: the role is played by a conversation on another chat-ui instance, named as
 * {@code ask_chat} names one there. A node this instance does not know is refused, with nothing changed.
 */
class SetCollaboratorNodeTest {

    @Test
    void aKnownNodeIsRecordedWithTheEdgeAndAnUnknownOneIsRefused() throws Exception {
        ActorSystem system = new ActorSystem("set-collaborator-node");
        try {
            ActorRef<RoleTable> roles = system.actorOf("project1/roles",
                    new RoleTable("project1", SessionEntries.ofProject(null, null, "project1")));
            String ok = SetCollaboratorTool.setCollaborator(roles, "project1/chat-01", "02", "worker", "project1/chat-01", "b", Set.of("b")::contains);
            assertTrue(ok.startsWith("ok:") && ok.contains("on node b"), ok);
            assertEquals(new RoleTable.Collaborator("project1/chat-01", "b"),
                    roles.ask(t -> t.collaboratorAt("project1/chat-02", "worker")).get(5, TimeUnit.SECONDS));

            String refused = SetCollaboratorTool.setCollaborator(roles, "project1/chat-01", "02", "reviewer", "project1/chat-01", "c", Set.of("b")::contains);
            assertTrue(refused.startsWith("error:") && refused.contains("c"), refused);
            assertEquals(null, roles.ask(t -> t.collaboratorAt("project1/chat-02", "reviewer")).get(5, TimeUnit.SECONDS));

            String local = SetCollaboratorTool.setCollaborator(roles, "project1/chat-01", "02", "reviewer", "03", null, Set.of("b")::contains);
            assertTrue(local.startsWith("ok:") && !local.contains("node"), local);

            // On another instance the collaborator is named with its project, as ask_chat names it there.
            String bare = SetCollaboratorTool.setCollaborator(roles, "project1/chat-01", "02", "tester", "01", "b", Set.of("b")::contains);
            assertTrue(bare.startsWith("error:") && bare.contains("must start with the project on that instance")
                    && bare.contains("\"project1/chat-01\""), bare);
            assertEquals(null, roles.ask(t -> t.collaboratorAt("project1/chat-02", "tester")).get(5, TimeUnit.SECONDS));
        } finally {
            system.terminate();
        }
    }
}
