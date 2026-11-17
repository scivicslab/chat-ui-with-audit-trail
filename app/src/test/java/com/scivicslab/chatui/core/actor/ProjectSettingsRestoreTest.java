package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.scivicslab.chatui.core.iolog.IoLogView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A log written by the code before the project-settings actor existed restores through it unchanged: the
 * project's name, its organization and its role edges come back.
 */
class ProjectSettingsRestoreTest {

    @Test
    void aLogWrittenByTheEarlierCodeRestores(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("earlier-log"))) {
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "01"));
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "02"));
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "03"));
            long session = systems.log.ensureProjectSession("project1");
            // Exactly the JSON ChatUiActorSystem.recordProjectProperties wrote before.
            systems.log.record(session, "agent", IoLogView.SETTINGS_LABEL,
                    "{\"name\":\"restored name\",\"workingDir\":null,"
                            + "\"organization\":{\"superiors\":{\"02\":\"01\",\"03\":\"02\"},\"liaison\":\"01\",\"maxSubordinates\":5},"
                            + "\"roles\":[{\"from\":\"project1/chat-02\",\"role\":\"worker\",\"to\":\"project1/chat-03\"}]}");

            ChatUiActorSystem system = systems.start();
            assertEquals("restored name", system.getProjectName("project1"));
            ConversationGraph g = system.conversationGraph("project1");
            assertTrue(g.superiorEdges().contains(new ConversationGraph.SuperiorEdge("01", "02")), g.superiorEdges().toString());
            assertTrue(g.superiorEdges().contains(new ConversationGraph.SuperiorEdge("02", "03")), g.superiorEdges().toString());
            assertEquals(List.of(new ConversationGraph.RoleEdge("02", "worker", "03", "project1")), g.roleEdges());
        }
    }
}
