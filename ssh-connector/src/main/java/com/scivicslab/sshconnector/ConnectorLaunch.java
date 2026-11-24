package com.scivicslab.sshconnector;

import com.scivicslab.chatui.confine.PodmanConfinement;
import com.scivicslab.chatui.harness.HarnessLaunch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How ssh-connector starts its one harness (tasks/todo.md, "Stage 11 spec"): {@code podman run} as
 * {@link com.scivicslab.chatui.confine.PodmanConfinement} builds it, then the harness's own arguments, as a
 * {@link HarnessLaunch}. The keys go in podman's environment, never on its command line, and the real LLM credential is
 * removed from that environment.
 */
public final class ConnectorLaunch {

    /** The variable that may carry the real LLM credential into ssh-connector; podman never sees it. */
    public static final String CREDENTIAL_VAR = "AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL";

    private ConnectorLaunch() {
    }

    /**
     * @param settings    ssh-connector's settings; the harness must not be {@code none}
     * @param configFile  the harness's configuration file, written for this launch
     * @param mcpKey      the scope's MCP key
     * @param llmKey      the scope's throw-away LLM proxy key
     * @param harnessArgs the harness's own arguments, after its command
     * @param terminal    whether ssh-connector runs on a terminal; then podman gets {@code -t} too
     * @return the launch
     */
    public static HarnessLaunch launch(ConnectorSettings settings, Path configFile, String mcpKey, String llmKey,
                                       List<String> harnessArgs, boolean terminal) {
        PodmanConfinement.Launch launch = new PodmanConfinement.Launch(settings.image(), settings.privateDir(),
                settings.socketDir(), settings.workDir(), configFile, settings.podmanHarness(), mcpKey, llmKey);
        List<String> command = new ArrayList<>(PodmanConfinement.arguments(launch));
        if (terminal) command.add(command.indexOf("-i") + 1, "-t");
        command.addAll(harnessArgs);
        return new HarnessLaunch(command, List.of(CREDENTIAL_VAR), PodmanConfinement.environment(launch), null);
    }
}
