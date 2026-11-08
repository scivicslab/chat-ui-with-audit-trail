package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.ToolSet;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.chatui.plugin.ProviderCreationContext;
import com.scivicslab.pojoactor.core.ActorRef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code build-team.yaml} run as a job on a real actor system, with providers that only answer "noted" and list two
 * models ({@code TeamBuiltByProjectWorkflow_261108_oo01}): the organization and the names it leaves, the message the
 * liaison receives, and nothing left behind when a step fails.
 */
@Tag("TeamBuiltByProjectWorkflowTests_261109_oo01")
@DisplayName("build-team: 実際のアクターシステムでチームを作る")
class BuildTeamWorkflowTest {

    /** Every prompt any fake provider was sent; only the liaison is ever sent one in these tests. */
    private static final List<String> PROMPTS = new CopyOnWriteArrayList<>();

    /** A provider kind that lists {@code opus} and {@code sonnet} and answers each prompt with "noted". */
    private static final class FakeFactory implements LlmProviderFactory {
        @Override public String kind() { return "fake"; }
        @Override public List<ProviderChoice> choices() { return List.of(new ProviderChoice("fake", ToolSet.FULL, "Fake")); }
        @Override public LlmProvider create(ProviderCreationContext ctx) {
            return new LlmProvider() {
                private String model = "opus";
                @Override public String id() { return "fake"; }
                @Override public String displayName() { return "Fake"; }
                @Override public List<ModelEntry> getAvailableModels() {
                    return List.of(new ModelEntry("opus", id(), null), new ModelEntry("sonnet", id(), null));
                }
                @Override public String getCurrentModel() { return model; }
                @Override public void setModel(String m) { model = m; }
                @Override public void cancel() {}
                @Override public void sendPrompt(String prompt, String m, Consumer<ChatEvent> emitter, ProviderContext c) {
                    PROMPTS.add(prompt);
                    emitter.accept(ChatEvent.delta("noted"));
                    emitter.accept(ChatEvent.result(null, 0, 0));
                }
            };
        }
    }

    private ChatUiActorSystem system;
    private ActorRef<Project> project;

    @BeforeEach
    void setUp() {
        PROMPTS.clear();
        system = new ChatUiActorSystem();
        system.init();
        system.getPluginRegistry().register(new FakeFactory());
        system.setProvider("project1", "01", "fake", ToolSet.FULL);
        project = system.getProject("project1");
        assertNotNull(project);
    }

    private String workflow() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/workflows/build-team.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> values(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("managers", "1");
        m.put("workersPerManager", "1");
        m.put("repeatersPerManager", "0");
        m.put("work", "");
        for (String key : List.of("managerProvider", "managerModel", "workerProvider", "workerModel",
                "repeaterProvider", "repeaterModel")) m.put(key, "");
        m.put("managerProvider", "fake");
        m.put("workerProvider", "fake");
        m.put("repeaterProvider", "fake");
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private Project.JobView run(Map<String, String> values) throws Exception {
        String yaml = workflow();
        Project.JobView started = project.ask(p -> p.submitJob("build-team", yaml, values)).join();
        for (int i = 0; i < 400; i++) {
            Project.JobView job = project.ask(p -> p.job(started.jobId())).join();
            if (!Project.QUEUED.equals(job.state()) && !Project.RUNNING.equals(job.state())) return job;
            Thread.sleep(25);
        }
        return project.ask(p -> p.job(started.jobId())).join();
    }

    @Test
    @DisplayName("管理職 2 人、各 2 人の作業役: 上司の表、名前、モデル、窓口への 1 通の知らせ、ジョブの結果が揃う")
    void aTeamOfTwoManagersWithTwoWorkersEachIsBuiltAndTheLiaisonIsTold() throws Exception {
        Project.JobView job = run(values("managers", "2", "workersPerManager", "2", "work", "Wikipedia summaries",
                "managerModel", "opus", "workerModel", "sonnet"));

        assertEquals(Project.FINISHED, job.state(), "result was: " + job.result());
        JSONObject superiors = system.organizationJson("project1").getJSONObject("superiors");
        assertEquals("01", superiors.getString("02"), "a manager reports to the liaison");
        assertEquals("02", superiors.getString("03"));
        assertEquals("02", superiors.getString("04"));
        assertEquals("01", superiors.getString("05"));
        assertEquals("05", superiors.getString("06"));
        assertEquals("05", superiors.getString("07"));
        assertEquals("Wikipedia summaries / manager 1", system.getChatName("project1", "02"));
        assertEquals("Wikipedia summaries / worker 1-2", system.getChatName("project1", "04"));
        assertEquals("Wikipedia summaries / worker 2-1", system.getChatName("project1", "06"));
        assertEquals("opus", system.getChatSession("project1", "02").getModelDirect());
        assertEquals("sonnet", system.getChatSession("project1", "03").getModelDirect());

        assertTrue(job.result().contains("project1/chat-03 \"Wikipedia summaries / worker 1-1\" - worker, fake sonnet, superior chat-02"),
                "the job's result is the tree: " + job.result());

        long deadline = System.currentTimeMillis() + 20_000;
        while (PROMPTS.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, PROMPTS.size(), "the liaison got one message: " + PROMPTS);
        String notice = PROMPTS.get(0);
        assertTrue(notice.contains("[team built by job project1/job-01]\nThis is a notice, not a request: no reply is expected."), notice);
        assertTrue(notice.contains("project1/chat-02 \"Wikipedia summaries / manager 1\" - manager, fake opus, superior chat-01"), notice);
        assertTrue(notice.contains("  project1/chat-07 \"Wikipedia summaries / worker 2-2\" - worker, fake sonnet, superior chat-05"), notice);
    }

    @Test
    @DisplayName("窓口に入る枠より多い管理職を頼むと、何も作らずにジョブが理由を言って失敗し、窓口には何も届かない")
    void moreManagersThanTheLiaisonHasRoomForFailsBeforeAnythingIsMade() throws Exception {
        Project.JobView job = run(values("managers", "9"));

        assertEquals(Project.STOPPED, job.state(), "result was: " + job.result());
        assertTrue(job.result().startsWith("(plan stopped:") && job.result().contains("room for 5"), job.result());
        assertEquals(List.of("project1/chat-01"), system.getChatNames().stream().filter(n -> n.startsWith("project1/")).toList());
        Thread.sleep(300);
        assertTrue(PROMPTS.isEmpty(), "nothing was sent to the liaison: " + PROMPTS);
    }

    @Test
    @DisplayName("プロバイダが挙げないモデルを頼むと、作りかけの ConversationSquad アクターを全部消してジョブが失敗する")
    void aModelTheProviderDoesNotListUndoesWhatWasMadeAndFailsTheJob() throws Exception {
        Project.JobView job = run(values("workersPerManager", "2", "workerModel", "gpt-9"));

        assertEquals(Project.STOPPED, job.state(), "result was: " + job.result());
        assertTrue(job.result().startsWith("(plan stopped:") && job.result().contains("gpt-9"), job.result());
        assertEquals(List.of("project1/chat-01"), system.getChatNames().stream().filter(n -> n.startsWith("project1/")).toList(),
                "the manager and the worker that were made are deleted");
        assertEquals(0, system.organizationJson("project1").getJSONObject("superiors").length());
    }
}
