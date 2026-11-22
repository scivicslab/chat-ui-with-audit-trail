package com.scivicslab.sshconnector;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * ssh-connector's settings as Quarkus will see them, read before Quarkus starts, so that the startup guard runs before
 * anything listens on the socket (tasks/todo.md, "ssh-connector's startup guard"). A setting is looked up in
 * MicroProfile Config's order: a system property, then an environment variable (the name upper-cased, every character
 * other than a letter or a digit replaced by {@code _}), then {@value ConnectorSettings#FILE}.
 */
public final class StartupSettings {

    private StartupSettings() {
    }

    /**
     * @param dir    ssh-connector's directory
     * @param sysProp reads a system property, {@code null} when unset
     * @param env     reads an environment variable, {@code null} when unset
     * @return the settings
     * @throws IOException when the settings file cannot be read
     */
    public static ConnectorSettings read(Path dir, Function<String, String> sysProp, Function<String, String> env)
            throws IOException {
        Properties file = new Properties();
        try (Reader in = Files.newBufferedReader(dir.resolve(ConnectorSettings.FILE), StandardCharsets.UTF_8)) {
            file.load(in);
        }
        return ConnectorSettings.from(dir, key -> {
            String v = sysProp.apply(key);
            if (v == null) v = env.apply(envName(key));
            if (v == null) v = file.getProperty(key);
            return Optional.ofNullable(v);
        });
    }

    /** @return the environment variable MicroProfile Config reads for {@code key} */
    public static String envName(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (char c : key.toCharArray()) sb.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        return sb.toString();
    }
}
