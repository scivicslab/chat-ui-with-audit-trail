package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.ToolParameter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a harness tool is defined: its name, what it does, its arguments with their JSON Schema
 * types, and whether it drives the conversation ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>Two things are made from a definition. The {@code <invoke>} path (the claude and codex providers)
 * shows the model {@link Spec#invokeDescription()}; chat-ui's MCP server and the tool-calling path show
 * {@link Spec#inputSchema()} with {@link Spec#description()}. A tool that drives the conversation changes
 * the running turn's state (request ids, open requests, the turn-ending question), so it is run only by
 * the conversation's own agent loop and never offered through the MCP server.</p>
 *
 * <p>This class is part of agent-connector-kernel. It holds {@link Spec}, the helpers that render it, and the
 * definitions of the tools agent-connector-kernel runs itself ({@link #coreTools()}). The full list a
 * conversation is shown, including the tools that operate ConversationSquad actors, is the app's
 * {@code ConversationToolCatalog}, which places these core definitions where they always stood.</p>
 */
public final class ToolCatalog {

    /**
     * One tool's definition.
     *
     * @param name                the name the model calls it by
     * @param description         what it does, in words that do not depend on how the call is written
     * @param parameters          its arguments, in signature order
     * @param drivesConversation  whether it changes the running turn's state
     */
    public record Spec(String name, String description, List<ToolParameter> parameters, boolean drivesConversation) {

        /** @return the argument names in signature order */
        public List<String> signature() {
            return parameters.stream().map(ToolParameter::name).toList();
        }

        /** @return the names of the arguments the tool cannot run without, in signature order */
        public List<String> required() {
            return parameters.stream().filter(ToolParameter::required).map(ToolParameter::name).toList();
        }

        /**
         * The entry the {@code <invoke>} path lists under "Available tools": {@code - name(args): }, the
         * description, and the sentence naming the required parameter tags, wrapped and ended by a newline.
         *
         * @return the entry text
         */
        public String invokeDescription() {
            String text = "- " + name + "(" + String.join(", ", signature()) + "): " + description
                    + requiredSentence(required());
            return wrap(text, 100) + "\n";
        }

        /** @return the arguments as a JSON Schema object ({@code type}, {@code properties}, {@code required}) */
        public JSONObject inputSchema() {
            JSONObject properties = new JSONObject();
            for (ToolParameter p : parameters) {
                JSONObject prop = new JSONObject().put("type", p.type());
                if (!p.description().isEmpty()) prop.put("description", p.description());
                properties.put(p.name(), prop);
            }
            return new JSONObject().put("type", "object").put("properties", properties)
                    .put("required", new JSONArray(required()));
        }
    }

    private static final Map<String, Spec> CORE = core();

    private ToolCatalog() {
    }

    /** @return the tools agent-connector-kernel runs itself, keyed by name */
    public static Map<String, Spec> coreTools() {
        return CORE;
    }

    /** @return the core tool {@code name}, or {@code null} when there is none */
    public static Spec core(String name) {
        return CORE.get(name);
    }

    /**
     * Makes one definition. Line breaks and the spaces around them in {@code description} become one space, so
     * a definition can be written as a text block.
     *
     * @return the definition
     */
    public static Spec define(String name, String description, List<ToolParameter> parameters,
                              boolean drivesConversation) {
        return new Spec(name, description.strip().replaceAll("\\s*\\n\\s*", " "), parameters, drivesConversation);
    }

    /**
     * @param tool a plugin tool
     * @return its definition: the description after its {@code - name(args): } head, and its parameters
     */
    public static Spec ofPlugin(ConversationTool tool) {
        String text = tool.description().strip();
        int colon = text.indexOf("):");
        String description = colon >= 0 ? text.substring(colon + 2).strip() : text;
        description = description.replaceAll("\\s*\\n\\s*", " ");
        return new Spec(tool.name(), description, tool.parameters(), false);
    }

    /**
     * @param required the required arguments, in signature order
     * @return the sentence telling the model which parameter tags a call must carry, with a leading space;
     *         empty when none is required
     */
    static String requiredSentence(List<String> required) {
        if (required.isEmpty()) return "";
        List<String> quoted = required.stream().map(a -> "\"" + a + "\"").toList();
        if (quoted.size() == 1) return " Requires a " + quoted.get(0) + " <parameter> tag.";
        String list = String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.get(quoted.size() - 1);
        return " Requires " + list + " <parameter> tags.";
    }

    /** Wraps {@code text} at word boundaries to at most {@code width} characters, indenting continuations by two. */
    static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.append(line).append('\n');
                line.setLength(0);
                line.append("  ");
            }
            if (line.length() > 0 && !line.toString().equals("  ")) line.append(' ');
            line.append(word);
        }
        return out.append(line).toString();
    }

    private static void add(Map<String, Spec> m, String name, String description, List<ToolParameter> parameters,
                            boolean drivesConversation) {
        m.put(name, define(name, description, parameters, drivesConversation));
    }

    private static Map<String, Spec> core() {
        Map<String, Spec> m = new LinkedHashMap<>();
        add(m, "read", """
                read a file, or a whole directory recursively, under the working directory. To read several
                files at once, put one path per line in the same "path" parameter — use that instead of reading
                a parent directory when you want three specific files, since the parent brings everything else
                under it along too. A read that would return more than 4,000,000 characters or 1,000 files stops
                early and says where it stopped, listing the paths it did not reach so you can ask for them
                separately.
                """,
                List.of(
                    new ToolParameter("path", "string", true, "")),
                false);
        add(m, "calc", """
                evaluate a Java arithmetic expression, e.g. 23*47 or Math.sqrt(16).
                """,
                List.of(
                    new ToolParameter("expression", "string", true, "")),
                false);
        add(m, "search_docs", """
                search this team's internal documentation. It returns a ranked list of CANDIDATE documents —
                title, id, source path and a short summary each — not the answer to your question. A summary
                says what a document is about, not what it says, so to actually answer you must then call read
                on the "path" of the candidates that look relevant, and read more than one when the summaries do
                not settle which is right. Answering straight from a summary, or from a single top-ranked
                candidate you did not open, is how you get the answer wrong. Three unrelated searches — over
                words, over term frequencies, over meaning — always run, and their results are merged; each
                candidate says which of them found it, and one found by more than one is the strongest signal in
                the list. Search in the language the documents are written in: these are mostly Japanese. "node"
                is optional: leave it out to search the default documentation portal, or give the name of
                another html-saurus node to search that one instead.
                """,
                List.of(
                    new ToolParameter("query", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                false);
        add(m, "list_references", """
                follow the reference links an author declared between documents. Pass the "id" a search_docs
                candidate printed. Direction "forward" (the default) returns the documents that one refers to;
                "backward" returns the documents that refer to it. Each edge has a kind, written by the author —
                the kinds are not a fixed list, so read the kind that comes back rather than expecting a
                particular word; pass "relation" to keep only one kind, or omit it for every kind. This is not a
                search: the relation is written in the document's own source, so it is the way to reach a
                document whose wording never matches the question — the value you need may be stated only in a
                document its author told you to read first. It returns the same kind of candidate list
                search_docs does, so call read on a path to see what a document actually says. "backward" stops
                at 30 documents and says so when it did; a standard that many documents declare as their
                prerequisite has that many edges. "node" is optional and means what it means for search_docs.
                """,
                List.of(
                    new ToolParameter("id", "string", true, ""),
                    new ToolParameter("direction", "string", false, ""),
                    new ToolParameter("relation", "string", false, ""),
                    new ToolParameter("node", "string", false, "")),
                false);
        add(m, "write", """
                save text to a file under the working directory. Missing parent directories are created, so you
                can write straight to a new subdirectory without making it first. An existing file is
                OVERWRITTEN, not appended to — read it first if you meant to add to it. The reply gives the
                absolute path written and how many characters went into it.
                """,
                List.of(
                    new ToolParameter("path", "string", true, ""),
                    new ToolParameter("content", "string", false, "")),
                false);
        add(m, "remove", """
                take one file away from the working directory. It is MOVED to .trash/<timestamp>/ keeping the
                path it had, not deleted, so a wrong one can be put back and nothing is lost. The reply gives
                both paths. One file per call: a directory is refused, so clear a tree one file at a time or
                leave it to a person. Emptying the trash is a person's job — do not try to.
                """,
                List.of(
                    new ToolParameter("path", "string", true, "")),
                false);
        return Collections.unmodifiableMap(m);
    }
}
