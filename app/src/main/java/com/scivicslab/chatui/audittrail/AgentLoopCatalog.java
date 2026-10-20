package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.core.actor.ChatSessionIIAR;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * The agent loops a conversation can be put on: the bundled workflows whose first state is
 * {@code think}, the entry state {@code ChatSession.start()} moves to whichever loop is installed
 * ({@code AgentLoopDecidesThePrompt_261020_oo01}).
 *
 * <p>Choosing one is {@code POST .../agent-loop-workflow}; this lists what can be chosen. It is
 * not {@code GET .../workflows}, which shows the workflows this conversation is using now.</p>
 */
public final class AgentLoopCatalog {

    private AgentLoopCatalog() {}

    /**
     * One selectable loop.
     *
     * @param file           the file to give {@code POST .../agent-loop-workflow}, e.g. {@code plain-text-loop.yaml}
     * @param title          the workflow's {@code name:}
     * @param description    the first line of its {@code description:}, or {@code ""}
     * @param promptWorkflow the prompt construction it names, or the default when it names none
     * @param current        whether the conversation is on it now
     */
    public record Loop(String file, String title, String description, String promptWorkflow, boolean current) {}

    /**
     * @param currentFile the loop the conversation is on now, as {@code .yaml} file name
     * @return the bundled agent loops, by file name
     */
    public static List<Loop> list(String currentFile) {
        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(null);
        List<Loop> loops = new ArrayList<>();
        for (ProjectWorkflowCatalog.Entry entry : catalog.list("")) {
            ProjectWorkflowCatalog.Document doc = catalog.read(entry.name());
            if (doc == null || !isAgentLoop(doc.yaml())) continue;
            String file = entry.name() + ".yaml";
            String promptWorkflow = ChatSessionIIAR.promptWorkflowNamedBy(
                    doc.yaml().getBytes(StandardCharsets.UTF_8), file);
            loops.add(new Loop(file, entry.title(), entry.description(), promptWorkflow,
                    file.equals(currentFile)));
        }
        loops.sort((a, b) -> a.file().compareTo(b.file()));
        return List.copyOf(loops);
    }

    /**
     * @param yaml a workflow's text
     * @return whether one of its steps leaves the state {@code think}, which only an agent loop does
     */
    static boolean isAgentLoop(String yaml) {
        Object parsed;
        try {
            parsed = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            return false;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("steps") instanceof List<?> steps)) return false;
        for (Object step : steps) {
            if (step instanceof Map<?, ?> s && s.get("states") instanceof List<?> states
                    && !states.isEmpty() && "think".equals(String.valueOf(states.get(0)))) {
                return true;
            }
        }
        return false;
    }
}
