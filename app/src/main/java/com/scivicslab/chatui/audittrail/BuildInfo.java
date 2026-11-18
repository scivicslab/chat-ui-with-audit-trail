package com.scivicslab.chatui.audittrail;

import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which build of chat-ui is running, as the title area of the screen shows it: the Maven project version and the
 * short git commit the build was made from, for example {@code 3.0.0-SNAPSHOT (8765276)}. A SNAPSHOT version alone
 * does not say which build it is.
 *
 * <p>Both come from the build itself: Maven writes the version, and the git-commit-id plugin the commit, into the
 * filtered resource {@value #RESOURCE}. Nothing here is written by hand. A build made without git leaves the commit
 * unfilled, and the version is shown alone.</p>
 */
public final class BuildInfo {

    private static final Logger LOG = Logger.getLogger(BuildInfo.class.getName());

    /** The resource the build writes. */
    static final String RESOURCE = "/chat-ui-build.properties";

    private static volatile String current;

    private BuildInfo() {}

    /** @return the running build's label, read once from the resource the build wrote */
    public static String current() {
        String c = current;
        if (c == null) current = c = label(read());
        return c;
    }

    /** @return what the build wrote, or nothing when the resource is not there */
    static Properties read() {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read " + RESOURCE, e);
        }
        return p;
    }

    /**
     * @param p the {@code version} and {@code commit} the build wrote; a value it did not fill in (blank, or still the
     *          {@code ${...}} placeholder) is left out
     * @return {@code <version> (<commit>)}, the version alone, or {@code unknown version}
     */
    static String label(Properties p) {
        String version = filled(p.getProperty("version"));
        String commit = filled(p.getProperty("commit"));
        if (version == null) return "unknown version";
        return commit == null ? version : version + " (" + commit + ")";
    }

    private static String filled(String value) {
        if (value == null) return null;
        String v = value.strip();
        return v.isEmpty() || v.contains("${") ? null : v;
    }
}
