package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.SkillRegistry;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code load_skill} tool: returns one skill's full instructions from the shared
 * {@link SkillRegistry} ({@code SkillAndAgentsFile_260830_oo01}).
 *
 * <p>This is the second of the Agent Skills specification's three loading levels. Every
 * conversation already carries the catalog — each skill's name and description — in its system
 * prompt; this tool is how it turns one of those names into the instructions themselves, and it
 * is the only path by which a skill's body enters a conversation's history.</p>
 */
public final class LoadSkillTool {

    private LoadSkillTool() {}

    private static final Logger LOG = Logger.getLogger(LoadSkillTool.class.getName());
    private static final int TIMEOUT_SECONDS = 10;

    /**
     * @param registry the shared {@link SkillRegistry}
     * @param name     the skill's name, as listed in the conversation's system prompt
     * @return the skill's instructions, or an {@code error: ...} string naming the skills that
     *         are available
     */
    public static String load(ActorRef<SkillRegistry> registry, String name) {
        if (registry == null) return "error: no skill registry is wired to this conversation";
        if (name == null || name.isBlank()) return "error: name is required";
        String wanted = name.strip();
        try {
            String body = registry.ask(r -> r.bodyOf(wanted)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (body != null) return body;
            List<String> available =
                    registry.ask(SkillRegistry::getSkillNames).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return "error: no skill named '" + wanted + "'. Available: "
                    + (available.isEmpty() ? "(none)" : String.join(", ", available));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "load_skill: failed for '" + wanted + "'", e);
            return "error: load_skill failed: " + e.getMessage();
        }
    }
}
