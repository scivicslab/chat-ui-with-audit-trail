package com.scivicslab.chatui.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The text {@code list_running_tools} answers, with the chat-ui node names {@code ask_chat}'s {@code node} takes
 * ({@code ListRunningToolsShowsNodeNames_261119_oo01}). Touches neither the remote path nor HTTP: it is given the
 * AI-workspace's rows and the names of the nodes this instance knows.
 *
 * <p>A row on HTTP port {@code P} is shown with {@code node chat-ui-P} only when {@code chat-ui-P} is a node this
 * instance knows: its own name or one of the others. The AI-workspace's rows carry no node name, and a tile may set
 * its own {@code chat-ui.node-name}, so the name from the naming rule is shown only where it is checked. The known
 * nodes no row was matched to come on one more line, so a node with a name of its own is shown without a port
 * rather than hidden.</p>
 */
public final class NodeNameListing {

    /** One tool instance, as the AI-workspace's {@code listInstances} answers it. */
    public record Row(String name, int port, String state, String url, String title) {}

    /** The node name the AI-workspace gives a chat-ui it launches on HTTP port {@code P}: this prefix and {@code P}. */
    static final String CHAT_UI_PREFIX = "chat-ui-";

    /** What is put after this instance's own name. */
    static final String THIS_INSTANCE = " (this instance)";

    private NodeNameListing() {}

    /**
     * @param workspace the AI-workspace's node name
     * @param tool      the tool the rows were asked for; blank for all
     * @param rows      the AI-workspace's rows
     * @param me        this instance's node name
     * @param others    the other chat-ui nodes this instance knows
     * @return the rows, each with its node name where it is a known node, then the known nodes no row was matched to
     */
    public static String withRows(String workspace, String tool, List<Row> rows, String me, List<String> others) {
        Set<String> known = known(me, others);
        Set<String> unmatched = new LinkedHashSet<>(known);
        String of = tool == null || tool.isBlank() ? "" : " of " + tool.strip();
        StringBuilder sb = new StringBuilder();
        if (rows.isEmpty()) {
            sb.append("No tool instances").append(of).append(" are running in the AI-workspace ").append(workspace).append(".\n");
        } else {
            sb.append(rows.size()).append(rows.size() == 1 ? " tool instance" : " tool instances").append(of)
              .append(" in the AI-workspace ").append(workspace).append(", in any state:\n");
        }
        for (Row r : rows) {
            sb.append("- ").append(r.name()).append("  port ").append(r.port()).append("  ").append(r.state());
            String node = CHAT_UI_PREFIX + r.port();
            if (known.contains(node)) {
                sb.append("  node ").append(marked(node, me));
                unmatched.remove(node);
            }
            if (r.url() != null && !r.url().isBlank()) sb.append("  ").append(r.url());
            if (r.title() != null && !r.title().isBlank()) sb.append("  \"").append(r.title()).append("\"");
            sb.append("\n");
        }
        if (!unmatched.isEmpty()) {
            sb.append("other nodes this instance knows: ")
              .append(unmatched.stream().map(n -> marked(n, me)).toList()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * @param why    why there is no AI-workspace list, e.g. "this chat-ui instance knows no AI-workspace node"
     * @param me     this instance's node name
     * @param others the other chat-ui nodes this instance knows
     * @return the known node names alone, this instance's first
     */
    public static String namesOnly(String why, String me, List<String> others) {
        StringBuilder sb = new StringBuilder("There is no AI-workspace list of running tools here: ").append(why)
                .append(". The chat-ui nodes this instance knows, by name only:\n");
        for (String n : known(me, others)) sb.append("- ").append(marked(n, me)).append("\n");
        return sb.toString().stripTrailing();
    }

    private static Set<String> known(String me, List<String> others) {
        Set<String> known = new LinkedHashSet<>();
        if (me != null && !me.isBlank()) known.add(me);
        known.addAll(others == null ? List.of() : new ArrayList<>(others));
        return known;
    }

    private static String marked(String node, String me) {
        return node.equals(me) ? node + THIS_INSTANCE : node;
    }
}
