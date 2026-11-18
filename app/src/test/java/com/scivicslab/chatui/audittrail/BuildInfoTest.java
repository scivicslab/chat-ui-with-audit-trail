package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Which build is running, as the title area shows it: the Maven project version and the short git commit it was built
 * from, both written into the build by the build itself.
 */
class BuildInfoTest {

    private static Properties props(String version, String commit) {
        Properties p = new Properties();
        if (version != null) p.setProperty("version", version);
        if (commit != null) p.setProperty("commit", commit);
        return p;
    }

    @Test
    void theLabelIsTheVersionAndTheShortCommit() {
        assertEquals("3.0.0-SNAPSHOT (8765276)", BuildInfo.label(props("3.0.0-SNAPSHOT", "8765276")));
    }

    @Test
    void aValueTheBuildDidNotFillInIsLeftOut() {
        assertEquals("3.0.0-SNAPSHOT", BuildInfo.label(props("3.0.0-SNAPSHOT", "${git.commit.id.abbrev}")),
                "built without git: the version alone");
        assertEquals("3.0.0-SNAPSHOT", BuildInfo.label(props("3.0.0-SNAPSHOT", " ")));
        assertEquals("unknown version", BuildInfo.label(props("${project.version}", null)));
        assertEquals("unknown version", BuildInfo.label(new Properties()));
    }

    /** The resource the build wrote: the version filled in, and the commit too when the build had git. */
    @Test
    void theBuildWroteItsVersionAndCommit() {
        String label = BuildInfo.current();
        assertFalse(label.contains("${") || label.startsWith("unknown"), "the build filled in its version: " + label);
        assertTrue(label.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?( \\([0-9a-f]{7}\\))?"), label);
        Properties written = BuildInfo.read();
        String commit = written.getProperty("commit", "");
        if (!commit.contains("${")) {
            assertTrue(label.endsWith(" (" + commit + ")") && commit.matches("[0-9a-f]{7}"),
                    "the commit the build read from git is in the label: " + label);
        }
    }
}
