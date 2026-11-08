package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.ToolSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The plain object that builds a team of ConversationSquad actors, run against a desk that only records what
 * it is asked ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 */
@Tag("TeamBuiltByProjectWorkflowTests_261109_oo01")
@DisplayName("TeamBuilder: チームの形、上書き、上限、全部か無か")
class TeamBuilderTest {

    /** What a project's ConversationSquad actors would do, with every call written down. */
    private static final class FakeDesk implements TeamDesk {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> superiors = new LinkedHashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, String> providers = new LinkedHashMap<>();
        final Map<String, String> models = new LinkedHashMap<>();
        final Set<String> alive = new HashSet<>();
        final List<String> notices = new ArrayList<>();
        int next = 2;
        int limit = 5;
        int liaisonRoom = 5;
        String liaison = "01";
        /** Refuses the superior of the n-th ConversationSquad actor made (1-based); 0 for never. */
        int refuseSuperiorOfNth = 0;
        int made = 0;
        String noticeRefusal = null;
        List<String> modelsOfProvider = List.of("opus", "sonnet", "haiku", "fable");
        String projectRoles = "";

        @Override public List<Provider> providers() {
            return List.of(new Provider("claude", "claude", ToolSet.HARNESS),
                    new Provider("claude:full", "claude", ToolSet.FULL),
                    new Provider("openai-compat", "openai-compat", ToolSet.FULL));
        }
        @Override public String rolesText(String projectId) { return projectRoles; }
        @Override public String liaisonOf(String projectId) { return liaison; }
        @Override public int subordinateLimit(String projectId) { return limit; }
        @Override public int roomLeft(String projectId, String chatId) { return chatId.equals(liaison) ? liaisonRoom : limit; }
        @Override public String createChat(String projectId) {
            String id = String.format("%02d", next++);
            made++;
            alive.add(id);
            calls.add("create " + id);
            return id;
        }
        @Override public void setProvider(String projectId, String chatId, Provider provider) {
            providers.put(chatId, provider.value());
            calls.add("provider " + chatId + " " + provider.value());
        }
        @Override public List<String> modelNames(String projectId, String chatId) {
            return providers.get(chatId).startsWith("claude") ? modelsOfProvider : List.of();
        }
        @Override public void setModel(String projectId, String chatId, String model) {
            models.put(chatId, model);
            calls.add("model " + chatId + " " + model);
        }
        @Override public String setChatName(String projectId, String chatId, String name) {
            names.put(chatId, name);
            calls.add("name " + chatId);
            return null;
        }
        @Override public String setSuperior(String projectId, String chatId, String superiorChatId) {
            calls.add("superior " + chatId + " " + superiorChatId);
            if (made == refuseSuperiorOfNth) return "chat-" + superiorChatId + " already has 5 subordinates";
            superiors.put(chatId, superiorChatId);
            return null;
        }
        @Override public void setTimekeeperLimits(String projectId, String chatId, long urgent, long warn) {
            calls.add("timekeeper " + chatId + " " + urgent + "," + warn);
        }
        @Override public boolean deleteChat(String projectId, String chatId) {
            calls.add("delete " + chatId);
            alive.remove(chatId);
            return true;
        }
        @Override public String notifyConversation(String projectId, String chatId, String source, String text) {
            if (noticeRefusal != null) return noticeRefusal;
            notices.add(chatId + "|" + source + "|" + text);
            return null;
        }
    }

    private static TeamBuilder.Inputs inputs(String managers, String workers, String repeaters, String work) {
        return new TeamBuilder.Inputs("project1/job-02", managers, workers, repeaters, work,
                "", "", "", "", "", "");
    }

    @Test
    @DisplayName("管理職 2 人、各 2 人の作業役と 1 人の繰り返す作業役で、8 つの ConversationSquad アクターの計画ができる")
    void theShapeIsAsManyManagersAsAskedEachWithItsWorkers() {
        TeamBuilder.Plan plan = new TeamBuilder(new FakeDesk()).check(inputs("2", "2", "1", "Wikipedia 要約"));

        assertEquals("project1", plan.projectId());
        assertEquals(List.of("m1", "m1w1", "m1w2", "m1r1", "m2", "m2w1", "m2w2", "m2r1"),
                plan.members().stream().map(TeamBuilder.Member::key).toList());
        assertEquals(List.of("Wikipedia 要約 / manager 1", "Wikipedia 要約 / worker 1-1", "Wikipedia 要約 / worker 1-2",
                "Wikipedia 要約 / repeater 1-1", "Wikipedia 要約 / manager 2", "Wikipedia 要約 / worker 2-1",
                "Wikipedia 要約 / worker 2-2", "Wikipedia 要約 / repeater 2-1"),
                plan.members().stream().map(TeamBuilder.Member::name).toList());
        assertEquals(List.of("", "m1", "m1", "m1", "", "m2", "m2", "m2"),
                plan.members().stream().map(m -> m.superiorKey() == null ? "" : m.superiorKey()).toList());
    }

    @Test
    @DisplayName("既定では、管理職と作業役は claude の opus、繰り返す作業役は local LLM。work が空ならジョブの名前が名前に入る")
    void theDefaultsAreTheRoleTablesAndAnEmptyWorkNamesTheJob() {
        TeamBuilder.Plan plan = new TeamBuilder(new FakeDesk()).check(inputs("1", "3", "0", ""));

        assertEquals(4, plan.members().size());
        assertTrue(plan.members().stream().allMatch(m -> m.settings().provider().equals("claude") && m.settings().model().equals("opus")));
        assertEquals("job-02 / manager 1", plan.members().get(0).name());
        assertEquals("job-02 / worker 1-3", plan.members().get(3).name());
    }

    @Test
    @DisplayName("この実行だけの上書きは、その役にだけ効く。プロバイダだけの上書きでは、表のモデルを持ち込まない")
    void aPerRunOverrideAffectsOnlyItsRoleAndAProviderAloneDropsTheTablesModel() {
        TeamBuilder.Inputs in = new TeamBuilder.Inputs("project1/job-02", "1", "2", "1", "x",
                "", "", "", "sonnet", "claude", "");

        TeamBuilder.Plan plan = new TeamBuilder(new FakeDesk()).check(in);

        assertEquals("opus", plan.members().get(0).settings().model(), "the manager keeps the table");
        assertEquals("sonnet", plan.members().get(1).settings().model(), "workers take the override");
        TeamBuilder.Member repeater = plan.members().get(3);
        assertEquals("claude", repeater.settings().provider());
        assertEquals("", repeater.settings().model(), "the repeater's gemma is not carried over to claude");
    }

    @Test
    @DisplayName("プロジェクトが自分の役の表を持っていれば、それを使う")
    void theProjectsOwnRoleTableIsUsedWhenItHasOne() {
        FakeDesk desk = new FakeDesk();
        desk.projectRoles = """
                roles:
                  manager: {provider: claude, model: sonnet, urgentWaitMinutes: 5, childReplyWarnMinutes: 15}
                  worker: {provider: claude, model: haiku, urgentWaitMinutes: 10, childReplyWarnMinutes: 30}
                  repeater: {provider: openai-compat, model: "", urgentWaitMinutes: 10, childReplyWarnMinutes: 30}
                """;

        TeamBuilder.Plan plan = new TeamBuilder(desk).check(inputs("1", "1", "0", "x"));

        assertEquals("sonnet", plan.members().get(0).settings().model());
        assertEquals(5, plan.members().get(0).settings().urgentWaitMinutes());
        assertEquals("haiku", plan.members().get(1).settings().model());
    }

    @Test
    @DisplayName("窓口の残りの枠より多い管理職、上限より多い部下、40 文字を超える work、載っていないプロバイダは、何も作らずに断る")
    void aPlanBeyondTheLimitsOrWithAnUnknownProviderIsRefusedBeforeAnythingIsMade() {
        FakeDesk desk = new FakeDesk();
        desk.liaisonRoom = 2;
        TeamBuilder builder = new TeamBuilder(desk);

        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(inputs("3", "1", "0", "x"))).getMessage()
                .contains("room for 2"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(inputs("1", "4", "2", "x"))).getMessage()
                .contains("at most 5"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(inputs("1", "1", "0", "x".repeat(41)))).getMessage()
                .contains("40"));
        TeamBuilder.Inputs unknown = new TeamBuilder.Inputs("project1/job-02", "1", "1", "0", "x", "gpt", "", "", "", "", "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(unknown)).getMessage().contains("gpt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(inputs("0", "1", "0", "x"))).getMessage()
                .contains("managers"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> builder.check(inputs("two", "1", "0", "x"))).getMessage()
                .contains("managers"));
        assertTrue(desk.calls.isEmpty(), "checking made nothing: " + desk.calls);
    }

    @Test
    @DisplayName("作るときは、1 つずつ 作る・プロバイダ・モデル・名前・上司・timekeeper の順で、作業役の上司はその管理職、管理職の上司は窓口")
    void eachConversationSquadActorIsMadeAndSetUpInOrderUnderItsSuperior() {
        FakeDesk desk = new FakeDesk();
        TeamBuilder builder = new TeamBuilder(desk);
        TeamBuilder.Plan plan = builder.check(inputs("1", "1", "1", "w"));

        List<TeamBuilder.Built> built = builder.build(plan);

        assertEquals(List.of("create 02", "provider 02 claude", "model 02 opus", "name 02", "superior 02 01", "timekeeper 02 10,30"),
                desk.calls.subList(0, 6));
        assertEquals("02", built.get(0).chatId());
        assertEquals("01", desk.superiors.get("02"), "the manager reports to the liaison");
        assertEquals("02", desk.superiors.get("03"), "the worker reports to its manager");
        assertEquals("02", desk.superiors.get("04"));
        assertEquals("openai-compat", desk.providers.get("04"));
        assertEquals("w / worker 1-1", desk.names.get("03"));
        assertEquals(3, built.size());
    }

    @Test
    @DisplayName("モデルがそのプロバイダの一覧に無ければ、作ったものを全部、部下から先に消して、理由を言って失敗する")
    void aModelTheProviderDoesNotListUndoesEverythingMadeSubordinatesFirst() {
        FakeDesk desk = new FakeDesk();
        TeamBuilder builder = new TeamBuilder(desk);
        TeamBuilder.Inputs in = new TeamBuilder.Inputs("project1/job-02", "1", "2", "0", "x", "", "", "", "gpt-9", "", "");
        TeamBuilder.Plan plan = builder.check(in);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> builder.build(plan));

        assertTrue(failure.getMessage().contains("gpt-9"), failure.getMessage());
        assertEquals(List.of("delete 03", "delete 02"), desk.calls.subList(desk.calls.size() - 2, desk.calls.size()),
                "the worker that was being made goes first, then the manager above it");
        assertTrue(desk.alive.isEmpty(), "nothing is left: " + desk.alive);
    }

    @Test
    @DisplayName("途中で上司を断られたら、それまでに作った全部を、作った順の逆に消す")
    void aRefusedSuperiorMidwayUndoesAllInReverse() {
        FakeDesk desk = new FakeDesk();
        desk.refuseSuperiorOfNth = 4;
        TeamBuilder builder = new TeamBuilder(desk);
        TeamBuilder.Plan plan = builder.check(inputs("1", "3", "0", "x"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> builder.build(plan));

        assertTrue(failure.getMessage().contains("already has 5 subordinates"), failure.getMessage());
        List<String> deletes = desk.calls.stream().filter(c -> c.startsWith("delete")).toList();
        assertEquals(List.of("delete 05", "delete 04", "delete 03", "delete 02"), deletes);
        assertTrue(desk.alive.isEmpty());
    }

    @Test
    @DisplayName("報告は、木の表をジョブの結果にし、同じ表を窓口の待ち行列に ジョブの名前を出どころとして 1 通入れる")
    void theReportIsTheTreeAndOneMessageToTheLiaisonFromTheJob() {
        FakeDesk desk = new FakeDesk();
        TeamBuilder builder = new TeamBuilder(desk);
        TeamBuilder.Plan plan = builder.check(inputs("1", "1", "0", "Wikipedia 要約"));
        List<TeamBuilder.Built> built = builder.build(plan);

        String table = builder.report("project1/job-02", plan, built);

        assertEquals(1, desk.notices.size());
        String[] parts = desk.notices.get(0).split("\\|", 3);
        assertEquals("01", parts[0], "to the liaison's queue");
        assertEquals("job:project1/job-02", parts[1], "from the job, not from a ConversationSquad actor");
        assertTrue(parts[2].startsWith("[team built by job project1/job-02]\nThis is a notice, not a request: no reply is expected."), parts[2]);
        assertTrue(parts[2].contains("project1/chat-02 \"Wikipedia 要約 / manager 1\" - manager, claude opus, superior chat-01"), parts[2]);
        assertTrue(parts[2].contains("  project1/chat-03 \"Wikipedia 要約 / worker 1-1\" - worker, claude opus, superior chat-02"), parts[2]);
        assertTrue(table.contains("project1/chat-03"), "the same tree is the job's result: " + table);
        assertFalse(table.contains("not notified"));
    }

    @Test
    @DisplayName("窓口に知らせを入れられなくても、チームは消さず、結果に「知らせていない」と書く")
    void aNoticeThatCannotBeDeliveredLeavesTheTeamAndSaysSo() {
        FakeDesk desk = new FakeDesk();
        desk.noticeRefusal = "error: no such conversation: project1/chat-01";
        TeamBuilder builder = new TeamBuilder(desk);
        TeamBuilder.Plan plan = builder.check(inputs("1", "1", "0", "x"));
        List<TeamBuilder.Built> built = builder.build(plan);

        String table = builder.report("project1/job-02", plan, built);

        assertTrue(table.contains("the liaison was not notified: error: no such conversation: project1/chat-01"), table);
        assertEquals(2, desk.alive.size(), "the team stays");
    }

    @Test
    @DisplayName("ConversationSquad アクターを扱う机が登録されていなければ、何も作らずに理由を言って失敗する")
    void withoutADeskEveryStepFailsWithTheReason() {
        TeamBuilder builder = new TeamBuilder(TeamDesk.absent());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> builder.check(inputs("1", "1", "0", "x")));

        assertTrue(failure.getMessage().contains("conversations"), failure.getMessage());
    }
    @Test
    @DisplayName("窓口が受ける知らせの出どころ job:<ジョブの名前> は、画面では job <ジョブの名前> と読める")
    void aMessageFromAJobIsShownAsComingFromTheJob() {
        assertEquals("job project1/job-02", ChatSession.originOf("job:project1/job-02"));
    }
}
