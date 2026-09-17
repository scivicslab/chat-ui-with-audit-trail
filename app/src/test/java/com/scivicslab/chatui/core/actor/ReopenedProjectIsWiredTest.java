package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.scivicslab.chatui.core.iolog.IoLogStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project that comes back from the I/O log at start-up must be able to run jobs
 * ({@code ProjectsComeBackWiredOrNotAtAll_260917_oo01}). It was created and not bound, so it looked
 * complete -- its conversations were all there -- and refused every job with "project null is not
 * bound to an actor system".
 */
class ReopenedProjectIsWiredTest {

    @Test
    void aProjectBroughtBackFromTheLog_canStartAJob(@TempDir Path dir) {
        // A previous run of this program: one conversation of its own, recorded and still open.
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("reopen-test").toString());
        assertTrue(log.ensureSession("project7/chat-02") >= 0,
                "the conversation has to be recorded for the project to come back");

        ChatUiActorSystem system = new ChatUiActorSystem();
        system.ioLogStore = log;
        system.init();

        assertTrue(system.getProjectIds().contains("project7"),
                "expected project7 to come back, got " + system.getProjectIds());

        // Load-bearing: startJob is where an unbound project throws, before anything is run.
        String failure = system.getProject("project7")
                .ask(p -> {
                    try {
                        p.startJob("no-such-workflow", "steps: []", java.util.Map.of());
                        return "";
                    } catch (IllegalStateException e) {
                        return e.getMessage();
                    }
                }).join();

        assertEquals("", failure, "a reopened project must be bound to the actor system");
    }
}
