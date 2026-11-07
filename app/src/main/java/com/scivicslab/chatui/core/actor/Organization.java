package com.scivicslab.chatui.core.actor;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The reporting lines of one project's conversations: who each conversation's superior is, which
 * conversation is the liaison that talks with the person, and how many subordinates one superior may
 * have ({@code ConversationReportingLines_261108_oo01}). One instance per project, held by the actor
 * {@code <project>/organization}.
 *
 * <p>Conversations are named by their bare id ({@code "02"}). The table holds only superiors; a
 * conversation's subordinates are read back from it, so the two can never disagree. A conversation
 * with no superior is not in the table.</p>
 *
 * <p>Every change either succeeds and returns {@code null}, or is refused with the reason and leaves the
 * organization as it was.</p>
 */
public class Organization {

    /** The liaison when none has been chosen: the conversation every project starts with. */
    public static final String DEFAULT_LIAISON = "01";
    /** The subordinates one superior may have when no other limit has been set. */
    public static final int DEFAULT_MAX_SUBORDINATES = 5;

    private final Map<String, String> superiors = new LinkedHashMap<>();
    private String liaison = DEFAULT_LIAISON;
    private int maxSubordinates = DEFAULT_MAX_SUBORDINATES;

    /** @return the conversation's superior, or {@code null} when it has none */
    public String superiorOf(String chatId) {
        return superiors.get(chatId);
    }

    /** @return the conversation's subordinates, in id order */
    public List<String> subordinatesOf(String chatId) {
        List<String> out = new ArrayList<>();
        superiors.forEach((sub, sup) -> { if (sup.equals(chatId)) out.add(sub); });
        out.sort(null);
        return out;
    }

    /** @return whether {@code superior} is {@code chatId}'s superior, directly */
    public boolean isSuperiorOf(String superior, String chatId) {
        return superior != null && superior.equals(superiors.get(chatId));
    }

    public String liaison() { return liaison; }

    public int maxSubordinates() { return maxSubordinates; }

    /**
     * Makes {@code superior} the superior of {@code chatId}, or, with a blank {@code superior}, leaves
     * {@code chatId} with none.
     *
     * @return {@code null} when done; otherwise why not, with nothing changed: the liaison cannot have a
     *         superior, a conversation cannot be its own superior or have one of its own subordinates
     *         (at any depth) above it, and a superior already holding the limit takes no more, in which
     *         case the reason names the subordinates that still have room
     */
    public String setSuperior(String chatId, String superior) {
        if (superior == null || superior.isBlank()) {
            superiors.remove(chatId);
            return null;
        }
        if (chatId.equals(liaison)) return "chat-" + chatId + " is the liaison, which has no superior";
        if (chatId.equals(superior)) return "chat-" + chatId + " cannot be its own superior";
        for (String up = superior; up != null; up = superiors.get(up)) {
            if (up.equals(chatId)) {
                return "chat-" + superior + " reports to chat-" + chatId + ", so it cannot be its superior";
            }
        }
        if (superior.equals(superiors.get(chatId))) return null;
        String full = roomFor(superior);
        if (full != null) return full;
        superiors.put(chatId, superior);
        return null;
    }

    /**
     * @return {@code null} when {@code superior} can take one more subordinate; otherwise the refusal,
     *         naming the subordinates that still have room, which can take the new conversation instead
     */
    public String roomFor(String superior) {
        List<String> subs = subordinatesOf(superior);
        if (subs.size() < maxSubordinates) return null;
        List<String> withRoom = subs.stream().filter(s -> subordinatesOf(s).size() < maxSubordinates)
                .map(s -> "chat-" + s).toList();
        return "chat-" + superior + " already has " + subs.size() + " subordinates, the limit; "
                + (withRoom.isEmpty() ? "none of them has room either"
                        : "make one of its subordinates the superior instead: " + String.join(", ", withRoom));
    }

    /**
     * Makes {@code chatId} the liaison. It loses its superior, since the liaison has none.
     *
     * @return {@code null} when done, or why not
     */
    public String setLiaison(String chatId) {
        if (chatId == null || chatId.isBlank()) return "a liaison is required";
        liaison = chatId;
        superiors.remove(chatId);
        return null;
    }

    /** @return {@code null} when done, or why not */
    public String setMaxSubordinates(int max) {
        if (max < 1) return "the limit is at least 1";
        maxSubordinates = max;
        return null;
    }

    /** Forgets a conversation that was deleted. One with subordinates is not deleted, so none point to it. */
    public void remove(String chatId) {
        superiors.remove(chatId);
    }

    /** Forgets every conversation not in {@code existing}, as after a restart where some did not come back. */
    public void retainOnly(Collection<String> existing) {
        superiors.entrySet().removeIf(e -> !existing.contains(e.getKey()) || !existing.contains(e.getValue()));
    }

    /**
     * The paragraph a conversation reads at the start of each turn about its place here: who it is, its
     * superior, its subordinates, which conversation is the liaison, and what to do with what it cannot
     * decide.
     *
     * @param label gives a conversation's id with its name, e.g. {@code chat-02 "platoon 1 leader"}
     * @return the paragraph, or {@code null} when the conversation has no superior, no subordinates and is
     *         not the liaison, and so has no place in the organization to be told about
     */
    public String positionText(String projectId, String chatId, Function<String, String> label) {
        String sup = superiorOf(chatId);
        List<String> subs = subordinatesOf(chatId);
        boolean isLiaison = chatId.equals(liaison);
        if (sup == null && subs.isEmpty() && !isLiaison) return null;
        StringBuilder sb = new StringBuilder("Your position in ").append(projectId).append(": you are ")
                .append(label.apply(chatId)).append(". ");
        if (isLiaison) {
            sb.append("You are the liaison: the only conversation that talks with the person. Put the questions "
                    + "your subordinates send up to the person, together or, when urgent, at once; when the "
                    + "person decides, send each decision to the subordinate that asked, with ask_chat, as a "
                    + "message of its own. ");
        } else if (sup != null) {
            sb.append("Your superior is ").append(label.apply(sup)).append(". ");
        }
        if (!subs.isEmpty()) {
            sb.append("Your subordinates are ").append(String.join(", ", subs.stream().map(label).toList())).append(". ");
        }
        if (!isLiaison) {
            sb.append(label.apply(liaison)).append(" is the liaison, the only conversation that talks with the person. ");
        }
        sb.append("Decide what you can within the work you were given");
        if (sup != null) {
            sb.append("; ask your superior with ask_chat only what you cannot decide, then go on with the work "
                    + "that does not depend on the answer. A decision your superior sends later comes as a "
                    + "message of its own; apply it when it arrives");
        }
        return sb.append('.').toString();
    }

    /** @return the organization as the JSON object the project's settings entry carries */
    public JSONObject toJson() {
        return new JSONObject()
                .put("superiors", new JSONObject(superiors))
                .put("liaison", liaison)
                .put("maxSubordinates", maxSubordinates);
    }

    /** Puts this organization on the one a settings entry carried; a missing field keeps its default. */
    public void loadJson(JSONObject o) {
        superiors.clear();
        JSONObject sups = o.optJSONObject("superiors");
        if (sups != null) for (String k : sups.keySet()) superiors.put(k, sups.getString(k));
        liaison = o.optString("liaison", DEFAULT_LIAISON);
        maxSubordinates = Math.max(1, o.optInt("maxSubordinates", DEFAULT_MAX_SUBORDINATES));
    }
}
