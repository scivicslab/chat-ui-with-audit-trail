package com.scivicslab.sshtools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ssh-connector's own {@code ssh_config} and {@code known_hosts} (tasks/todo.md, "Stage 11 spec", P4). They live in a
 * directory of ssh-connector's own, 0700, and are 0600. Every {@code Host} block names its key and its known-hosts
 * file, so the user's {@code ~/.ssh/config} and {@code ~/.ssh/known_hosts} are never read.
 */
public final class SshConfigFile {

    /** The file's name inside the directory. */
    public static final String CONFIG = "ssh_config";
    /** The known-hosts file's name inside the directory; it starts empty and {@code accept-new} fills it. */
    public static final String KNOWN_HOSTS = "known_hosts";

    private static final Set<PosixFilePermission> OWNER_DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> OWNER_FILE = PosixFilePermissions.fromString("rw-------");

    /**
     * One {@code Host} block.
     *
     * @param alias        the name the tools connect to, e.g. {@code nig-a003}
     * @param hostName     the real host name or address
     * @param user         the account
     * @param port         the port, or 0 for ssh's default
     * @param identityFile the one key this host is reached with
     * @param proxyJump    the alias of another block to jump through, or {@code null}
     */
    public record HostEntry(String alias, String hostName, String user, int port, Path identityFile, String proxyJump) {
    }

    private SshConfigFile() {
    }

    /**
     * @param hosts      the blocks, in order
     * @param knownHosts the known-hosts file every block names
     * @return the file's text
     * @throws IllegalArgumentException when a value is empty or holds white space, a quote, {@code #} or a control
     *                                  character, or a {@code proxyJump} names no block
     */
    public static String text(List<HostEntry> hosts, Path knownHosts) {
        Set<String> aliases = new HashSet<>();
        for (HostEntry h : hosts) aliases.add(h.alias());
        StringBuilder sb = new StringBuilder("# Written by ssh-connector. The user's ~/.ssh/config is not read.\n");
        for (HostEntry h : hosts) {
            sb.append("Host ").append(word("alias", h.alias())).append('\n');
            line(sb, "HostName", word("hostName", h.hostName()));
            line(sb, "User", word("user", h.user()));
            if (h.port() != 0) {
                if (h.port() < 1 || h.port() > 65535) throw new IllegalArgumentException("port out of range: " + h.port());
                line(sb, "Port", String.valueOf(h.port()));
            }
            line(sb, "IdentityFile", word("identityFile", h.identityFile().toAbsolutePath().toString()));
            line(sb, "IdentitiesOnly", "yes");
            line(sb, "BatchMode", "yes");
            line(sb, "StrictHostKeyChecking", "accept-new");
            line(sb, "UserKnownHostsFile", word("knownHosts", knownHosts.toAbsolutePath().toString()));
            line(sb, "GlobalKnownHostsFile", "/dev/null");
            if (h.proxyJump() != null) {
                if (!aliases.contains(h.proxyJump()) || h.proxyJump().equals(h.alias())) {
                    throw new IllegalArgumentException("proxyJump '" + h.proxyJump() + "' names no other Host block");
                }
                line(sb, "ProxyJump", h.proxyJump());
            }
        }
        return sb.toString();
    }

    /**
     * Writes {@value #CONFIG} (0600) and, when it is missing, an empty {@value #KNOWN_HOSTS} (0600) into {@code dir},
     * which is created 0700.
     *
     * @return the configuration file
     * @throws IOException when {@code dir} exists and is open to its group or others, or cannot be written
     */
    public static Path write(Path dir, List<HostEntry> hosts) throws IOException {
        Path known = dir.resolve(KNOWN_HOSTS);
        String text = text(hosts, known);
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException(dir + " is not a directory");
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            if (!perms.equals(OWNER_DIR)) {
                throw new IOException(dir + " is " + PosixFilePermissions.toString(perms)
                        + "; ssh-connector's ssh directory must be rwx------");
            }
        } else {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_DIR));
            Files.setPosixFilePermissions(dir, OWNER_DIR);
        }
        Path config = dir.resolve(CONFIG);
        Path tmp = dir.resolve(CONFIG + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(OWNER_FILE));
        Files.writeString(tmp, text);
        Files.setPosixFilePermissions(tmp, OWNER_FILE);
        Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (Files.notExists(known, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(known, PosixFilePermissions.asFileAttribute(OWNER_FILE));
        }
        Files.setPosixFilePermissions(known, OWNER_FILE);
        return config;
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append("    ").append(key).append(' ').append(value).append('\n');
    }

    /** @return {@code value} when it is one ssh_config word that cannot change the file's meaning */
    private static String word(String field, String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(field + " is empty");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '"' || c == '\'' || c == '#'
                    || c == '=') {
                throw new IllegalArgumentException(field + " must be one word without quotes, '#' or '=': " + value);
            }
        }
        return value;
    }
}
