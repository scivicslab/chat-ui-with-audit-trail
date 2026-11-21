package com.scivicslab.sshtools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one {@code ssh} command line the ssh tools run ({@code SshOnlyNoWebApi_261121_oo01}; tasks/todo.md, "Stage 11
 * spec", P2 and P4). It is an argument array for {@link ProcessBuilder}, never a shell line:
 *
 * <pre>
 * ssh -F &lt;ssh-connector's ssh_config&gt; -o BatchMode=yes -o StrictHostKeyChecking=accept-new
 *     -o IdentitiesOnly=yes -o ClearAllForwardings=yes -o ForwardAgent=no -o PermitLocalCommand=no
 *     -- &lt;host alias&gt; &lt;remote command&gt;
 * </pre>
 *
 * The host alias and the configuration are the deployment's; the model's text is only the one argument after the
 * alias, so {@code ssh} never reads it as an option.
 */
public final class SshCommandLine {

    /** The options every run carries, in order, after {@code -F <config>}. */
    public static final List<String> FIXED_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "IdentitiesOnly=yes",
            "-o", "ClearAllForwardings=yes",
            "-o", "ForwardAgent=no",
            "-o", "PermitLocalCommand=no");

    private final String sshBinary;
    private final Path configFile;
    private final String hostAlias;

    /**
     * @param sshBinary  the {@code ssh} program, normally {@code "ssh"}
     * @param configFile ssh-connector's own {@code ssh_config}
     * @param hostAlias  the deployment's host alias in that file
     * @throws IllegalArgumentException when the alias is empty or starts with {@code -}
     */
    public SshCommandLine(String sshBinary, Path configFile, String hostAlias) {
        if (hostAlias == null || hostAlias.isEmpty() || hostAlias.startsWith("-")) {
            throw new IllegalArgumentException("host alias must be a name, not '" + hostAlias + "'");
        }
        this.sshBinary = sshBinary;
        this.configFile = configFile;
        this.hostAlias = hostAlias;
    }

    /** @return the host alias every run goes to */
    public String hostAlias() {
        return hostAlias;
    }

    /**
     * @param remoteCommand the command for the remote shell, passed as one argument after the host alias
     * @return the argument array: the program, {@code -F}, the fixed options, {@code --}, the alias, the command
     */
    public List<String> arguments(String remoteCommand) {
        List<String> args = new ArrayList<>(FIXED_OPTIONS.size() + 6);
        args.add(sshBinary);
        args.add("-F");
        args.add(configFile.toString());
        args.addAll(FIXED_OPTIONS);
        args.add("--");
        args.add(hostAlias);
        args.add(remoteCommand);
        return List.copyOf(args);
    }
}
