import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ssh-connector's MCP server on its domain socket, with require-key=true (tasks/todo.md, stage 11, step 2). An E2E
 * program with main(), outside the Maven build (TestingStandard_260404_oo01); it is compiled with SocketMcpClient.
 *
 * <pre>
 * java e2e/SshConnectorCheck.java &lt;ssh-connector's directory&gt;
 * </pre>
 *
 * ssh-connector runs with {@code ssh-connector.harness.kind=none}, so it writes the scope's key to
 * {@code private/mcp-key}. Checks: the socket and its directory are owner-only; a request naming no scope gets 400; no
 * key, a wrong key and an unknown scope get 401; with the key, initialize answers 200 and tools/list lists exactly the
 * seven ssh tools; a refused job_id is answered before ssh starts; run_command reaches ssh, which fails for the
 * unreachable host with exit 255. Prints one line per check and exits 1 when any fails.
 */
public class SshConnectorCheck {

    static final List<String> failures = new ArrayList<>();
    static final List<String> TOOLS = List.of("run_command", "submit_job", "job_status", "cancel_job", "list_remote",
            "upload_file", "download_file");

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path socket = dir.resolve("sockets/agent-connector-kernel.sock");
        String key = Files.readString(dir.resolve("private/mcp-key")).strip();
        check(perms(dir.resolve("private/mcp-key")).equals("rw-------"), "the key file is 0600", perms(dir.resolve("private/mcp-key")));
        check(perms(socket.getParent()).equals("rwx------"), "the socket's directory is 0700", perms(socket.getParent()));
        check(perms(socket).equals("rw-------"), "the socket is 0600", perms(socket));

        SocketMcpClient.Answer noScope = new SocketMcpClient(socket, null, key).initialize();
        check(noScope.status() == 400, "a request naming no scope gets 400", noScope.toString());
        SocketMcpClient.Answer noKey = new SocketMcpClient(socket, "ssh-connector", null).initialize();
        SocketMcpClient.Answer wrong = new SocketMcpClient(socket, "ssh-connector", key + "x").initialize();
        SocketMcpClient.Answer other = new SocketMcpClient(socket, "other", key).initialize();
        check(noKey.status() == 401, "no key gets 401", noKey.toString());
        check(wrong.status() == 401, "a wrong key gets 401", wrong.toString());
        check(other.status() == 401, "the key does not open another scope name", other.toString());

        SocketMcpClient mcp = new SocketMcpClient(socket, "ssh-connector", key);
        SocketMcpClient.Answer init = mcp.initialize();
        check(init.status() == 200 && init.json().contains("\"serverInfo\""), "with the key, initialize answers 200", init.toString());
        SocketMcpClient.Answer list = mcp.listTools();
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([a-z_]+)\"").matcher(list.json());
        while (m.find()) if (!names.contains(m.group(1))) names.add(m.group(1));
        names.retainAll(List.of("run_command", "submit_job", "job_status", "cancel_job", "list_remote", "upload_file",
                "download_file", "read", "write", "remove", "calc", "search_docs", "list_references"));
        check(names.equals(TOOLS), "tools/list lists exactly the seven ssh tools", names + " " + list);

        SocketMcpClient.Answer refused = mcp.call("job_status", "{\"job_id\":\"1;id\"}");
        check(refused.json().contains("job_id must be a Slurm job id") && refused.json().contains("\"isError\":true"),
                "a refused job_id is answered as an error before ssh starts", refused.toString());
        SocketMcpClient.Answer unreachable = mcp.call("run_command", "{\"command\":\"hostname\"}");
        check(unreachable.json().contains("ssh failed (exit 255)"),
                "run_command reaches ssh, which fails for the unreachable host with exit 255", unreachable.toString());
        System.out.println(failures.isEmpty() ? "ALL PASSED" : failures.size() + " FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static String perms(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : " -- " + detail));
        if (!ok) failures.add(what);
    }
}
