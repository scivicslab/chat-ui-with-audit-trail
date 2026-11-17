package com.scivicslab.chatui.core.actor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Holds each ConversationSquad's role -> collaborator ConversationSquad id assignments (e.g. ConversationSquad "02" mapping
 * role "worker" to ConversationSquad "03"), so workflow logic (babysitter loops etc.) can resolve who to
 * {@code ask_chat} without hardcoding a raw chat id. One instance for the whole actor system,
 * reached via {@link com.scivicslab.pojoactor.core.ActorRef#ask}/{@code tell} like any other
 * POJO-actor.
 *
 * <p>Ordinary mutable actor state, rewritable at any time by any ConversationSquad that calls
 * {@code set_collaborator} — not a fixed configuration snapshot ({@code
 * CollaborationGraph_260828_oo01} "CallWatchdogとの違い"). A role assignment made mid-flow takes
 * effect on the next lookup; callers that resolve a role once per {@code ask_chat} call (rather
 * than caching it across calls) automatically follow any later reassignment.</p>
 */
public class CollaborationGraph {

    private final Map<String, Map<String, String>> edges = new HashMap<>();

    /**
     * Assigns {@code toChatId} as {@code fromChatId}'s collaborator for the given role,
     * overwriting any previous assignment.
     *
     * @param fromChatId the ConversationSquad whose role table is being set
     * @param role       a free-form role name (e.g. "worker"), meaningful only to the workflow
     *                   that uses it
     * @param toChatId   the ConversationSquad assigned to that role
     */
    public void setCollaborator(String fromChatId, String role, String toChatId) {
        edges.computeIfAbsent(fromChatId, k -> new HashMap<>()).put(role, toChatId);
    }

    /**
     * @param fromChatId the ConversationSquad whose role table to consult
     * @param role       the role name to look up
     * @return the assigned collaborator's ConversationSquad id, or {@code null} if none is set
     */
    public String getCollaborator(String fromChatId, String role) {
        Map<String, String> byRole = edges.get(fromChatId);
        return byRole == null ? null : byRole.get(role);
    }

    /**
     * @param fromChatId the ConversationSquad whose role table to return
     * @return an unmodifiable view of {@code fromChatId}'s role -> collaborator ConversationSquad id table
     *         (empty if none set)
     */
    public Map<String, String> getCollaborators(String fromChatId) {
        return edges.getOrDefault(fromChatId, Map.of());
    }

    /**
     * Puts back the assignments saved for one project, replacing whatever is held for sources under
     * {@code prefix}. Called when the project's settings are read at start-up, before any turn runs.
     *
     * @param prefix the project's prefix, e.g. {@code "project1/"}
     * @param saved  the assignments as {@link #edgesFrom} gave them when they were saved
     */
    public void replaceEdgesFrom(String prefix, List<Edge> saved) {
        edges.keySet().removeIf(from -> from.startsWith(prefix));
        for (Edge e : saved) {
            if (e.from().startsWith(prefix)) setCollaborator(e.from(), e.role(), e.to());
        }
    }

    /**
     * Forgets a deleted conversation: its own assignments and every assignment that names it.
     *
     * @param qualifiedName e.g. {@code "project1/chat-03"}
     * @return the projects whose sources lost an assignment, to be saved again
     */
    public Set<String> forgetConversation(String qualifiedName) {
        return forget(qualifiedName::equals);
    }

    /**
     * Forgets a deleted project: the assignments of its conversations and every assignment that names one of them.
     *
     * @param prefix the project's prefix, e.g. {@code "project2/"}
     * @return the projects whose sources lost an assignment, the deleted one included
     */
    public Set<String> forgetProject(String prefix) {
        return forget(name -> name.startsWith(prefix));
    }

    /**
     * Forgets every assignment whose source or target is not among {@code existing}: after a restart, the
     * conversations that did not come back.
     *
     * @param existing the qualified names of the conversations that exist now
     * @return the projects whose sources lost an assignment
     */
    public Set<String> forgetMissing(Set<String> existing) {
        return forget(name -> !existing.contains(name));
    }

    private Set<String> forget(Predicate<String> gone) {
        Set<String> touched = new TreeSet<>();
        for (Iterator<Map.Entry<String, Map<String, String>>> it = edges.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Map<String, String>> entry = it.next();
            String from = entry.getKey();
            boolean changed;
            if (gone.test(from)) {
                it.remove();
                changed = true;
            } else {
                changed = entry.getValue().values().removeIf(gone);
                if (entry.getValue().isEmpty()) it.remove();
            }
            if (changed) touched.add(from.contains("/") ? from.substring(0, from.indexOf('/')) : from);
        }
        return touched;
    }

    /**
     * One role assignment: {@code from}'s collaborator for {@code role} is {@code to}. Both are qualified names
     * ({@code "project1/chat-02"}).
     */
    public record Edge(String from, String role, String to) {}

    /**
     * Every role assignment whose source name starts with {@code prefix}, for the left pane's graph
     * ({@code ConversationGraphInTheLeftPane_261117_oo01}). Pass {@code "project1/"} for one project.
     *
     * @return the assignments, sorted by source and then by role
     */
    public List<Edge> edgesFrom(String prefix) {
        List<Edge> out = new ArrayList<>();
        edges.forEach((from, byRole) -> {
            if (from.startsWith(prefix)) byRole.forEach((role, to) -> out.add(new Edge(from, role, to)));
        });
        out.sort((a, b) -> a.from().equals(b.from()) ? a.role().compareTo(b.role()) : a.from().compareTo(b.from()));
        return out;
    }
}
