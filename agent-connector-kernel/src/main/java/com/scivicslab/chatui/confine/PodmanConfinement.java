package com.scivicslab.chatui.confine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code podman run} that confines a harness other than Claude Code ({@code HarnessConfinement_261121_oo01};
 * tasks/todo.md, Decisions 9 and 10, stage 9). A plain object that only builds arguments and variables; whoever
 * launches the harness runs them.
 *
 * <ul>
 * <li>{@code --network=none}: the container has no network.</li>
 * <li>agent-connector-kernel's MCP server and LLM proxy are reached only through their two Unix sockets, bind-mounted
 * into {@value #SOCKET_DIR}. The image's relay ({@value #RELAY}, {@code socat}) exposes them inside the container
 * on 127.0.0.1:{@value #MCP_PORT} and 127.0.0.1:{@value #LLM_PORT}, and then runs the harness.</li>
 * <li>The only other mounts are the working directory and the harness's configuration file (read-only). None of them
 * may lie inside agent-connector-kernel's own directory, which holds the secrets.</li>
 * <li>The keys are passed by variable name only ({@code -e NAME}), so their values are in the environment podman is
 * started with and never on its command line.</li>
 * </ul>
 */
public final class PodmanConfinement {

    /** Where the sockets appear inside the container. */
    public static final String SOCKET_DIR = "/run/agent-connector-kernel";
    /** The MCP server's socket file name, on both sides. */
    public static final String MCP_SOCKET = "mcp.sock";
    /** The LLM proxy's socket file name, on both sides. */
    public static final String LLM_SOCKET = "llm.sock";
    /** The relay in the image: exposes the sockets on 127.0.0.1, then runs its arguments. */
    public static final String RELAY = "/usr/local/bin/ack-relay";
    /** The port the relay exposes the MCP server on, inside the container. */
    public static final int MCP_PORT = 18765;
    /** The port the relay exposes the LLM proxy on, inside the container. */
    public static final int LLM_PORT = 18766;
    /** The working directory inside the container. */
    public static final String WORK_DIR = "/work";
    /** The home directory of the image's user. */
    public static final String HOME = "/home/agent";
    /** The variable holding the LLM proxy's throw-away key. */
    public static final String LLM_KEY_VAR = "ACK_LLM_KEY";
    /** The variable holding the MCP scope's key. */
    public static final String MCP_KEY_VAR = "ACK_MCP_KEY";

    /** @return the MCP server's URL inside the container */
    public static String mcpUrl() {
        return "http://127.0.0.1:" + MCP_PORT + "/mcp";
    }

    /** @return the LLM proxy's OpenAI-compatible base URL for {@code scope}, inside the container */
    public static String llmBaseUrl(String scope) {
        return "http://127.0.0.1:" + LLM_PORT + "/" + scope + "/v1";
    }

    /**
     * What one launch needs.
     *
     * @param image      the harness image, e.g. {@code localhost/ack-codex:0.171.0}
     * @param kernelDir  agent-connector-kernel's own directory; nothing inside it is mounted
     * @param socketDir  the owner-only directory holding {@value #MCP_SOCKET} and {@value #LLM_SOCKET}
     * @param workDir    the working directory, mounted at {@value #WORK_DIR}
     * @param configFile the harness configuration file written for this launch
     * @param harness    which harness
     * @param mcpKey     the MCP scope's key
     * @param llmKey     the LLM proxy's throw-away key
     */
    public record Launch(String image, Path kernelDir, Path socketDir, Path workDir, Path configFile,
                         PodmanHarness harness, String mcpKey, String llmKey) {
    }

    private PodmanConfinement() {
    }

    /**
     * @return the command line: {@code podman run} with the confinement above, the image, the relay and the harness
     * @throws IllegalArgumentException when a mounted path lies inside agent-connector-kernel's directory
     */
    public static List<String> arguments(Launch launch) {
        Path kernel = launch.kernelDir().toAbsolutePath().normalize();
        for (Path mounted : List.of(launch.socketDir(), launch.workDir(), launch.configFile())) {
            if (mounted.toAbsolutePath().normalize().startsWith(kernel)) {
                throw new IllegalArgumentException(mounted + " lies inside agent-connector-kernel's directory " + kernel
                        + "; nothing there may be mounted into the container");
            }
        }
        List<String> args = new ArrayList<>(List.of("podman", "run", "--rm", "-i", "--network=none",
                "--userns=keep-id", "--cap-drop=all", "--security-opt=no-new-privileges"));
        Path sockets = launch.socketDir().toAbsolutePath().normalize();
        args.add("-v");
        args.add(sockets.resolve(MCP_SOCKET) + ":" + SOCKET_DIR + "/" + MCP_SOCKET);
        args.add("-v");
        args.add(sockets.resolve(LLM_SOCKET) + ":" + SOCKET_DIR + "/" + LLM_SOCKET);
        args.add("-v");
        args.add(launch.workDir().toAbsolutePath().normalize() + ":" + WORK_DIR);
        args.add("-w");
        args.add(WORK_DIR);
        args.add("-v");
        args.add(launch.configFile().toAbsolutePath().normalize() + ":" + launch.harness().containerConfigPath() + ":ro");
        args.add("-e");
        args.add(LLM_KEY_VAR);
        args.add("-e");
        args.add(MCP_KEY_VAR);
        args.add(launch.image());
        args.add(RELAY);
        args.addAll(launch.harness().command());
        return args;
    }

    /** @return the variables podman must be started with: the two keys, which {@code -e NAME} passes on */
    public static Map<String, String> environment(Launch launch) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(LLM_KEY_VAR, launch.llmKey());
        env.put(MCP_KEY_VAR, launch.mcpKey());
        return env;
    }
}
