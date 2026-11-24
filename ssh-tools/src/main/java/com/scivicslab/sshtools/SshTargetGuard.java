package com.scivicslab.sshtools;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Refuses an ssh target that is the user's own computer (tasks/todo.md, "ssh-connector's startup guard"). If
 * ssh-connector's ssh_config pointed at this machine, {@code run_command} would run commands on it, outside the
 * container. Every host ssh-connector would connect to, each Host block's HostName and every ProxyJump target, is
 * resolved to all its addresses; a loopback or unspecified address, an address of one of this machine's network
 * interfaces, or this machine's own host name refuses the start. A host that does not resolve at start-up passes:
 * ssh cannot reach it either.
 *
 * <p>A plain object: name resolution, the interface addresses and the local host names come in through small seams,
 * so a test gives fixed values; {@link #system()} uses {@link InetAddress} and {@link NetworkInterface}. The check is
 * made once, at start-up: a name that DNS later maps to this machine is not caught.</p>
 */
public final class SshTargetGuard {

    /** Resolves a host name to all its addresses. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private final Resolver resolver;
    private final Supplier<Collection<InetAddress>> localAddresses;
    private final Supplier<Collection<String>> localNames;

    /**
     * @param resolver       resolves a host name to all its addresses
     * @param localAddresses this machine's network interface addresses
     * @param localNames     this machine's host name and FQDN
     */
    public SshTargetGuard(Resolver resolver, Supplier<Collection<InetAddress>> localAddresses,
                          Supplier<Collection<String>> localNames) {
        this.resolver = resolver;
        this.localAddresses = localAddresses;
        this.localNames = localNames;
    }

    /** @return the guard on this machine: real DNS, the real interfaces, the real host names */
    public static SshTargetGuard system() {
        return new SshTargetGuard(host -> Arrays.asList(InetAddress.getAllByName(host)), SshTargetGuard::interfaceAddresses,
                SshTargetGuard::hostNames);
    }

    /**
     * @param hosts the Host blocks ssh-connector writes
     * @return one English sentence per refused target, naming the host and the reason; empty when every target is
     *         another computer
     */
    public List<String> refusals(List<SshConfigFile.HostEntry> hosts) {
        Map<String, String> hostNameOfAlias = new HashMap<>();
        for (SshConfigFile.HostEntry h : hosts) hostNameOfAlias.put(h.alias(), h.hostName());
        // Every name ssh would connect to: each block's HostName, and each ProxyJump hop that is not an alias of a
        // block (an alias is reached through that block's HostName, which is checked already).
        Set<String> targets = new LinkedHashSet<>();
        for (SshConfigFile.HostEntry h : hosts) {
            targets.add(h.hostName());
            if (h.proxyJump() != null) {
                for (String hop : jumpHosts(h.proxyJump())) {
                    targets.add(hostNameOfAlias.getOrDefault(hop, hop));
                }
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (String n : localNames.get()) names.add(normalize(n));
        List<byte[]> local = new ArrayList<>();
        for (InetAddress a : localAddresses.get()) local.add(a.getAddress());
        List<String> refusals = new ArrayList<>();
        for (String target : targets) {
            String why = reason(target, names, local);
            if (why != null) refusals.add("ssh target '" + target + "' " + why);
        }
        return refusals;
    }

    /**
     * @throws IllegalStateException when any target is this machine, with every refusal in the message
     */
    public void check(List<SshConfigFile.HostEntry> hosts) {
        List<String> refusals = refusals(hosts);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("ssh-connector refuses to start: an ssh target must be a computer other "
                    + "than the user's own computer, because run_command would run commands on it outside the "
                    + "container. " + String.join("; ", refusals));
        }
    }

    /**
     * @param proxyJump a ProxyJump value: an alias, {@code host}, {@code user@host}, {@code host:port},
     *                  {@code [v6]:port}, or several of these separated by commas
     * @return the host part of each hop, in order
     */
    public static List<String> jumpHosts(String proxyJump) {
        List<String> hosts = new ArrayList<>();
        for (String hop : proxyJump.split(",")) {
            String h = hop.strip();
            if (h.startsWith("ssh://")) h = h.substring(6);
            int at = h.lastIndexOf('@');
            if (at >= 0) h = h.substring(at + 1);
            if (h.startsWith("[")) {
                int close = h.indexOf(']');
                h = close > 0 ? h.substring(1, close) : h.substring(1);
            } else if (h.indexOf(':') == h.lastIndexOf(':') && h.indexOf(':') >= 0) {
                h = h.substring(0, h.indexOf(':'));
            }
            if (!h.isEmpty()) hosts.add(h);
        }
        return hosts;
    }

    /** @return why {@code target} is this machine, or {@code null} when it is another computer */
    private String reason(String target, Set<String> names, List<byte[]> local) {
        if (names.contains(normalize(target))) return "is this computer's own name";
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(target);
        } catch (UnknownHostException e) {
            // ssh cannot reach a name that does not resolve; a name that DNS maps to this machine later is the gap
            // the start-up check leaves open.
            return null;
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress()) return "resolves to the loopback address " + a.getHostAddress();
            if (a.isAnyLocalAddress()) return "resolves to the unspecified address " + a.getHostAddress();
            for (byte[] mine : local) {
                if (Arrays.equals(mine, a.getAddress())) {
                    return "resolves to " + a.getHostAddress() + ", an address of this computer's network interface";
                }
            }
        }
        return null;
    }

    private static String normalize(String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }

    private static Collection<InetAddress> interfaceAddresses() {
        List<InetAddress> all = new ArrayList<>();
        try {
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                all.addAll(nic.inetAddresses().toList());
            }
        } catch (SocketException e) {
            throw new IllegalStateException("cannot list this computer's network interfaces: " + e.getMessage(), e);
        }
        return all;
    }

    private static Collection<String> hostNames() {
        Set<String> names = new LinkedHashSet<>();
        try {
            InetAddress self = InetAddress.getLocalHost();
            names.add(self.getHostName());
            names.add(self.getCanonicalHostName());
        } catch (UnknownHostException e) {
            // The local name does not resolve; the name from the environment is still compared.
        }
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) names.add(env);
        return names;
    }
}
