package com.scivicslab.sshconnector;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.chatui.tool.ToolExtension;
import com.scivicslab.sshtools.SshTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ssh-connector's one MCP scope (tasks/todo.md, "Stage 11 spec", Decision 13). */
class ConnectorScopeTest {

    @TempDir
    Path work;

    /** Stands in for the ssh tools: it answers with the tool's name. */
    private static final ToolExtension FAKE = new ToolExtension() {
        @Override public boolean defines(String tool) { return SshTools.NAMES.contains(tool); }
        @Override public boolean offers(String tool) { return SshTools.NAMES.contains(tool); }
        @Override public String run(String tool, String args) { return defines(tool) ? "ran " + tool : null; }
    };

    private ConnectorScope scope() {
        return new ConnectorScope(FAKE, SshTools.specs(), new FileAccessScope(work, List.of()));
    }

    @Test
    void theScopeOffersTheSevenSshToolsOnly() {
        ConnectorScope s = scope();
        assertEquals(ConnectorSettings.SCOPE, s.name());
        assertEquals(SshTools.NAMES, s.tools().stream().map(ToolCatalog.Spec::name).toList());
        for (String tool : SshTools.NAMES) assertTrue(s.runner().offers(tool), tool);
        for (String tool : List.of("read", "write", "remove", "calc")) assertFalse(s.runner().offers(tool), tool);
        assertEquals("ran submit_job", s.runner().callFromMcp("submit_job", "{}"));
        assertEquals(work, s.runner().scope().fileScope().writeRoot());
    }

    @Test
    void onlyItsOwnKeyOpensIt() {
        ConnectorScope s = scope();
        assertTrue(s.key().length() >= 43, s.key());
        assertTrue(s.opens(ConnectorSettings.SCOPE, s.key()));
        assertFalse(s.opens(ConnectorSettings.SCOPE, s.key() + "x"));
        assertFalse(s.opens(ConnectorSettings.SCOPE, null));
        assertFalse(s.opens("other", s.key()));
        assertNotEquals(s.key(), scope().key(), "a new key at every start");
    }
}
