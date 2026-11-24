package com.scivicslab.sshtools;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.agent.ToolCatalog;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seven tools against a fake {@code ssh} that records its arguments and standard input and answers like the
 * cluster would (tasks/todo.md, "Stage 11 spec"). The real ssh and a real sshd are the 5.14 E2E's.
 */
class SshToolsTest {

    @TempDir
    Path base;

    Path work;
    Path calls;
    ActorSystem system;
    SshTools tools;

    /** Records call N's arguments in calls/N/args (one per line) and its standard input in calls/N/stdin. */
    private static final String FAKE_SSH = """
            #!/bin/sh
            n=$(ls "$CALLS" | wc -l); d="$CALLS/$n"; mkdir "$d"
            for a in "$@"; do printf '%s\\n' "$a"; done > "$d/args"
            cat > "$d/stdin"
            eval "last=\\${$#}"
            case "$last" in
              *"sbatch "*) echo "Submitted batch job 4242" ;;
              *"squeue "*) : ;;
              *"sacct "*) echo "4242|job|COMPLETED|0:0|00:00:01" ;;
              *"scancel "*) : ;;
              "cat -- "*) printf 'remote bytes\\n' ;;
              "sleep") sleep 30 ;;
              *) echo "ran: $last" ;;
            esac
            """;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(base.resolve("work"));
        calls = Files.createDirectory(base.resolve("calls"));
        Path ssh = base.resolve("fake-ssh");
        Files.writeString(ssh, FAKE_SSH.replace("$CALLS", calls.toString()));
        Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
        system = new ActorSystem("ssh-tools-test");
        SshLimits limits = new SshLimits(Duration.ofSeconds(2), 1 << 20, 64);
        ActorRef<SshRunner> runner = system.actorOf("ssh-test-host", new SshRunner(limits));
        tools = new SshTools(runner, new SshCommandLine(ssh.toString(), base.resolve("ssh_config"), "test-host"),
                new SlurmSettings("sbatch", "squeue", "sacct", "scancel", "jobs dir"), limits,
                new FileAccessScope(work, List.of()), () -> "J1");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String call(String tool, JSONObject args) {
        return tools.run(tool, args.toString());
    }

    private List<String> args(int n) throws Exception {
        return Files.readAllLines(calls.resolve(String.valueOf(n)).resolve("args"));
    }

    private String remote(int n) throws Exception {
        List<String> a = args(n);
        return a.get(a.size() - 1);
    }

    private long callCount() throws Exception {
        try (var s = Files.list(calls)) {
            return s.count();
        }
    }

    @Test
    void theSevenToolsAreDefinedInOrderWithTheirArguments() {
        List<ToolCatalog.Spec> specs = SshTools.specs();
        assertEquals(SshTools.NAMES, specs.stream().map(ToolCatalog.Spec::name).toList());
        assertEquals(List.of("script", "name"), specs.get(1).signature());
        assertEquals(List.of("script"), specs.get(1).required());
        assertEquals(List.of("remote_path", "local_path", "overwrite"), specs.get(6).signature());
        for (String name : SshTools.NAMES) assertTrue(tools.defines(name) && tools.offers(name), name);
        assertFalse(tools.defines("read"));
        assertEquals(null, tools.run("read", "{}"));
    }

    @Test
    void runCommandPassesTheTextAsTheOneArgumentAfterTheAlias() throws Exception {
        String text = "-oProxyCommand=touch /tmp/x; echo $(id)";
        String answer = call("run_command", new JSONObject().put("command", text));
        assertTrue(answer.contains("ran: " + text), answer);
        List<String> a = args(0);
        assertEquals(List.of("--", "test-host", text), a.subList(a.size() - 3, a.size()));
        assertEquals("-F", a.get(0));
    }

    @Test
    void submitJobSendsTheScriptOnStandardInputAndReturnsTheJobId() throws Exception {
        String script = "#!/bin/bash\n#SBATCH -t 1\necho 'it''s' \"$(hostname)\"\n";
        String answer = call("submit_job", new JSONObject().put("script", script).put("name", "my-job_1"));
        assertTrue(answer.contains("job_id: 4242"), answer);
        assertEquals(script, Files.readString(calls.resolve("0").resolve("stdin")), "the script arrived exactly");
        assertEquals("mkdir -p -- 'jobs dir/J1' && cat > 'jobs dir/J1/job.sh' && sbatch --job-name='my-job_1' "
                + "'jobs dir/J1/job.sh'", remote(0));
    }

    @Test
    void refusedArgumentsNeverStartSsh() throws Exception {
        assertTrue(call("submit_job", new JSONObject().put("script", "x").put("name", "a b")).startsWith("error: "));
        assertTrue(call("job_status", new JSONObject().put("job_id", "1;id")).startsWith("error: "));
        assertTrue(call("cancel_job", new JSONObject().put("job_id", "$(id)")).startsWith("error: "));
        assertTrue(call("run_command", new JSONObject().put("command", "a\nb")).startsWith("error: "));
        assertTrue(call("list_remote", new JSONObject().put("path", "a\u0000b")).startsWith("error: "));
        assertTrue(call("download_file", new JSONObject().put("remote_path", "x").put("local_path", "../x"))
                .startsWith("error: "));
        assertTrue(call("upload_file", new JSONObject().put("local_path", "/etc/passwd").put("remote_path", "x"))
                .startsWith("error: "));
        assertEquals(0, callCount());
    }

    @Test
    void jobStatusAsksSqueueThenSacct() throws Exception {
        String answer = call("job_status", new JSONObject().put("job_id", "4242"));
        assertTrue(answer.contains("COMPLETED"), answer);
        assertTrue(remote(0).startsWith("squeue -j 4242 "), remote(0));
        assertTrue(remote(1).startsWith("sacct -j 4242 "), remote(1));
        assertTrue(call("cancel_job", new JSONObject().put("job_id", "4242_3")).startsWith("cancelled"));
        assertEquals("scancel 4242_3", remote(2));
    }

    @Test
    void listRemoteQuotesThePath() throws Exception {
        call("list_remote", new JSONObject().put("path", "a'b $(id)"));
        assertEquals("ls -la -- 'a'\\''b $(id)'", remote(0));
    }

    @Test
    void uploadSendsTheFileWithinTheSizeLimit() throws Exception {
        Files.writeString(work.resolve("in.txt"), "hello\n");
        String answer = call("upload_file", new JSONObject().put("local_path", "in.txt").put("remote_path", "r/in.txt"));
        assertTrue(answer.startsWith("uploaded 6 bytes"), answer);
        assertEquals("cat > 'r/in.txt'", remote(0));
        assertEquals("hello\n", Files.readString(calls.resolve("0").resolve("stdin")));
        Files.writeString(work.resolve("big"), "x".repeat(65));
        String big = call("upload_file", new JSONObject().put("local_path", "big").put("remote_path", "b"));
        assertTrue(big.startsWith("error: ") && big.contains("64 bytes"), big);
        assertEquals(1, callCount());
    }

    @Test
    void downloadWritesInsideAndReplacesOnlyWhenAsked() throws Exception {
        String answer = call("download_file", new JSONObject().put("remote_path", "r/out.txt")
                .put("local_path", "got/out.txt"));
        assertTrue(answer.startsWith("downloaded 13 bytes"), answer);
        assertEquals("cat -- 'r/out.txt'", remote(0));
        assertEquals("remote bytes\n", Files.readString(work.resolve("got/out.txt")));
        try (var s = Files.list(work.resolve("got"))) {
            assertEquals(1, s.count(), "no temporary file is left");
        }
        String again = call("download_file", new JSONObject().put("remote_path", "r/out.txt")
                .put("local_path", "got/out.txt"));
        assertTrue(again.startsWith("error: ") && again.contains("overwrite"), again);
        assertTrue(call("download_file", new JSONObject().put("remote_path", "r/out.txt")
                .put("local_path", "got/out.txt").put("overwrite", true)).startsWith("downloaded"));
    }

    @Test
    void aLimitIsNamedInTheToolsError() {
        String answer = call("run_command", new JSONObject().put("command", "sleep"));
        assertTrue(answer.startsWith("error: ") && answer.contains("time limit of 2 s"), answer);
    }
}
