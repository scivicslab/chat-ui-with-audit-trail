package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.scivicslab.chatui.core.iolog.IoLogView;
import com.scivicslab.chatui.core.iolog.SessionEntries;

/**
 * One project's role table: for each of the project's conversations, which conversation plays each role for it
 * (conversation {@code project1/chat-02}'s {@code "worker"} is {@code project1/chat-03}), so an agent loop can resolve
 * whom to {@code ask_chat} without a hard-coded id ({@code CollaborationGraph_260828_oo01}). The one who plays a role
 * may be in another project; the edge belongs to the project of its source, which is the one that looks it up.
 *
 * <p>The table records itself ({@code EachActorRecordsItsOwnState_261118_oo01}): every change writes the whole table
 * as the project's {@code roles} entry, and {@link #restore} reads the last one back at start-up. A log written before
 * the table existed kept the edges in the project's {@code settings} entry, next to the name and the organization;
 * that is read when there is no {@code roles} entry, and written straight away as the table's own.</p>
 *
 * <p>A plain object. The actor system holds one per project as the project's child {@code <project>/roles}, and the
 * rest of the application reaches it only by {@code tell}/{@code ask} on that actor. Ordinary mutable state: a role
 * reassigned in the middle of the work is what the next lookup finds.</p>
 */
public class RoleTable {

    /** Label of the project's entry that holds its role edges. */
    public static final String ROLES_LABEL = "roles";

    /**
     * One role edge: {@code from}'s collaborator for {@code role} is {@code to}. Both are qualified names
     * ({@code "project1/chat-02"}).
     */
    public record Edge(String from, String role, String to, String node) {
        /** An edge to a conversation of this instance. */
        public Edge(String from, String role, String to) { this(from, role, to, null); }
    }

    /**
     * Who plays a role: a conversation's qualified name, and the chat-ui instance it is on when that is another one.
     *
     * @param name the qualified name, as that instance names it
     * @param node the other instance's node name, or {@code null} for a conversation of this instance
     */
    public record Collaborator(String name, String node) {}

    private final String prefix;
    private final SessionEntries entries;
    /** Source, then role, then who plays it; sorted, so {@link #edges} and the entry come in a fixed order. */
    private final Map<String, Map<String, Collaborator>> bySource = new TreeMap<>();

    /**
     * @param projectId the project whose conversations are the sources
     * @param entries   the project's own session
     */
    public RoleTable(String projectId, SessionEntries entries) {
        this.prefix = projectId + "/";
        this.entries = entries;
    }

    /**
     * Makes {@code to} {@code from}'s collaborator for {@code role}, replacing whoever played it, and records the table.
     *
     * @return {@code null} when set, or why it was refused with nothing changed
     */
    public String set(String from, String role, String to) {
        return set(from, role, to, null);
    }

    /**
     * As {@link #set(String, String, String)}, with the role played by a conversation on another chat-ui instance.
     *
     * @param node that instance's node name, as {@code ask_chat} takes it, or {@code null} for this instance
     */
    public String set(String from, String role, String to, String node) {
        if (!from.startsWith(prefix)) return from + " is not a conversation of " + prefix.substring(0, prefix.length() - 1);
        bySource.computeIfAbsent(from, k -> new TreeMap<>()).put(role,
                new Collaborator(to, node == null || node.isBlank() ? null : node.strip()));
        record();
        return null;
    }

    /** @return the qualified name of who plays {@code role} for {@code from}, or {@code null} when nobody does */
    public String collaborator(String from, String role) {
        Collaborator c = collaboratorAt(from, role);
        return c == null ? null : c.name();
    }

    /** @return who plays {@code role} for {@code from}, with the instance it is on, or {@code null} when nobody does */
    public Collaborator collaboratorAt(String from, String role) {
        Map<String, Collaborator> roles = bySource.get(from);
        return roles == null ? null : roles.get(role);
    }

    /** @return {@code from}'s roles and the qualified name of who plays each, empty when it has none */
    public Map<String, String> collaboratorsOf(String from) {
        Map<String, Collaborator> roles = bySource.get(from);
        if (roles == null) return Map.of();
        Map<String, String> out = new TreeMap<>();
        roles.forEach((role, c) -> out.put(role, c.name()));
        return Map.copyOf(out);
    }

    /** @return every edge, sorted by source and then by role */
    public List<Edge> edges() {
        List<Edge> out = new ArrayList<>();
        bySource.forEach((from, roles) -> roles.forEach((role, c) -> out.add(new Edge(from, role, c.name(), c.node()))));
        return out;
    }

    /**
     * Forgets a deleted conversation: its edges, and every edge that names it. Recorded when anything went.
     *
     * @param name the conversation's qualified name, e.g. {@code "project1/chat-03"}
     * @return whether an edge went
     */
    public boolean forgetConversation(String name) {
        return forgetAndRecord(name::equals);
    }

    /**
     * Forgets the conversations of a deleted project: every edge that names one of them. Recorded when anything went.
     *
     * @param projectPrefix the deleted project's prefix, e.g. {@code "project2/"}
     * @return whether an edge went
     */
    public boolean forgetProject(String projectPrefix) {
        return forgetAndRecord(name -> name.startsWith(projectPrefix));
    }

    /**
     * Puts back the edges last recorded, at start-up once every project's conversations exist. An edge whose source or
     * target did not come back would name nobody, so it is dropped, and the drop recorded. What was read from the
     * earlier {@code settings} entry is recorded as the table's own entry, so that form is read only once.
     *
     * @param existing the qualified names of the conversations of this instance
     */
    public void restore(Set<String> existing) {
        boolean earlierForm = false;
        org.json.JSONObject saved = entries.latestWithKey(ROLES_LABEL, ROLES_LABEL);
        if (saved == null) {
            saved = entries.latestWithKey(IoLogView.SETTINGS_LABEL, ROLES_LABEL);
            earlierForm = saved != null;
        }
        if (saved == null) return;
        bySource.clear();
        org.json.JSONArray edges = saved.optJSONArray(ROLES_LABEL);
        for (int i = 0; edges != null && i < edges.length(); i++) {
            org.json.JSONObject e = edges.optJSONObject(i);
            if (e == null) continue;
            String from = e.optString("from", null), role = e.optString("role", null), to = e.optString("to", null);
            String node = e.isNull("node") ? null : e.optString("node", null);
            if (from != null && role != null && to != null && from.startsWith(prefix)) {
                bySource.computeIfAbsent(from, k -> new TreeMap<>()).put(role, new Collaborator(to, node));
            }
        }
        boolean dropped = forget(name -> !existing.contains(name));
        if (earlierForm || dropped) record();
    }

    private boolean forgetAndRecord(Predicate<String> gone) {
        boolean changed = forget(gone);
        if (changed) record();
        return changed;
    }

    /**
     * Forgets the edges from a conversation of this project {@code gone} names, and the edges to a conversation of this
     * instance it names. An edge to another instance's conversation stays: a name here says nothing about that one, and
     * whether it still exists cannot be told from here; using it says so ("conversation not found on node").
     */
    private boolean forget(Predicate<String> gone) {
        boolean changed = false;
        for (Iterator<Map.Entry<String, Map<String, Collaborator>>> it = bySource.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Map<String, Collaborator>> source = it.next();
            if (gone.test(source.getKey())) {
                it.remove();
                changed = true;
                continue;
            }
            if (source.getValue().values().removeIf(c -> c.node() == null && gone.test(c.name()))) changed = true;
            if (source.getValue().isEmpty()) it.remove();
        }
        return changed;
    }

    /** Writes the whole table as one entry, in the shape the earlier settings entry held it, under a key of the same name. */
    private void record() {
        org.json.JSONArray edges = new org.json.JSONArray();
        for (Edge e : edges()) {
            org.json.JSONObject o = new org.json.JSONObject().put("from", e.from()).put("role", e.role()).put("to", e.to());
            if (e.node() != null) o.put("node", e.node());
            edges.put(o);
        }
        entries.write(ROLES_LABEL, new org.json.JSONObject().put(ROLES_LABEL, edges).toString());
    }
}
