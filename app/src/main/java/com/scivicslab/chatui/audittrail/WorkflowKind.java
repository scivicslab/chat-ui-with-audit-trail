package com.scivicslab.chatui.audittrail;

import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three kinds of Turing Workflow YAML this program runs, told apart by what each must do
 * ({@code WorkflowKinds_261021_oo01}).
 *
 * <p>All three live side by side under {@code /workflows/}, so the file's place says nothing about
 * which it is. What it does does: an agent loop is entered at the state {@code think}, which
 * {@code ChatSession.start()} moves to; a prompt construction hands the conversation the request it
 * built, by one of the three actions that put a prompt in the conversation's send queue. A file
 * that does neither is a job's workflow.</p>
 */
public enum WorkflowKind {

    /** Run by a conversation's {@code ChatSession} actor, once per question. */
    AGENT_LOOP,
    /** Called by an agent loop before each request, to build that request. */
    PROMPT_CONSTRUCTION,
    /** Run by a {@code PlanRunner} actor the project makes for one job. */
    JOB;

    /** The actions that put a prompt in the conversation's send queue; only a prompt construction calls them. */
    private static final Set<String> QUEUE_A_PROMPT =
            Set.of("buildDefaultPrompt", "appendConstructedPrompt", "appendConstructedMessages");

    /**
     * @param yaml a workflow's text
     * @return its kind; {@link #JOB} for text that cannot be read, which the job's own check then refuses
     */
    public static WorkflowKind of(String yaml) {
        Object parsed;
        try {
            parsed = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            return JOB;
        }
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("steps") instanceof List<?> steps)) return JOB;
        boolean queuesAPrompt = false;
        for (Object step : steps) {
            if (!(step instanceof Map<?, ?> s)) continue;
            if (s.get("states") instanceof List<?> states && !states.isEmpty()
                    && "think".equals(String.valueOf(states.get(0)))) {
                return AGENT_LOOP;
            }
            if (s.get("actions") instanceof List<?> actions) {
                for (Object action : actions) {
                    if (action instanceof Map<?, ?> a && QUEUE_A_PROMPT.contains(String.valueOf(a.get("method")))) {
                        queuesAPrompt = true;
                    }
                }
            }
        }
        return queuesAPrompt ? PROMPT_CONSTRUCTION : JOB;
    }
}
