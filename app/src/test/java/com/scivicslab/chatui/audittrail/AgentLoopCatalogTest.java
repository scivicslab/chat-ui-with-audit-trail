package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("選べるエージェントループの一覧")
class AgentLoopCatalogTest {

    @Test
    @DisplayName("think から始まる遷移を持つワークフローだけが、ループとして並ぶ")
    void onlyWorkflowsThatLeaveThinkAreLoops() {
        assertTrue(AgentLoopCatalog.isAgentLoop("name: x\nsteps:\n  - states: [\"think\", \"act\"]\n"));
        assertFalse(AgentLoopCatalog.isAgentLoop("name: x\nsteps:\n  - states: [\"0\", \"end\"]\n"));
        assertFalse(AgentLoopCatalog.isAgentLoop("name: x\n"));
        assertFalse(AgentLoopCatalog.isAgentLoop("- not\n- a map\n"));
    }

    @Test
    @DisplayName("同梱のループが、指定するプロンプト構築つきで並び、今のものに印が付く")
    void theBundledLoopsAreListedWithTheirPromptConstruction() {
        List<AgentLoopCatalog.Loop> loops = AgentLoopCatalog.list("plain-text-loop.yaml");

        AgentLoopCatalog.Loop plain = loops.stream().filter(l -> l.file().equals("plain-text-loop.yaml")).findFirst().orElseThrow();
        assertEquals("prompt-construction-plain.yaml", plain.promptWorkflow());
        assertTrue(plain.current());

        AgentLoopCatalog.Loop standard = loops.stream().filter(l -> l.file().equals("chat-session-agent-loop.yaml")).findFirst().orElseThrow();
        assertEquals("prompt-construction-default.yaml", standard.promptWorkflow());
        assertFalse(standard.current());

        assertEquals(1, loops.stream().filter(AgentLoopCatalog.Loop::current).count());
        assertFalse(loops.stream().anyMatch(l -> l.file().equals("polish.yaml")), "a workflow without a think state is not a loop");
    }
}
