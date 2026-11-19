package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.ToolParameter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one place a harness tool is defined: its name, what it does, its arguments with their JSON Schema
 * types, and whether it drives the conversation ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>Two things are made from a definition. The {@code <invoke>} path (the claude and codex providers)
 * shows the model {@link Spec#invokeDescription()}; chat-ui's MCP server and the tool-calling path show
 * {@link Spec#inputSchema()} with {@link Spec#description()}. A tool that drives the conversation changes
 * the running turn's state (request ids, open requests, the turn-ending question), so it is run only by
 * the conversation's own agent loop and never offered through the MCP server.</p>
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

    private static final Map<String, Spec> BUILT_IN = builtIn();

    private ToolCatalog() {
    }

    /** @return the built-in tools, keyed by name, in the order the model is shown them */
    public static Map<String, Spec> builtInTools() {
        return BUILT_IN;
    }

    /** @return the built-in tool {@code name}, or {@code null} when there is none */
    public static Spec builtIn(String name) {
        return BUILT_IN.get(name);
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
        m.put(name, new Spec(name, description.strip().replaceAll("\\s*\\n\\s*", " "), parameters, drivesConversation));
    }

    private static Map<String, Spec> builtIn() {
        Map<String, Spec> m = new LinkedHashMap<>();
        // First in the list: it is read before any tool a model might reach for instead of asking
        // (AskThePersonEndsTheTurn_261119_oo01). Never run by runTool: stepExpectingAction reads it.
        add(m, "ask_person", """
                ask the person a question and end your turn at once. Use it whenever you need a decision or an
                answer before you can go on. Write what the person needs to know before the call, in the same
                reply; put the question itself in "question". No other tool in that reply runs. In a
                conversation that is not the liaison, the question goes to whoever sent the request you are
                working on, or to your superior when no request did.
                """,
                List.of(
                    new ToolParameter("question", "string", true, "")),
                true);
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
        add(m, "list_running_tools", """
                list the tool instances the AI-workspace that runs this chat-ui is running — each one's name,
                port, state (READY, STARTING, ...), address and title — and, for a chat-ui instance, "node
                <name>": the node name ask_chat's "node" takes. Known nodes no row matched come on a last line;
                without an AI-workspace it lists the known node names alone. "tool" is optional: give a tool's
                name to list only its instances. It only reads the list; it cannot start or stop anything.
                """,
                List.of(
                    new ToolParameter("tool", "string", false, "")),
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
        add(m, "ask_chat", """
                send an instruction to another conversation (e.g. "02" for one in your own project, or
                "project2/02" to reach one in another project). Give "node" only to reach a conversation of
                another chat-ui instance: to find the node name of another instance, call list_running_tools
                first; then chatId names the conversation there. Leave "node" out for this instance. It does NOT
                wait: the result only says the instruction was sent, and under which request id, e.g.
                "project1/chat-01:s40:t5:r2" (your conversation, session, turn, and the number of this ask_chat
                in your turn). The reply reaches you only AFTER YOU END YOUR TURN: it comes as a new message
                starting with "[reply to request <request id>, from <the turn that wrote it>]" — match the id
                against the one you were given to tell which request it answers — followed by one line, [ended:
                answered; model calls: N; tool calls: M], saying how its turn ended: "answered", "step-limit"
                when it ran out of steps (then the reply is only a notice, not an answer), or "asked" when it
                ended with ask_person (then the reply is a question that waits for your decision: answer it with
                ask_chat, or ask_person yourself). If its turn left no reply, the message says so and why. So
                once you have sent your instructions, end your turn and wait; do not call get_chat_log or
                get_chat_status over and over to look for the reply, since it cannot arrive while your turn goes
                on. Several replies wait in order and come to you one per turn. Use this to direct or review
                another ConversationSquad's work; get_chat_log is for checking once whether the other
                conversation is stuck.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("prompt", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "ask_chat_urgent", """
                as ask_chat (including "node": to find the node name of another instance, call
                list_running_tools first), but the request goes into the other conversation's queue ahead of
                every waiting message that is not urgent, and its reply comes back to you the same way. It does
                not interrupt a turn the other conversation is running; it is answered next. Use it when someone
                asks you how the work you handed out is going: send each conversation you gave work to an urgent
                request asking, in one paragraph, how far it has got — saying it should answer only that and
                keep the work it is doing — then end your turn. When every answer is back, sum up how far each
                one has got.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("prompt", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "cancel_request", """
                take back a request you sent with ask_chat or ask_chat_urgent while it still waits in the other
                conversation's queue. "node" is optional: a request sent to another instance is found by its id;
                its result then arrives as a new message (to find the node name of another instance, call
                list_running_tools first). "requestId" is the id the ask_chat result gave, e.g.
                "project1/chat-01:s40:t5:r2". Only you, the sender, can cancel it. The result says what
                happened: "removed" (it is out of the queue and no reply will come, so do not wait for one),
                "already running" (the other conversation has started it and it is not stopped; send a follow-up
                request if the work is no longer wanted), "already answered" (nothing to take back), or "not
                found". Use it when the work is no longer wanted, or when you are about to send the same request
                again.
                """,
                List.of(
                    new ToolParameter("requestId", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "set_chat_timekeeper", """
                change another conversation of this project's timekeeper. urgentWaitMinutes is how long an
                urgent request may wait while that conversation runs a long turn before the turn is paused to
                answer it (default 10); childReplyWarnMinutes is how long a request it sent may go without a
                reply before it is told (default 5). Give at least one of the two, in whole minutes; one left
                out keeps its value.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("urgentWaitMinutes", "integer", false, ""),
                    new ToolParameter("childReplyWarnMinutes", "integer", false, "")),
                false);
        add(m, "accept_reply", """
                record that the reply to your request "requestId" is enough: the work is done, or you need
                nothing more from it. When a turn of yours begins with a reply ([reply to request …] or [follow-
                up to request …]), record one decision on it before you end the turn: accept_reply,
                expect_report, or ask_chat to the one that replied.
                """,
                List.of(
                    new ToolParameter("requestId", "string", false, "")),
                true);
        add(m, "expect_report", """
                when a reply says a report will come later (for example "I will report later" or "not finished
                yet, about 20 more minutes"), register it with that conversation's timekeeper. After "minutes"
                it is reminded once, and the answer it writes then comes to you as [follow-up to request …,
                reminder N]. If that says "not yet" again, register again. "minutes" is in whole minutes. Give
                "node" when the conversation that promised the report is on another chat-ui instance, as you
                gave it to ask_chat (to find the node name of another instance, call list_running_tools first).
                You do not register a reminder for a report you promise yourself: the end of your turn decides
                that.
                """,
                List.of(
                    new ToolParameter("chatId", "string", false, ""),
                    new ToolParameter("requestId", "string", true, ""),
                    new ToolParameter("minutes", "integer", true, ""),
                    new ToolParameter("node", "string", false, "")),
                true);
        add(m, "set_chat_name", """
                give a conversation of this project, your own included, the name a person reads beside its id,
                saying what it is for (for example "worker 1-3: Wikipedia articles A-F"). The id does not
                change, and the other tools still take the id. A blank name clears it; at most 80 characters.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("name", "string", false, "")),
                false);
        add(m, "set_agent_loop", """
                replace another ConversationSquad's agent loop — the Turing workflow that runs each of its turns
                — with the given YAML text (not a file path). Use this to author an agent loop for another
                ConversationSquad to run, then use ask_chat to start a turn under it.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("yaml", "string", true, "")),
                false);
        add(m, "load_skill", """
                read one of the skills listed after this tool list in full — its step-by-step instructions, the
                routes it says to call, its own files. Each skill's description says when it applies; when one
                applies to the task at hand, load it before you act rather than after.
                """,
                List.of(
                    new ToolParameter("name", "string", true, "")),
                false);
        add(m, "set_collaborator", """
                record that, for ConversationSquad "chatId", the ConversationSquad playing role "role" (e.g.
                "worker") is "collaboratorChatId". Give "node" only when the one playing the role is a
                conversation of another chat-ui instance, as ask_chat takes it: to find the node name of another
                instance, call list_running_tools first; then collaboratorChatId names the conversation there,
                and the agent loop sends its work there. An agent loop installed via set_agent_loop can then
                resolve that role instead of a hardcoded chat id, and you can reassign it again later by calling
                this a second time.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("role", "string", true, ""),
                    new ToolParameter("collaboratorChatId", "string", true, ""),
                    new ToolParameter("node", "string", false, "")),
                false);
        add(m, "get_chat_status", """
                report which model, provider and tool set another conversation is on, and whether it is busy
                right now. "chatId" is, for example, "02", or "project2/02" for another project. It does not
                wait for the conversation's turn, so it answers even while that conversation is running.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "list_chat_models", """
                list the models another conversation's provider offers, one per line as name (type, server). Use
                it to find the exact name to give set_chat_model.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "set_chat_model", """
                put another conversation of this project on a different model, for the jobs you hand it with
                ask_chat. The name must be one list_chat_models printed; any other is refused with the list. The
                change takes effect after that conversation's current turn. A conversation of another project is
                refused.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("model", "string", true, "")),
                false);
        add(m, "list_providers", """
                list the providers a conversation of this instance can be put on, one per line as value (label),
                e.g. "openai-compat (Local LLM)" or "claude (Claude Code)". Give the value to create_chat or
                set_chat_provider. Takes no parameters.
                """,
                List.of(),
                false);
        add(m, "create_chat", """
                add a new, empty conversation to your own project and return its id (for example "06"), which
                you can then pass as "chatId" to ask_chat, set_chat_model, get_request_messages and the other
                conversation tools. All parameters are optional: "provider" is a value list_providers printed
                (default: claude, Claude Code), and "model" is one of that provider's models (default: the
                provider's own default). A wrong provider or model is refused with the names you can use, and no
                conversation is left behind. Name openai-compat (a local LLM) only for workers that repeat the
                same kind of job many times (for example, processing Wikipedia articles one by one), where calls
                cost nothing and many run at once. Instead of provider and model you may give "role" (manager,
                worker or repeater): the role decides the provider, model, name and timekeeper limits, and a
                role given together with a provider or model is refused. Nothing else creates a conversation: a
                "chatId" that does not exist is an error. The new conversation reports to you; give the optional
                "superior" parameter another conversation's id to put it under that one instead, or "none" for
                no superior. A superior already holding the subordinate limit is refused, naming its
                subordinates that still have room: put the new one under one of them.
                """,
                List.of(
                    new ToolParameter("provider", "string", false, ""),
                    new ToolParameter("model", "string", false, ""),
                    new ToolParameter("superior", "string", false, ""),
                    new ToolParameter("role", "string", false, "")),
                false);
        add(m, "list_chats", """
                list the conversations of your project, one per line: id, name, superior, number of
                subordinates, provider and model, busy or idle. You and the liaison (the one conversation that
                talks with the person) are marked. Takes no parameters.
                """,
                List.of(),
                false);
        add(m, "set_superior", """
                make "superiorChatId" the superior of conversation "chatId" of your project, or, with "none",
                leave it with no superior. A conversation reports to its superior and sends it the questions it
                cannot decide. Refused, with nothing changed, when it would make a loop, when "chatId" is the
                liaison, and when the superior already has the subordinate limit.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("superiorChatId", "string", false, "")),
                false);
        add(m, "set_chat_provider", """
                put another conversation of this project on a different provider. "provider" is a value
                list_providers printed. Its model becomes that provider's default; call set_chat_model after it
                for another. The change takes effect after that conversation's current turn. A conversation of
                another project is refused.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("provider", "string", true, "")),
                false);
        add(m, "delete_chat", """
                delete a conversation of your own project, for example one you made with create_chat. Refused,
                with the reason and nothing changed, for project1's chat-01, the project's last conversation,
                one that is answering or has queued prompts, one with conversations under it, and your own.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "get_chat_log", """
                read the newest entries of another conversation's log without sending it anything — to see what
                it is doing while an ask_chat to it waits, or why a call to it failed. "count" is optional
                (default 20, at most 200).
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("count", "integer", false, "")),
                false);
        add(m, "get_request_messages", """
                read the messages array another conversation will send to its model next, as a JSON array of
                {"role","content"}. This is what the model reads, not the screen's record. Refused while that
                conversation is answering.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, "")),
                false);
        add(m, "set_request_messages", """
                replace the whole messages array another conversation of this project will send next. "messages"
                is a JSON array of {"role","content"} with roles system, user and assistant. An array that is
                malformed or too long for the model is refused and nothing changes. To drop one message, read
                the array, remove it, and set the result.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("messages", "string", true, "")),
                false);
        add(m, "complete_messages", """
                call the model of another conversation once with exactly the given messages and return its
                reply. Nothing is added to that conversation's history, and it works while that conversation is
                answering. "model" and "noThink" are optional.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("messages", "string", true, ""),
                    new ToolParameter("model", "string", false, ""),
                    new ToolParameter("noThink", "boolean", false, "")),
                false);
        return Collections.unmodifiableMap(m);
    }

    /** @return the names of the built-in tools that drive the conversation */
    public static List<String> conversationDrivingTools() {
        return BUILT_IN.values().stream().filter(Spec::drivesConversation).map(Spec::name)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
