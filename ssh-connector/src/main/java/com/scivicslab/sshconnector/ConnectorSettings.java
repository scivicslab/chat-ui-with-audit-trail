package com.scivicslab.sshconnector;

import com.scivicslab.chatui.confine.CodexHarness;
import com.scivicslab.chatui.confine.HermesHarness;
import com.scivicslab.chatui.confine.OpenCodeHarness;
import com.scivicslab.chatui.confine.PodmanConfinement;
import com.scivicslab.chatui.confine.PodmanHarness;
import com.scivicslab.sshtools.SlurmSettings;
import com.scivicslab.sshtools.SshConfigFile;
import com.scivicslab.sshtools.SshLimits;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * ssh-connector's settings, from the one file {@value #FILE} in its directory (tasks/todo.md, "Stage 11 spec"): the
 * host and its ssh_config values, the key, the Slurm commands and jobs directory, the limits, the working directory,
 * and the harness with its image and model. Nothing here comes from the model.
 *
 * <p>Its directory holds everything that must stay on this side: {@code private/} (ssh_config, known_hosts),
 * {@code sockets/} (the one domain socket) and {@code harness/} (the harness's configuration file). Only the socket and
 * the configuration file are mounted into the container, and the working directory, which may not overlap the
 * directory or hold the key.</p>
 *
 * @param dir     ssh-connector's directory
 * @param host    the host the tools reach
 * @param jump    the host it is reached through, or {@code null}
 * @param ssh     the ssh program
 * @param slurm   the Slurm command names and jobs directory
 * @param limits  the time, output and file limits
 * @param workDir the working directory, the harness's {@code /work}
 * @param harness {@code none}, {@code opencode}, {@code codex} or {@code hermes}
 * @param image   the harness image, or {@code null} for {@code none}
 * @param model   the model the harness asks for, or {@code null} for {@code none}
 */
public record ConnectorSettings(Path dir, SshConfigFile.HostEntry host, SshConfigFile.HostEntry jump, String ssh,
                                SlurmSettings slurm, SshLimits limits, Path workDir, String harness, String image,
                                String model) {

    /** The settings file's name in ssh-connector's directory. */
    public static final String FILE = "ssh-connector.properties";
    /** The one scope's name, the harness's. */
    public static final String SCOPE = "ssh-connector";

    /**
     * @param dir    ssh-connector's directory
     * @param lookup reads one setting by its full name, e.g. {@code ssh-connector.host.alias}
     * @return the settings
     * @throws IllegalArgumentException when a required setting is missing or a value does not fit, naming it
     */
    public static ConnectorSettings from(Path dir, Function<String, Optional<String>> lookup) {
        Path home = dir.toAbsolutePath().normalize();
        Path key = path(lookup, "ssh-connector.key");
        SshConfigFile.HostEntry jump = null;
        Optional<String> jumpAlias = value(lookup, "ssh-connector.jump.alias");
        if (jumpAlias.isPresent()) {
            jump = new SshConfigFile.HostEntry(jumpAlias.get(), required(lookup, "ssh-connector.jump.name"),
                    required(lookup, "ssh-connector.jump.user"), port(lookup, "ssh-connector.jump.port"), key, null);
        }
        SshConfigFile.HostEntry host = new SshConfigFile.HostEntry(required(lookup, "ssh-connector.host.alias"),
                required(lookup, "ssh-connector.host.name"), required(lookup, "ssh-connector.host.user"),
                port(lookup, "ssh-connector.host.port"), key, jump == null ? null : jump.alias());
        SlurmSettings d = SlurmSettings.DEFAULT;
        SlurmSettings slurm = new SlurmSettings(
                value(lookup, "ssh-connector.slurm.sbatch").orElse(d.sbatch()),
                value(lookup, "ssh-connector.slurm.squeue").orElse(d.squeue()),
                value(lookup, "ssh-connector.slurm.sacct").orElse(d.sacct()),
                value(lookup, "ssh-connector.slurm.scancel").orElse(d.scancel()),
                value(lookup, "ssh-connector.slurm.jobs-dir").orElse(d.jobsDir()));
        SshLimits l = SshLimits.DEFAULT;
        SshLimits limits = new SshLimits(
                Duration.ofSeconds(positive(lookup, "ssh-connector.limits.time-seconds", l.time().toSeconds())),
                positive(lookup, "ssh-connector.limits.output-bytes", l.outputBytes()),
                positive(lookup, "ssh-connector.limits.file-bytes", l.fileBytes()));
        Path workDir = path(lookup, "ssh-connector.work-dir");
        if (workDir.startsWith(home) || home.startsWith(workDir)) {
            throw new IllegalArgumentException("ssh-connector.work-dir " + workDir + " overlaps ssh-connector's directory "
                    + home + "; the working directory is mounted into the container and the directory may not be");
        }
        if (key.startsWith(workDir)) {
            throw new IllegalArgumentException("ssh-connector.key " + key + " lies inside ssh-connector.work-dir, which the"
                    + " harness can read");
        }
        String harness = value(lookup, "ssh-connector.harness.kind").orElse("none");
        if (!HARNESSES.contains(harness)) {
            throw new IllegalArgumentException("ssh-connector.harness.kind must be one of " + HARNESSES + ", not " + harness);
        }
        String image = null;
        String model = null;
        if (!harness.equals("none")) {
            image = required(lookup, "ssh-connector.harness.image");
            model = required(lookup, "ssh-connector.harness.model");
        }
        return new ConnectorSettings(home, host, jump, value(lookup, "ssh-connector.ssh").orElse("ssh"), slurm, limits,
                workDir, harness, image, model);
    }

    private static final List<String> HARNESSES = List.of("none", "opencode", "codex", "hermes");

    private static Optional<String> value(Function<String, Optional<String>> lookup, String key) {
        return lookup.apply(key).map(String::strip).filter(v -> !v.isEmpty());
    }

    private static String required(Function<String, Optional<String>> lookup, String key) {
        return value(lookup, key).orElseThrow(() -> new IllegalArgumentException(key + " is required in " + FILE));
    }

    private static Path path(Function<String, Optional<String>> lookup, String key) {
        Path p = Path.of(required(lookup, key));
        if (!p.isAbsolute()) throw new IllegalArgumentException(key + " must be an absolute path, not " + p);
        return p.normalize();
    }

    private static int port(Function<String, Optional<String>> lookup, String key) {
        Optional<String> v = value(lookup, key);
        if (v.isEmpty()) return 0;
        try {
            int port = Integer.parseInt(v.get());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(key + " must be a port number, not " + v.get());
    }

    private static long positive(Function<String, Optional<String>> lookup, String key, long dflt) {
        Optional<String> v = value(lookup, key);
        if (v.isEmpty()) return dflt;
        try {
            long n = Long.parseLong(v.get());
            if (n > 0) return n;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException(key + " must be a positive whole number, not " + v.get());
    }

    /** @return agent-connector-kernel's own directory here: nothing inside it is ever mounted */
    public Path privateDir() {
        return dir.resolve("private");
    }

    /** @return where ssh_config and known_hosts are written */
    public Path sshDir() {
        return privateDir().resolve("ssh");
    }

    /** @return the owner-only directory of the domain socket */
    public Path socketDir() {
        return dir.resolve("sockets");
    }

    /** @return the domain socket of the application's HTTP server */
    public Path socket() {
        return socketDir().resolve(PodmanConfinement.SOCKET);
    }

    /** @return the directory of the harness's configuration file */
    public Path harnessDir() {
        return dir.resolve("harness");
    }

    /** @return the Host blocks of ssh_config: the jump host first when there is one, then the host */
    public List<SshConfigFile.HostEntry> hostEntries() {
        return jump == null ? List.of(host) : List.of(jump, host);
    }

    /** @return the harness's launcher, or {@code null} for {@code none} */
    public PodmanHarness podmanHarness() {
        return switch (harness) {
            case "opencode" -> new OpenCodeHarness();
            case "codex" -> new CodexHarness();
            case "hermes" -> new HermesHarness();
            default -> null;
        };
    }
}
