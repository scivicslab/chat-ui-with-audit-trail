package com.scivicslab.chatui.core.actor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * The role table: for each role of a team, the provider, the model and the two timekeeper limits a ConversationSquad
 * actor in that role is made with ({@code TeamBuiltByProjectWorkflow_261108_oo01}).
 *
 * <p>The bundled table is {@code /team/team-roles.yaml} on the classpath. A project may keep its own at
 * {@code <working directory>/team/team-roles.yaml}; {@link #of} takes whichever text it is given and falls back to
 * the bundled one for blank text. A table is never changed: {@link #overriding} returns a new one.</p>
 */
public final class TeamRoles {

    /** The roles every table must define. */
    public static final List<String> NAMES = List.of("manager", "worker", "repeater");

    /** The classpath location of the bundled table. */
    static final String BUNDLED = "/team/team-roles.yaml";

    /**
     * One role's settings.
     *
     * @param provider              the provider's value ({@code claude}, {@code openai-compat})
     * @param model                 a model that provider lists, or {@code ""} for its own default
     * @param urgentWaitMinutes     how long an urgent request may wait before the running turn is paused
     * @param childReplyWarnMinutes how long a request may go without a reply before the owner is told
     */
    public record Role(String provider, String model, long urgentWaitMinutes, long childReplyWarnMinutes) {}

    private final Map<String, Role> roles;

    private TeamRoles(Map<String, Role> roles) {
        this.roles = roles;
    }

    /** @return the bundled table */
    public static TeamRoles bundled() {
        try (InputStream in = TeamRoles.class.getResourceAsStream(BUNDLED)) {
            if (in == null) throw new IllegalStateException("the bundled team-roles.yaml is missing from the classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the bundled team-roles.yaml: " + e.getMessage(), e);
        }
    }

    /**
     * @param text a project's own table, or blank for none
     * @return the table the text defines, or the bundled one for blank text
     * @throws IllegalArgumentException when the text is not a table: it cannot be read, lacks a role, or has a limit
     *                                  below one minute
     */
    public static TeamRoles of(String text) {
        return text == null || text.isBlank() ? bundled() : parse(text);
    }

    private static TeamRoles parse(String text) {
        Object parsed;
        try {
            parsed = new Yaml().load(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("team-roles.yaml cannot be read: " + e.getMessage(), e);
        }
        if (!(parsed instanceof Map<?, ?> top) || !(top.get("roles") instanceof Map<?, ?> table)) {
            throw new IllegalArgumentException("team-roles.yaml has no 'roles' table");
        }
        Map<String, Role> roles = new LinkedHashMap<>();
        for (String name : NAMES) {
            if (!(table.get(name) instanceof Map<?, ?> r)) {
                throw new IllegalArgumentException("team-roles.yaml defines no role '" + name + "'");
            }
            String provider = text(r.get("provider"));
            if (provider.isBlank()) throw new IllegalArgumentException("the role '" + name + "' names no provider");
            roles.put(name, new Role(provider, text(r.get("model")),
                    minutes(r.get("urgentWaitMinutes"), name, "urgentWaitMinutes"),
                    minutes(r.get("childReplyWarnMinutes"), name, "childReplyWarnMinutes")));
        }
        return new TeamRoles(roles);
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString().strip();
    }

    private static long minutes(Object v, String role, String key) {
        long n;
        try {
            n = v instanceof Number num ? num.longValue() : Long.parseLong(text(v));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the role '" + role + "' needs a whole number of minutes for " + key);
        }
        if (n < 1) throw new IllegalArgumentException("the role '" + role + "' needs " + key + " of at least 1 minute");
        return n;
    }

    /** @return the settings of the role; it exists, since every table defines {@link #NAMES} */
    public Role role(String name) {
        Role r = roles.get(name);
        if (r == null) throw new IllegalArgumentException("no such role: " + name);
        return r;
    }

    /**
     * A table with one role's provider and model replaced, for one run.
     *
     * @param provider the provider to use, or blank to keep the table's
     * @param model    the model to use, or blank to keep the table's, except that a new provider with no model named
     *                 means that provider's own default model, not the table's model (which belongs to the old provider)
     */
    public TeamRoles overriding(String roleName, String provider, String model) {
        boolean newProvider = provider != null && !provider.isBlank();
        boolean newModel = model != null && !model.isBlank();
        if (!newProvider && !newModel) return this;
        Role old = role(roleName);
        Role next = new Role(newProvider ? provider.strip() : old.provider(),
                newModel ? model.strip() : newProvider ? "" : old.model(),
                old.urgentWaitMinutes(), old.childReplyWarnMinutes());
        Map<String, Role> copy = new LinkedHashMap<>(roles);
        copy.put(roleName, next);
        return new TeamRoles(copy);
    }
}
