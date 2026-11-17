package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One project's conversations and the two kinds of edge between them, as the left pane draws them
 * ({@code ConversationGraphInTheLeftPane_261117_oo01}): superior to subordinate, from the project's
 * {@link Organization}, and role edges, from the project's {@link RoleTable}. Built on each request from what
 * those two actors hold; nothing is kept.
 *
 * @param projectId     the project
 * @param name          the name a person gave the project, or {@code null}
 * @param liaison       the project's liaison, as the organization names it
 * @param nodes         the project's conversations, in id order
 * @param superiorEdges superior to subordinate, between conversations of the project, in subordinate order
 * @param roleEdges     role edges whose source is a conversation of the project, by source and role
 */
public record ConversationGraph(String projectId, String name, String liaison, List<Node> nodes,
                                List<SuperiorEdge> superiorEdges, List<RoleEdge> roleEdges) {

    /**
     * @param chatId   the bare id, e.g. {@code "02"}
     * @param name     the name a person gave it, or {@code null}
     * @param liaison  whether it is the project's liaison
     * @param provider the provider as the screen's dropdown names it, e.g. {@code "Claude Code"}, or {@code null}
     *                 when it could not be read
     * @param model    the model the provider is on now, or {@code null} when none is settled yet
     */
    public record Node(String chatId, String name, boolean liaison, String provider, String model) {}

    /**
     * What a conversation runs on, read from it at the time of the call.
     *
     * @param provider the provider's label, as the screen's dropdown shows it
     * @param model    the model the provider is on, or {@code null}
     */
    public record Model(String provider, String model) {}

    /**
     * Every project of the instance, each as one {@link ConversationGraph}, for the left pane's single tree
     * ({@code ConversationGraphInTheLeftPane_261117_oo01}).
     *
     * @param projects the projects, in the order of their numbers ({@code project1}, {@code project2}, ...,
     *                 {@code project10})
     */
    public record All(List<ConversationGraph> projects) {}

    /** {@code from} is the superior of {@code to}; both are bare ids. */
    public record SuperiorEdge(String from, String to) {}

    /**
     * {@code from}'s collaborator for {@code role} is {@code to} of {@code toProject}.
     *
     * @param from      the source's bare id
     * @param role      the role name
     * @param to        the target's bare id
     * @param toProject the target's project, which may be another project
     */
    public record RoleEdge(String from, String role, String to, String toProject) {}

    /**
     * @param chatIds   the project's conversations, bare ids
     * @param names     bare id to the name a person gave it; ids without a name are absent
     * @param superiors bare id to its superior's bare id, as the organization's table holds them
     * @param liaison   the liaison's bare id
     * @param edges     the collaboration graph's edges; those not from a conversation of the project are dropped
     * @param models    bare id to what it runs on; an id without an entry gets {@code null} provider and model
     */
    public static ConversationGraph of(String projectId, String name, List<String> chatIds, Map<String, String> names,
                                       Map<String, String> superiors, String liaison,
                                       List<RoleTable.Edge> edges, Map<String, Model> models) {
        Set<String> ids = new TreeSet<>(chatIds);
        List<Node> nodes = new ArrayList<>();
        for (String id : ids) {
            Model m = models.get(id);
            nodes.add(new Node(id, names.get(id), id.equals(liaison), m == null ? null : m.provider(),
                    m == null ? null : m.model()));
        }

        List<SuperiorEdge> sup = new ArrayList<>();
        for (String id : ids) {
            String s = superiors.get(id);
            if (s != null && ids.contains(s)) sup.add(new SuperiorEdge(s, id));
        }

        List<RoleEdge> roles = new ArrayList<>();
        for (RoleTable.Edge e : edges) {
            String[] from = split(e.from());
            String[] to = split(e.to());
            if (from == null || to == null || !projectId.equals(from[0]) || !ids.contains(from[1])) continue;
            roles.add(new RoleEdge(from[1], e.role(), to[1], to[0]));
        }
        roles.sort((a, b) -> a.from().equals(b.from()) ? a.role().compareTo(b.role()) : a.from().compareTo(b.from()));
        return new ConversationGraph(projectId, name, liaison, nodes, sup, roles);
    }

    /** {@code "project1/chat-02"} to {@code ["project1", "02"]}, or {@code null} for any other form. */
    private static String[] split(String qualified) {
        if (qualified == null) return null;
        int slash = qualified.indexOf('/');
        String prefix = "chat-";
        if (slash <= 0 || !qualified.startsWith(prefix, slash + 1)) return null;
        return new String[] {qualified.substring(0, slash), qualified.substring(slash + 1 + prefix.length())};
    }
}
