package com.scivicslab.chatui.core.actor;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds each conversation tab's role -> collaborator-tab-id assignments (e.g. tab "02" mapping
 * role "worker" to tab "03"), so workflow logic (babysitter loops etc.) can resolve who to
 * {@code ask_chat} without hardcoding a raw chat id. One instance for the whole actor system,
 * reached via {@link com.scivicslab.pojoactor.core.ActorRef#ask}/{@code tell} like any other
 * POJO-actor.
 *
 * <p>Ordinary mutable actor state, rewritable at any time by any tab that calls
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
     * @param fromChatId the tab whose role table is being set
     * @param role       a free-form role name (e.g. "worker"), meaningful only to the workflow
     *                   that uses it
     * @param toChatId   the tab assigned to that role
     */
    public void setCollaborator(String fromChatId, String role, String toChatId) {
        edges.computeIfAbsent(fromChatId, k -> new HashMap<>()).put(role, toChatId);
    }

    /**
     * @param fromChatId the tab whose role table to consult
     * @param role       the role name to look up
     * @return the assigned collaborator's tab id, or {@code null} if none is set
     */
    public String getCollaborator(String fromChatId, String role) {
        Map<String, String> byRole = edges.get(fromChatId);
        return byRole == null ? null : byRole.get(role);
    }

    /**
     * @param fromChatId the tab whose role table to return
     * @return an unmodifiable view of {@code fromChatId}'s role -> collaborator-tab-id table
     *         (empty if none set)
     */
    public Map<String, String> getCollaborators(String fromChatId) {
        return edges.getOrDefault(fromChatId, Map.of());
    }
}
