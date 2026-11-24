package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.agent.ToolCatalog.Spec;
import com.scivicslab.chatui.plugin.ToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The built-in tools a conversation is shown, in the order the model sees them
 * ({@code ToolCatalogAndChatUiMcpServer_261120_oo01}).
 *
 * <p>The tools agent-connector-kernel runs itself ({@code read}, {@code calc}, {@code search_docs},
 * {@code list_references}, {@code write}, {@code remove}) are defined in agent-connector-kernel's {@link ToolCatalog}
 * and placed here where they always stood. Every other built-in tool works on ConversationSquad actors or the actor
 * tree, so it is defined here, with the screen and the record.</p>
 */
public final class ConversationToolCatalog {

    private static final Map<String, Spec> BUILT_IN = builtIn();

    private ConversationToolCatalog() {
    }

    /** @return the built-in tools, keyed by name, in the order the model is shown them */
    public static Map<String, Spec> builtInTools() {
        return BUILT_IN;
    }

    /** @return the built-in tool {@code name}, or {@code null} when there is none */
    public static Spec builtIn(String name) {
        return BUILT_IN.get(name);
    }

    /** @return the names of the built-in tools that drive the conversation */
    public static List<String> conversationDrivingTools() {
        return BUILT_IN.values().stream().filter(Spec::drivesConversation).map(Spec::name)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static void add(Map<String, Spec> m, String name, String description, List<ToolParameter> parameters,
                            boolean drivesConversation) {
        m.put(name, ToolCatalog.define(name, description, parameters, drivesConversation));
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
        m.put("read", ToolCatalog.core("read"));
        m.put("calc", ToolCatalog.core("calc"));
        m.put("search_docs", ToolCatalog.core("search_docs"));
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
        m.put("list_references", ToolCatalog.core("list_references"));
        m.put("write", ToolCatalog.core("write"));
        m.put("remove", ToolCatalog.core("remove"));
        add(m, "ask_chat", """
                send an instruction to another conversation. Write chatId by where that conversation is: in your
                own project, "chat-02"; in another project of this chat-ui instance, "project1/chat-01", with no
                node. Every project shown on this instance's screen is on this instance, so a conversation of
                this instance, in any project, takes no node. On another chat-ui instance: to find the node name
                of another instance, call list_running_tools first; give it as "node", and write chatId with the
                project on that instance first, e.g. "project1/chat-01"; with node, a chatId without its project
                ("chat-01") is refused.
                It does NOT wait: the result says the instruction was sent, to which conversation of which
                project on which instance (node), and under which request id, e.g.
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
                as ask_chat, with chatId and node written as ask_chat says: a conversation of this instance, in
                any project, takes no node ("chat-02", "project1/chat-01"); on another instance (to find the node
                name of another instance, call list_running_tools first), give the node and write chatId with its
                project, e.g. "project1/chat-01". The
                request goes into the other conversation's queue ahead of
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
                conversation's queue. "node" is optional and only for a request sent to another chat-ui instance
                (to find the node name of another instance, call list_running_tools first); a request to a
                conversation of this instance, in any
                project, takes no node. A request sent to another instance is found by its id; its result then
                arrives as a new message. "requestId" is the id the ask_chat result gave, e.g.
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
                out keeps its value. 0 turns that watch off: childReplyWarnMinutes 0 gives no "no reply yet"
                notices at all, urgentWaitMinutes 0 never pauses a turn. It also takes your own chatId.
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
                reminder N]. If that says "not yet" again, register again. "minutes" is in whole minutes. A
                conversation of this instance, in any project, takes no node. Give "node" only when the
                conversation that promised the report is on another chat-ui instance, as you gave it to ask_chat
                (to find the node name of another instance, call list_running_tools first), and then write chatId
                with its project first, e.g.
                "project1/chat-01".
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
                "worker") is "collaboratorChatId". A conversation of this instance, in any project, takes no
                node. Give "node" only when the one playing the role is a conversation of another chat-ui
                instance, as ask_chat takes it (to find the node name of another instance, call list_running_tools
                first); then write
                collaboratorChatId with its project on that instance first, e.g. "project1/chat-01", and the
                agent loop sends its work there. An agent loop installed via set_agent_loop can then
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
        // ExternalMcpServersOfAConversation_261120_oo01
        add(m, "set_mcp_servers", """
                register the external MCP servers a conversation of this project uses, replacing the ones it
                had. "servers" is a JSON object from a server name (a lowercase letter, then lowercase letters,
                digits or '-', at most 20) to the URL of its MCP endpoint, for example
                {"htmlsaurus": "http://localhost:28001/mcp"}; {} removes them all. From that conversation's
                next turn, a local LLM there is given each server's tools named <server>__<tool>, for example
                htmlsaurus__search.
                """,
                List.of(
                    new ToolParameter("chatId", "string", true, ""),
                    new ToolParameter("servers", "string", true, "")),
                false);
        return Collections.unmodifiableMap(m);
    }
}
