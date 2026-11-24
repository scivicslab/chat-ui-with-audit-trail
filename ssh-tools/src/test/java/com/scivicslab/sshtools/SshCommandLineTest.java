package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixed {@code ssh} argument array (tasks/todo.md, "Stage 11 spec", P2 and P4). */
class SshCommandLineTest {

    @TempDir
    Path dir;

    @Test
    void fixedOptionsInOrderThenDashDashThenTheAliasThenTheCommand() {
        Path config = dir.resolve("ssh_config");
        List<String> args = new SshCommandLine("ssh", config, "nig-a003").arguments("hostname");
        assertEquals(List.of("ssh", "-F", config.toString(),
                "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", "-o", "IdentitiesOnly=yes",
                "-o", "ClearAllForwardings=yes", "-o", "ForwardAgent=no", "-o", "PermitLocalCommand=no",
                "--", "nig-a003", "hostname"), args);
    }

    @Test
    void theModelsTextIsOneArgumentAfterTheAlias() {
        String text = "-oProxyCommand=touch /tmp/x";
        List<String> args = new SshCommandLine("ssh", dir.resolve("ssh_config"), "nig-a003").arguments(text);
        int dashDash = args.indexOf("--");
        assertEquals(args.size() - 3, dashDash, "-- comes right before the alias");
        assertEquals("nig-a003", args.get(dashDash + 1));
        assertEquals(text, args.get(dashDash + 2));
        assertEquals(1, args.stream().filter(a -> a.contains("ProxyCommand")).count());
    }

    /**
     * Runs the real {@code ssh -G} (prints the configuration it would use, without connecting) on the generated array.
     * The control run without {@code --} shows the same text is read as an option there, so the check bites.
     */
    @Test
    void realSshDashGDoesNotReadAnOptionAfterTheAlias() throws Exception {
        Path marker = dir.resolve("marker");
        Path key = dir.resolve("id_ed25519");
        Files.writeString(key, "dummy");
        Path config = SshConfigFile.write(dir.resolve("conf"), List.of(new SshConfigFile.HostEntry(
                "nig-a003", "127.0.0.1", "dummy", 2222, key, null)));
        String text = "-oProxyCommand=touch " + marker;
        List<String> args = new ArrayList<>(new SshCommandLine("ssh", config, "nig-a003").arguments(text));
        args.add(1, "-G");

        String printed = run(args);
        assertTrue(printed.contains("hostname 127.0.0.1"), printed);
        assertFalse(proxyCommand(printed).contains("touch"), "proxycommand: " + proxyCommand(printed));

        List<String> withoutDashDash = new ArrayList<>(args);
        withoutDashDash.remove("--");
        assertTrue(proxyCommand(run(withoutDashDash)).contains("touch"),
                "without -- the same text is an option; the check above would not bite otherwise");
        assertFalse(Files.exists(marker), "nothing ran on this computer");
    }

    private static String proxyCommand(String printed) {
        return printed.lines().filter(l -> l.startsWith("proxycommand ")).findFirst().orElse("");
    }

    private static String run(List<String> args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return out;
    }
}
