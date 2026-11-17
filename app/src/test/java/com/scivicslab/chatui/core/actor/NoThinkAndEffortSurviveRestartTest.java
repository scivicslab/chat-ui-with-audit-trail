package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;
import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conversation's "answer without thinking" (noThink) and effort survive a restart, in its own settings record
 * beside the provider and the model, whichever way they were set: the screen's Think box and Effort field (REST),
 * or a project workflow's {@code chats} actor ({@code ConversationSettingsRecord_260913_oo01}).
 */
class NoThinkAndEffortSurviveRestartTest {

    /** A provider kind that keeps an effort, as the Claude Code harness does. */
    static final class EffortKeepingFactory implements LlmProviderFactory {
        @Override public String kind() { return "fake"; }
        @Override public List<ProviderChoice> choices() { return List.of(new ProviderChoice("fake", ToolSet.FULL, "Fake")); }
        @Override public LlmProvider create(ProviderCreationContext ctx) {
            return new LlmProvider() {
                private String model = "opus";
                private String effort;
                @Override public String id() { return "fake"; }
                @Override public String displayName() { return "Fake"; }
                @Override public List<ModelEntry> getAvailableModels() { return List.of(new ModelEntry("opus", id(), null)); }
                @Override public String getCurrentModel() { return model; }
                @Override public void setModel(String m) { model = m; }
                @Override public void setEffort(String e) { effort = e; }
                @Override public String getEffort() { return effort; }
                @Override public void cancel() {}
                @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext c) {
                    emitter.accept(ChatEvent.delta("noted"));
                    emitter.accept(ChatEvent.result(null, 0, 0));
                }
            };
        }
    }

    private static final Consumer<ChatUiActorSystem> WITH_FAKE = s -> s.extraProviderFactories.add(new EffortKeepingFactory());

    static final String WORKFLOW = """
            name: set-thinking-and-effort
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: chats
                    method: setNoThink
                    arguments: {chat: project1/chat-02, value: "true"}
              - states: ["1", "2"]
                actions:
                  - actor: chats
                    method: setEffort
                    arguments: {chat: project1/chat-02, effort: high}
              - states: ["2", "end"]
                actions:
                  - {actor: this, method: finish, arguments: ["done"], execution: direct}
            """;

    private static Project.JobView run(ActorRef<Project> project) throws Exception {
        Project.JobView started = project.ask(p -> p.submitJob("set-thinking-and-effort", WORKFLOW, Map.of())).join();
        for (int i = 0; i < 400; i++) {
            Project.JobView job = project.ask(p -> p.job(started.jobId())).join();
            if (!Project.QUEUED.equals(job.state()) && !Project.RUNNING.equals(job.state())) return job;
            Thread.sleep(25);
        }
        return project.ask(p -> p.job(started.jobId())).join();
    }

    @Test
    void theThinkBoxAndTheEffortSetFromTheScreenComeBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("think-effort-screen"))) {
            ChatUiActorSystem first = systems.start(WITH_FAKE);
            assertEquals("02", first.createChat("project1"));
            first.setProvider("project1", "02", "fake", ToolSet.FULL);
            assertTrue(first.setNoThink("project1", "02", true));
            assertTrue(first.setEffort("project1", "02", "high"));

            ChatUiActorSystem second = systems.start(WITH_FAKE);
            ChatSessionIIAR back = second.getChatSession("project1", "02");
            assertEquals("fake", back.getProviderIdDirect());
            assertTrue(back.isNoThinkDirect(), "answers without thinking, as set");
            assertEquals("high", back.getEffortDirect(), "the effort it was set to");
        }
    }

    @Test
    void thinkingAndEffortSetByAProjectWorkflowComeBack(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("think-effort-workflow"))) {
            ChatUiActorSystem first = systems.start(WITH_FAKE);
            assertEquals("02", first.createChat("project1"));
            first.setProvider("project1", "02", "fake", ToolSet.FULL);
            Project.JobView job = run(first.getProject("project1"));
            assertEquals(Project.FINISHED, job.state(), "the workflow ran: " + job);
            assertTrue(first.getChatSession("project1", "02").isNoThinkDirect());
            assertEquals("high", first.getChatSession("project1", "02").getEffortDirect());

            ChatUiActorSystem second = systems.start(WITH_FAKE);
            ChatSessionIIAR back = second.getChatSession("project1", "02");
            assertTrue(back.isNoThinkDirect(), "the workflow's noThink came back");
            assertEquals("high", back.getEffortDirect(), "the workflow's effort came back");
        }
    }

    @Test
    void aRecordWrittenBeforeTheTwoWereKeptRestoresToTheDefaults(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("think-effort-old"))) {
            systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "01"));
            long session = systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", "02"));
            // A settings record as the earlier code wrote it: no noThink, no effort.
            systems.log.record(session, "agent", IoLogView.SETTINGS_LABEL,
                    "{\"provider\":\"fake\",\"tools\":\"full\",\"model\":\"opus\",\"temperature\":null,"
                            + "\"urgentWaitMinutes\":10,\"childReplyWarnMinutes\":5}");

            ChatUiActorSystem system = systems.start(WITH_FAKE);
            ChatSessionIIAR back = system.getChatSession("project1", "02");
            assertEquals("fake", back.getProviderIdDirect(), "the rest of the record restores as before");
            assertEquals("opus", back.getModelDirect());
            assertFalse(back.isNoThinkDirect(), "thinking, today's default");
            assertNull(back.getEffortDirect(), "no effort, today's default");
        }
    }
}
