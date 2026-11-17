package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One project's conversations and the two kinds of edge between them, as the left pane draws them
 * ({@code ConversationGraphInTheLeftPane_261117_oo01}): superior to subordinate, from the project's
 * {@link Organization}, and role edges, from the {@link CollaborationGraph}. Built on each request from what
 * those two actors hold; nothing is kept.
 *
 * @param projectId     the project
 * @param liaison       the project's liaison, as the organization names it
 * @param nodes         the project's conversations, in id order
 * @param superiorEdges superior to subordinate, between conversations of the project, in subordinate order
 * @param roleEdges     role edges whose source is a conversation of the project, by source and role
 */
public record ConversationGraph(String projectId, String liaison, List<Node> nodes,
                                List<SuperiorEdge> superiorEdges, List<RoleEdge> roleEdges) {

    /**
     * @param chatId  the bare id, e.g. {@code "02"}
     * @param name    the name a person gave it, or {@code null}
     * @param liaison whether it is the project's liaison
     */
    public record Node(String chatId, String name, boolean liaison) {}

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
     */
    public static ConversationGraph of(String projectId, List<String> chatIds, Map<String, String> names,
                                       Map<String, String> superiors, String liaison,
                                       List<CollaborationGraph.Edge> edges) {
        Set<String> ids = new TreeSet<>(chatIds);
        List<Node> nodes = new ArrayList<>();
        for (String id : ids) nodes.add(new Node(id, names.get(id), id.equals(liaison)));

        List<SuperiorEdge> sup = new ArrayList<>();
        for (String id : ids) {
            String s = superiors.get(id);
            if (s != null && ids.contains(s)) sup.add(new SuperiorEdge(s, id));
        }

        List<RoleEdge> roles = new ArrayList<>();
        for (CollaborationGraph.Edge e : edges) {
            String[] from = split(e.from());
            String[] to = split(e.to());
            if (from == null || to == null || !projectId.equals(from[0]) || !ids.contains(from[1])) continue;
            roles.add(new RoleEdge(from[1], e.role(), to[1], to[0]));
        }
        roles.sort((a, b) -> a.from().equals(b.from()) ? a.role().compareTo(b.role()) : a.from().compareTo(b.from()));
        return new ConversationGraph(projectId, liaison, nodes, sup, roles);
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
