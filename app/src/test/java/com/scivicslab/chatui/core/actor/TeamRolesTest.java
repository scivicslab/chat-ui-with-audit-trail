package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The role table that decides a ConversationSquad actor's provider, model and timekeeper limits
 * ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 */
@Tag("TeamBuiltByProjectWorkflowTests_261109_oo01")
@DisplayName("役の表")
class TeamRolesTest {

    @Test
    @DisplayName("同梱の表では、管理職と作業役は claude の opus、繰り返す作業役は local LLM の gemma")
    void theBundledTablePutsManagersAndWorkersOnClaudeOpusAndRepeatersOnTheLocalLlm() {
        TeamRoles roles = TeamRoles.bundled();

        assertEquals(new TeamRoles.Role("claude", "opus", 10, 30), roles.role("manager"));
        assertEquals(new TeamRoles.Role("claude", "opus", 10, 30), roles.role("worker"));
        assertEquals("openai-compat", roles.role("repeater").provider());
        assertEquals("google/gemma-4-26B-A4B-it", roles.role("repeater").model());
    }

    @Test
    @DisplayName("プロジェクトの表の文字列を読む。空の文字列は同梱の表になる")
    void aProjectsOwnTextIsReadAndBlankTextMeansTheBundledTable() {
        String own = """
                roles:
                  manager: {provider: claude, model: sonnet, urgentWaitMinutes: 5, childReplyWarnMinutes: 15}
                  worker: {provider: claude, model: haiku, urgentWaitMinutes: 10, childReplyWarnMinutes: 30}
                  repeater: {provider: openai-compat, model: "", urgentWaitMinutes: 10, childReplyWarnMinutes: 30}
                """;

        assertEquals(new TeamRoles.Role("claude", "sonnet", 5, 15), TeamRoles.of(own).role("manager"));
        assertEquals("", TeamRoles.of(own).role("repeater").model());
        assertEquals(TeamRoles.bundled().role("worker"), TeamRoles.of("  ").role("worker"));
    }

    @Test
    @DisplayName("役が欠けている表、読めない表、1 分に満たない値は、理由を言って断る")
    void aTableWithAMissingRoleOrABadValueIsRefusedWithTheReason() {
        String missing = "roles:\n  manager: {provider: claude, model: opus, urgentWaitMinutes: 10, childReplyWarnMinutes: 30}\n";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TeamRoles.of(missing)).getMessage().contains("worker"));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> TeamRoles.of("roles: [unclosed")).getMessage()
                .contains("team-roles"));

        String zero = missing.replace("urgentWaitMinutes: 10", "urgentWaitMinutes: 0")
                + "  worker: {provider: claude, model: opus, urgentWaitMinutes: 10, childReplyWarnMinutes: 30}\n"
                + "  repeater: {provider: openai-compat, model: \"\", urgentWaitMinutes: 10, childReplyWarnMinutes: 30}\n";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TeamRoles.of(zero)).getMessage().contains("urgentWaitMinutes"));
    }

    @Test
    @DisplayName("上書き: 両方なら両方、プロバイダだけならモデルは空（そのプロバイダの既定）、モデルだけならプロバイダは表のまま")
    void anOverrideReplacesWhatItNamesAndAProviderAloneDropsTheTablesModel() {
        TeamRoles roles = TeamRoles.bundled();

        assertEquals(new TeamRoles.Role("claude", "sonnet", 10, 30),
                roles.overriding("worker", "", "sonnet").role("worker"));
        assertEquals(new TeamRoles.Role("openai-compat", "", 10, 30),
                roles.overriding("worker", "openai-compat", "").role("worker"),
                "the table's opus is not carried over to another provider");
        assertEquals(new TeamRoles.Role("openai-compat", "gemma", 10, 30),
                roles.overriding("worker", "openai-compat", "gemma").role("worker"));
        assertEquals(roles.role("worker"), roles.overriding("worker", "", "").role("worker"), "nothing named, nothing changed");
        assertEquals(roles.role("manager"), roles.overriding("worker", "openai-compat", "x").role("manager"));
    }
}
