package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A model and an agent loop that a project workflow puts on a conversation (the {@code chats} actor's
 * {@code setModel} and {@code setAgentLoop}, which {@code build-team} uses) come back after a restart, as the same
 * changes made with {@code set_chat_model} and the Agent Loop tab do ({@code ConversationRestoreOnRestart_260904_oo01}).
 */
class WorkflowSettingsSurviveRestartTest {

    static final String WORKFLOW = """
            name: set-model-and-loop
            steps:
              - states: ["0", "1"]
                label: set-model
                actions:
                  - actor: chats
                    method: setModel
                    arguments: {chat: project1/chat-02, model: model-from-workflow}
              - states: ["1", "2"]
                label: set-loop
                actions:
                  - actor: chats
                    method: setAgentLoop
                    arguments: {chat: project1/chat-02, loop: plain-text-loop.yaml}
              - states: ["2", "end"]
                label: done
                actions:
                  - {actor: this, method: finish, arguments: ["done"], execution: direct}
            """;

    private static Project.JobView run(ActorRef<Project> project) throws Exception {
        Project.JobView started = project.ask(p -> p.submitJob("set-model-and-loop", WORKFLOW, Map.of())).join();
        for (int i = 0; i < 400; i++) {
            Project.JobView job = project.ask(p -> p.job(started.jobId())).join();
            if (!Project.QUEUED.equals(job.state()) && !Project.RUNNING.equals(job.state())) return job;
            Thread.sleep(25);
        }
        return project.ask(p -> p.job(started.jobId())).join();
    }

    @Test
    void aModelAndAnAgentLoopSetByAProjectWorkflowComeBackAfterARestart(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("workflow-settings"))) {
            ChatUiActorSystem first = systems.start();
            assertEquals("02", first.createChat("project1"));
            Project.JobView job = run(first.getProject("project1"));
            assertEquals(Project.FINISHED, job.state(), "the workflow ran: " + job);
            assertEquals("model-from-workflow", first.getChatSession("project1", "02").getModelDirect());
            assertEquals("plain-text-loop.yaml", first.getChatSession("project1", "02").getAgentLoopWorkflowFile());

            ChatUiActorSystem second = systems.start();
            ChatSessionIIAR back = second.getChatSession("project1", "02");
            assertEquals("model-from-workflow", back.getModelDirect(), "the model the workflow set came back");
            assertEquals("plain-text-loop.yaml", back.getAgentLoopWorkflowFile(), "the agent loop it set came back");
            assertEquals("plain-text-loop", back.<String>ask(i -> ((ChatSession) i).getCode().getName())
                    .get(5, TimeUnit.SECONDS));
        }
    }
}
