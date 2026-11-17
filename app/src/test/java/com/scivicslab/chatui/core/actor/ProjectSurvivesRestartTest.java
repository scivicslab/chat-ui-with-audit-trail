package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.scivicslab.chatui.core.iolog.IoLogView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's name and working directory come back after a restart, and the restored instructions reach its
 * conversations; a restart writes no entry of its own ({@code EachActorRecordsItsOwnState_261118_oo01}, step 4).
 */
class ProjectSurvivesRestartTest {

    @Test
    void theNameAndTheDirectoryComeBackAndARestartWritesNothing(@TempDir Path dir) throws Exception {
        Path work = Files.createDirectory(dir.resolve("work"));
        Files.writeString(work.resolve(Project.AGENTS_FILE), "RESTORED-INSTRUCTIONS");
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("project-restart"))) {
            ChatUiActorSystem first = systems.start();
            assertTrue(first.setProjectName("project1", "Restart check").startsWith("ok"));
            assertTrue(first.setProjectWorkingDir("project1", work).startsWith("ok"));
            IoLogView view = IoLogView.over(systems.log);
            int written = view.settingsHistory(systems.log.findProjectSession("project1")).size();

            ChatUiActorSystem second = systems.start();
            assertEquals("Restart check", second.getProjectName("project1"));
            assertEquals(work, second.getProject("project1").ask(Project::getWorkingDir).get(5, TimeUnit.SECONDS));
            String handed = second.getChatSession("project1", "01")
                    .<String>ask(a -> ((ChatSession) a).getProjectInstructions()).get(5, TimeUnit.SECONDS);
            assertEquals("RESTORED-INSTRUCTIONS", handed, "the conversation got the directory's instructions");
            assertEquals(written, view.settingsHistory(systems.log.findProjectSession("project1")).size(),
                    "the restart wrote no settings entry");
        }
    }
}
