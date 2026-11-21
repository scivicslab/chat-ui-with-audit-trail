import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts the test host (e2e/confinement-host: agent-connector-kernel's MCP server and LLM proxy with one keyed scope)
 * on a domain socket in an owner-only directory, as a launcher would, and reads the keys it wrote (tasks/todo.md, steps
 * 5 and 6). Used by ConfinementCheck and HarnessStubRun, which the source launcher compiles together.
 *
 * <p>The real credential reaches the host through its environment ({@code AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL}), not
 * its command line. The host runs at nice 19 and is stopped by its own PID.</p>
 */
final class ConfinementHost implements AutoCloseable {

    final Path dir;
    final Path socketDir;
    final Path socket;
    final String mcpKey;
    final String llmKey;
    private final Process process;

    private ConfinementHost(Path dir, Path socketDir, Path socket, String mcpKey, String llmKey, Process process) {
        this.dir = dir;
        this.socketDir = socketDir;
        this.socket = socket;
        this.mcpKey = mcpKey;
        this.llmKey = llmKey;
        this.process = process;
    }

    /**
     * @param jar        the host's uber-jar
     * @param base       the run directory; the host's own directory is made inside it, 0700
     * @param scope      the scope's name
     * @param workDir    the scope's working directory (the harness's /work)
     * @param upstream   the LLM provider the proxy forwards to
     * @param credential the real credential (a dummy in every check)
     * @param marker     what calc's answer carries, to show the tool's result came back
     */
    static ConfinementHost start(Path jar, Path base, String scope, Path workDir, URI upstream, String credential,
                                 String marker) throws Exception {
        Path dir = base.resolve("host");
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        String token = HexFormat.of().formatHex(SecureRandom.getInstanceStrong().generateSeed(6));
        Path runtime = Path.of(System.getenv().getOrDefault("XDG_RUNTIME_DIR", base.toString()));
        Path socketDir = runtime.resolve("ack-host-" + token);
        Files.createDirectories(socketDir);
        Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwx------"));
        Path socket = socketDir.resolve("agent-connector-kernel.sock");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(List.of("nice", "-n", "19", java,
                "-Dquarkus.http.domain-socket=" + socket,
                "-Dagent-connector-kernel.llm.upstream=" + upstream,
                "-Dconfinement-host.scope=" + scope,
                "-Dconfinement-host.dir=" + dir,
                "-Dconfinement-host.work-dir=" + workDir,
                "-Dconfinement-host.result-marker=" + marker,
                "-jar", jar.toString()))
                .redirectErrorStream(true).redirectOutput(dir.resolve("host.log").toFile());
        Map<String, String> env = pb.environment();
        env.put("AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL", credential);
        Process process = pb.start();
        Path keys = dir.resolve("keys");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!(Files.exists(keys) && Files.exists(socket))) {
            if (!process.isAlive()) throw new IOException("the host ended at start-up:\n" + Files.readString(dir.resolve("host.log")));
            if (System.nanoTime() > deadline) {
                process.destroy();
                throw new IOException("the host did not start in 90 s:\n" + Files.readString(dir.resolve("host.log")));
            }
            Thread.sleep(200);
        }
        String mcp = null, llm = null;
        for (String line : Files.readAllLines(keys)) {
            if (line.startsWith("mcp=")) mcp = line.substring(4);
            if (line.startsWith("llm=")) llm = line.substring(4);
        }
        return new ConfinementHost(dir, socketDir, socket, mcp, llm, process);
    }

    /** @return the calc expressions the MCP server ran, in order */
    List<String> calcCalls() throws IOException {
        Path calls = dir.resolve("calls");
        return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
    }

    /** @return the socket's and its directory's permissions, e.g. {@code rw------- rwx------} */
    String permissions() throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)) + " "
                + PosixFilePermissions.toString(Files.getPosixFilePermissions(socketDir));
    }

    @Override
    public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(socketDir);
    }
}
