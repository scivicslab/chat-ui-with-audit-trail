package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Turing Workflow YAML in this program is one of three kinds — an agent loop, a prompt
 * construction, or a job's workflow — and only the last is run as a job
 * ({@code WorkflowKinds_261021_oo01}).
 */
@DisplayName("ワークフローの YAML は agent loop・プロンプト構築・ジョブのワークフローの 3 種類に分かれ、ジョブの一覧にはジョブのワークフローだけが並ぶ")
class WorkflowKindTest {

    private static WorkflowKind kindOfBundled(String name) {
        return WorkflowKind.of(new ProjectWorkflowCatalog(null).read(name).yaml());
    }

    @Test
    @DisplayName("同梱の YAML を 3 種類に分ける")
    void theBundledFilesFallIntoThreeKinds() {
        for (String loop : List.of("chat-session-agent-loop", "plain-text-loop", "plain-text-no-context-loop",
                "doc-retrieval-loop", "doc-retrieval-prompt-loop", "babysitter-loop", "research-babysitter-loop")) {
            assertEquals(WorkflowKind.AGENT_LOOP, kindOfBundled(loop), loop);
        }
        for (String construction : List.of("prompt-construction-default", "prompt-construction-plain",
                "prompt-construction-no-context", "prompt-construction-with-context",
                "prompt-construction-doc-retrieval")) {
            assertEquals(WorkflowKind.PROMPT_CONSTRUCTION, kindOfBundled(construction), construction);
        }
        for (String job : List.of("fill-frame", "polish", "rst-agent-loop", "research")) {
            assertEquals(WorkflowKind.JOB, kindOfBundled(job), job);
        }
    }

    @Test
    @DisplayName("プロジェクトのワークフロー一覧には、ジョブのワークフローだけが並ぶ")
    void theProjectsListHoldsOnlyJobWorkflows() {
        List<String> names = new ProjectWorkflowCatalog(null).listJobs("").stream()
                .map(ProjectWorkflowCatalog.Entry::name).toList();

        assertTrue(names.contains("fill-frame"), names.toString());
        assertTrue(names.contains("polish"), names.toString());
        assertFalse(names.contains("plain-text-loop"), "an agent loop is not run as a job: " + names);
        assertFalse(names.contains("prompt-construction-plain"), "a prompt construction is not run as a job: " + names);
    }

    @Test
    @DisplayName("読めない YAML はジョブのワークフローとして扱い、起動時の検査に任せる")
    void unreadableTextIsLeftToTheJobsOwnCheck() {
        assertEquals(WorkflowKind.JOB, WorkflowKind.of("steps: [unclosed"));
    }
}
