package com.scivicslab.sshtools;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ssh-connector never connects to the user's own computer (tasks/todo.md, "ssh-connector's startup guard"). */
class SshTargetGuardTest {

    private static final Path KEY = Path.of("/keys/id_ed25519");

    /** A resolver from fixed answers: no real DNS. */
    private static SshTargetGuard.Resolver dns(Map<String, List<String>> answers) {
        return host -> {
            List<String> ips = answers.get(host);
            if (ips == null) throw new UnknownHostException(host);
            List<InetAddress> out = new ArrayList<>();
            for (String ip : ips) out.add(InetAddress.getByName(ip));
            return out;
        };
    }

    /** This test machine: interface 192.168.5.12, host name my-pc, FQDN my-pc.example.org. */
    private static SshTargetGuard guard(Map<String, List<String>> answers) {
        return new SshTargetGuard(dns(answers), () -> {
            try {
                return List.of(InetAddress.getByName("192.168.5.12"), InetAddress.getByName("fe80::1"));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }, () -> List.of("my-pc", "my-pc.example.org"));
    }

    private static final Map<String, List<String>> DNS = Map.of(
            "localhost", List.of("127.0.0.1", "::1"),
            "127.0.0.2", List.of("127.0.0.2"),
            "::1", List.of("::1"),
            "0.0.0.0", List.of("0.0.0.0"),
            "my-pc", List.of("10.9.9.9"),
            "MY-PC.example.org", List.of("10.9.9.9"),
            "192.168.5.12", List.of("192.168.5.12"),
            "pc-alias.example.org", List.of("10.1.1.1", "192.168.5.12"),
            "a003", List.of("172.19.0.3"),
            "gw.example.org", List.of("133.39.0.1"));

    private static SshConfigFile.HostEntry host(String alias, String hostName, String jump) {
        return new SshConfigFile.HostEntry(alias, hostName, "u1", 0, KEY, jump);
    }

    private static void refused(String hostName, String why) {
        List<String> r = guard(DNS).refusals(List.of(host("nig-a003", hostName, null)));
        assertEquals(1, r.size(), hostName + ": " + r);
        assertTrue(r.get(0).contains(hostName) && r.get(0).contains(why), r.get(0));
    }

    @Test
    void localhostIsRefused() {
        refused("localhost", "loopback");
    }

    @Test
    void anyLoopbackAddressIsRefused() {
        refused("127.0.0.2", "loopback");
    }

    @Test
    void ipv6LoopbackIsRefused() {
        refused("::1", "loopback");
    }

    @Test
    void theUnspecifiedAddressIsRefused() {
        refused("0.0.0.0", "unspecified");
    }

    @Test
    void thisMachinesHostNameAndFqdnAreRefusedWhateverTheyResolveTo() {
        refused("my-pc", "this computer's own name");
        refused("MY-PC.example.org", "this computer's own name");
    }

    @Test
    void anAddressOfThisMachinesInterfaceIsRefused() {
        refused("192.168.5.12", "network interface");
        refused("pc-alias.example.org", "network interface");
    }

    @Test
    void aProxyJumpThroughLocalhostIsRefused() {
        List<String> r = guard(DNS).refusals(List.of(host("gw", "localhost", null), host("nig-a003", "a003", "gw")));
        assertEquals(1, r.size(), r.toString());
        assertTrue(r.get(0).contains("localhost") && r.get(0).contains("loopback"), r.get(0));
    }

    @Test
    void aProxyJumpThatIsNotAnAliasIsParsedForItsHost() {
        assertEquals(List.of("localhost"), SshTargetGuard.jumpHosts("dummy@localhost:2222"));
        assertEquals(List.of("::1"), SshTargetGuard.jumpHosts("[::1]:22"));
        assertEquals(List.of("gw.example.org", "localhost"), SshTargetGuard.jumpHosts("u@gw.example.org,localhost"));
        assertEquals(List.of("gw"), SshTargetGuard.jumpHosts("gw"));
        List<String> r = guard(DNS).refusals(List.of(host("nig-a003", "a003", "dummy@localhost:2222")));
        assertEquals(1, r.size(), r.toString());
        assertTrue(r.get(0).contains("localhost"), r.get(0));
    }

    @Test
    void aHostThatDoesNotResolvePassesBecauseSshCannotReachItEither() {
        assertEquals(List.of(), guard(DNS).refusals(List.of(host("nig-a003", "no-such-host.invalid", null))));
    }

    @Test
    void anotherComputerPasses() {
        SshTargetGuard g = guard(DNS);
        List<SshConfigFile.HostEntry> hosts = List.of(host("nig-gw", "gw.example.org", null),
                host("nig-a003", "a003", "nig-gw"));
        assertEquals(List.of(), g.refusals(hosts));
        g.check(hosts);
    }

    @Test
    void checkRefusesTheStartWithEveryReason() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> guard(DNS).check(List.of(
                host("a", "localhost", null), host("b", "192.168.5.12", null))));
        assertTrue(e.getMessage().contains("localhost") && e.getMessage().contains("192.168.5.12")
                && e.getMessage().contains("user's own computer"), e.getMessage());
    }
}
