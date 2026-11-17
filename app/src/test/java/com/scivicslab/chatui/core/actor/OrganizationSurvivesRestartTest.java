package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.scivicslab.chatui.agent.OrganizationTools;
import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The organization set with the tools comes back after a restart, from the project's own {@code organization} entry,
 * which the organization actor writes; the project's {@code settings} entry no longer carries it
 * ({@code EachActorRecordsItsOwnState_261118_oo01}, step 3).
 */
class OrganizationSurvivesRestartTest {

    @Test
    void theOrganizationComesBackFromItsOwnEntry(@TempDir Path dir) throws Exception {
        try (SystemsOnOneLog systems = new SystemsOnOneLog(dir.resolve("organization-restart"))) {
            ChatUiActorSystem first = systems.start();
            first.createChat("project1");
            first.createChat("project1");
            first.createChat("project1");
            for (String id : new String[] {"01", "02", "03", "04"}) {
                systems.log.ensureSession(ChatUiActorSystem.chatActorName("project1", id));
            }
            assertTrue(OrganizationTools.setSuperior(first, "project1", "01", "02", "01").startsWith("ok"));
            assertTrue(OrganizationTools.setSuperior(first, "project1", "01", "03", "02").startsWith("ok"));
            assertTrue(first.setOrganizationSettings("project1", null, 3).startsWith("ok"));
            // A rename writes the project's settings entry after the organization changed.
            assertTrue(first.setProjectName("project1", "Org check").startsWith("ok"));

            SessionEntries project = SessionEntries.ofProject(systems.log, IoLogView.over(systems.log), "project1");
            assertNotNull(project.latest(Organization.ORGANIZATION_LABEL), "the organization wrote its own entry");
            assertFalse(new org.json.JSONObject(project.latest(IoLogView.SETTINGS_LABEL)).has("organization"),
                    "the project's settings entry no longer carries the organization");

            ChatUiActorSystem second = systems.start();
            org.json.JSONObject org = second.organizationJson("project1");
            assertEquals("01", org.getJSONObject("superiors").getString("02"));
            assertEquals("02", org.getJSONObject("superiors").getString("03"));
            assertEquals(3, org.getInt("maxSubordinates"));
            assertEquals("Org check", second.getProjectName("project1"));
        }
    }
}
