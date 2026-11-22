package com.scivicslab.chatui.confine;

import java.util.List;

/**
 * One harness run in a Podman container (tasks/todo.md, stage 9): the configuration file it reads, where it reads it
 * from inside the container, and the command that starts it. Each configuration points the harness's MCP client at
 * {@link PodmanConfinement#mcpUrl()} with the scope's key, and its LLM client at
 * {@link PodmanConfinement#llmBaseUrl(String)} with the throw-away key, both through the in-container relay.
 */
public interface PodmanHarness {

    /** @return the configuration file's text for {@code scope}, using {@code model} */
    String configText(String scope, String model);

    /** @return where the harness reads its configuration inside the container */
    String containerConfigPath();

    /** @return the command that starts the harness inside the container, after the relay */
    List<String> command();
}
