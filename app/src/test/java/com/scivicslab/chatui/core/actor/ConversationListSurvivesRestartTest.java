package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which conversations a project has comes back after a restart from the project's own list
 * ({@code EachActorRecordsItsOwnState_261118_oo01}, step 5): a nested conversation comes back under the one it was
 * nested under, a conversation whose history was cleared before it ran again comes back, and one a project workflow
 * ended with {@code chats.end} does not.
 */
class ConversationListSurvivesRestartTest {

    private static ActorNode find(ActorNode node, String name) {
        if (name.equals(node.name())) return node;
        for (ActorNode child : node.children()) {
            ActorNode found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static List<String> childNames(ChatUiActorSystem system, String name) {
        ActorNode node = find(system.getActorTree(), name);
        return node == null ? List.of() : node.children().stream().map(ActorNode::name).toList();
    }

    @Test
    void aNestedConversationComesBackUnderItsParent(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("nested"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1", "02");
            first.createChat("project1", "03", "02");
            assertTrue(childNames(first, "project1/chat-02").contains("project1/chat-03"));

            ChatUiActorSystem second = systems.start();
            assertNotNull(second.getChatSession("project1", "03"));
            assertTrue(childNames(second, "project1/chat-02").contains("project1/chat-03"),
                    "chat-03 is back under chat-02: " + childNames(second, "project1/chat-02"));
            assertTrue(!childNames(second, "project1").contains("project1/chat-03"), "not under the project");
        }
    }

    @Test
    void aConversationClearedBeforeTheRestartComesBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("cleared"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            first.getChatSession("project1", "02").tell(a -> ((ChatSession) a).clearHistory()).join();

            ChatUiActorSystem second = systems.start();
            assertNotNull(second.getChatSession("project1", "02"), "a cleared conversation still exists");
            assertEquals("03", second.createChat("project1"), "and its number stays taken");
        }
    }

    @Test
    void aConversationEndedByAProjectWorkflowStaysGone(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("ended"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            assertEquals("03", first.createChat("project1"));
            Project.JobView job = run(first.getProject("project1"), """
                    name: end-one
                    steps:
                      - states: ["0", "1"]
                        actions:
                          - actor: chats
                            method: end
                            arguments: {chat: project1/chat-03}
                      - states: ["1", "end"]
                        actions:
                          - {actor: this, method: finish, arguments: ["done"], execution: direct}
                    """);
            assertEquals(Project.FINISHED, job.state(), "the workflow ran: " + job);

            ChatUiActorSystem second = systems.start();
            assertNotNull(second.getChatSession("project1", "02"));
            assertNull(second.getChatSession("project1", "03"), "the ended conversation is not built again");
            assertEquals("04", second.createChat("project1"), "and its number is not reused");
        }
    }

    private static Project.JobView run(ActorRef<Project> project, String yaml) throws Exception {
        Project.JobView started = project.ask(p -> p.submitJob("end-one", yaml, Map.of())).join();
        for (int i = 0; i < 400; i++) {
            Project.JobView job = project.ask(p -> p.job(started.jobId())).join();
            if (!Project.QUEUED.equals(job.state()) && !Project.RUNNING.equals(job.state())) return job;
            Thread.sleep(25);
        }
        return project.ask(p -> p.job(started.jobId())).join();
    }
}
