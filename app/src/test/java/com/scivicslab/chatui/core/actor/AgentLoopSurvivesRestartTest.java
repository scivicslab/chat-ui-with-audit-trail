package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent loop put on a conversation comes back after a restart, whether it was named by file (the REST call
 * and the screen's Agent Loop tab) or given as YAML ({@code set_agent_loop}), and whether or not the
 * conversation has run a turn ({@code ConversationRestoreOnRestart_260904_oo01}).
 */
class AgentLoopSurvivesRestartTest {

    private static String loopName(ChatUiActorSystem system, String chatId) throws Exception {
        return system.getChatSession("project1", chatId)
                .<String>ask(i -> ((ChatSession) i).getCode().getName()).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aLoopNamedByFileComesBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("loop-file"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertNull(first.setAgentLoopFile("project1", "02", "plain-text-loop.yaml"));

            ChatUiActorSystem second = systems.start();
            assertEquals("plain-text-loop.yaml", second.getChatSession("project1", "02").getAgentLoopWorkflowFile());
            assertEquals("plain-text-loop", loopName(second, "02"));
        }
    }

    @Test
    void aLoopGivenAsYamlComesBack(@TempDir Path dir) throws Exception {
        String yaml;
        try (InputStream in = AgentLoopSurvivesRestartTest.class.getResourceAsStream("/workflows/plain-text-loop.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceFirst("(?m)^name: plain-text-loop$", "name: loop-given-as-yaml");
        }
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("loop-yaml"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertTrue(first.setAgentLoop("project1", "02", yaml).startsWith("ok"));
            assertEquals("loop-given-as-yaml", loopName(first, "02"));

            ChatUiActorSystem second = systems.start();
            assertEquals("loop-given-as-yaml", loopName(second, "02"), "the YAML put on it came back");
        }
    }
}
