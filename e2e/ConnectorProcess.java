import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts the ssh-connector jar with a directory and settings of its own, at nice 19, as a user would (tasks/todo.md,
 * stage 11, step 3). The real LLM credential goes in its environment. Used by SshConnectorE2E and ConfinementCheck,
 * which the source launcher compiles together with it.
 */
final class ConnectorProcess implements AutoCloseable {

    final Path dir;
    final Path output;
    final Process process;

    private ConnectorProcess(Path dir, Path output, Process process) {
        this.dir = dir;
        this.output = output;
        this.process = process;
    }

    /**
     * @param jar        the ssh-connector jar
     * @param dir        ssh-connector's directory, made 0700; it must not exist yet
     * @param settings   the lines of ssh-connector.properties
     * @param credential the real LLM credential (a dummy), or {@code null}
     * @param args       the harness's arguments
     */
    static ConnectorProcess start(Path jar, Path dir, Map<String, String> settings, String credential, List<String> args)
            throws IOException {
        Files.createDirectories(dir.getParent());
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        StringBuilder sb = new StringBuilder();
        settings.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Path file = dir.resolve("ssh-connector.properties");
        Files.writeString(file, sb.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of("nice", "-n", "19", java, "-Dssh-connector.dir=" + dir, "-jar",
                jar.toString()));
        command.addAll(args);
        Path output = dir.resolveSibling(dir.getFileName() + ".out");
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        if (credential != null) pb.environment().put("AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL", credential);
        return new ConnectorProcess(dir, output, pb.start());
    }

    /** Waits until ssh-connector with no harness has written its keys and opened its socket. */
    ConnectorProcess awaitServing(boolean withLlmKey) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!(Files.exists(dir.resolve("private/mcp-key")) && Files.exists(socket())
                && (!withLlmKey || Files.exists(dir.resolve("private/llm-key"))))) {
            if (!process.isAlive()) throw new IOException("ssh-connector ended at start-up:\n" + log());
            if (System.nanoTime() > deadline) throw new IOException("ssh-connector did not start in 90 s:\n" + log());
            Thread.sleep(200);
        }
        return this;
    }

    Path socket() {
        return dir.resolve("sockets/agent-connector-kernel.sock");
    }

    String mcpKey() throws IOException {
        return Files.readString(dir.resolve("private/mcp-key")).strip();
    }

    String llmKey() throws IOException {
        return Files.readString(dir.resolve("private/llm-key")).strip();
    }

    /** @return its standard output and its log file */
    String log() throws IOException {
        Path log = dir.resolve("ssh-connector.log");
        return (Files.exists(output) ? Files.readString(output) : "") + (Files.exists(log) ? Files.readString(log) : "");
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
    }
}
