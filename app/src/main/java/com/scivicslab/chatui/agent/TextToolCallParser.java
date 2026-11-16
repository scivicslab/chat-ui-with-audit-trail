package com.scivicslab.chatui.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Parses tool calls an LLM writes as plain text, in the format
 * {@code ChatSessionAgentLoop_260823_oo01} instructs it to use:
 *
 * <pre>{@code
 * <function_calls>
 * <invoke name="web_search">
 * <parameter name="query">tokyo weather</parameter>
 * <reason>need the forecast</reason>
 * </invoke>
 * </function_calls>
 * }</pre>
 *
 * <p>A second spelling is accepted too, because some models write it whatever they are told
 * (measured: Gemma 4 behind the GPU broker):</p>
 *
 * <pre>{@code
 * <tool_call>
 * <function=web_search>
 * <parameter=query>
 * tokyo weather
 * </parameter>
 * </function>
 * </tool_call>
 * }</pre>
 *
 * <p>A third spelling is GLM's own (measured: GLM-5.3-Flash behind an OpenAI-compatible server, on
 * {@code project9/chat-01}): the tool name directly after {@code <tool_call>}, then key and value pairs.</p>
 *
 * <pre>{@code
 * <tool_call>load_skill<arg_key>name</arg_key><arg_value>html-saurus-search</arg_value></tool_call>
 * }</pre>
 *
 * <p>All spellings produce the same {@link ToolCall}. Without the second, that text matched no tool
 * call, so the agent loop took it for the final answer and returned it, unexecuted, to the caller. The
 * third did the same: three turns of a conversation ended at once, each showing the raw call as its answer.</p>
 *
 * <p>The first spelling was ported verbatim (parsing logic unchanged) from {@code quarkus-chat-ui3}'s
 * {@code TextToolCallParser}, where this was a fallback for a model that fell out of the native
 * {@code tool_calls} channel. {@code chat-ui-with-audit-trail} does not use native {@code tool_calls}
 * at all, so this is the only path — see {@code ChatSessionAgentLoop_260823_oo01} "ツール呼び出し要求の
 * 検出は、テキスト解析で行う". It is deliberately tolerant: it accepts missing {@code </parameter>} tags,
 * a bare {@code <reason>...} without a close tag, and strips the stray {@code <|"|>} special-token junk
 * some models interleave.</p>
 */
public final class TextToolCallParser {

    private TextToolCallParser() {}

    // Where an <invoke name="TOOL"> call begins: at the start of a line (spaces allowed), optionally
    // behind <function_calls>, the way a model writes one. The same tag in the middle of a sentence, in
    // backticks or in a table row is prose quoting the spelling, not a call, as for the function=
    // spelling below (2026-11-08: a report that quoted call blocks was run as calls and lost).
    // The quotes around a name in the tag syntax: the ASCII " and the typographic ones a model sometimes
    // writes instead (“ ” ″, measured on GLM-5.3-Flash-Uncensored: <invoke name=“read">). Only here, around
    // a name; a parameter's value is taken as written.
    private static final String Q = "[\"\u201C\u201D\u2033]";
    private static final String NAME = "([^\"\u201C\u201D\u2033]+)";
    private static final String INVOKE_START =
            "^[ \\t]*(?:<function_calls>\\s*)?<invoke\\s+name=" + Q + NAME + Q + "\\s*>";
    // Each <invoke name="TOOL"> ... </invoke> block that starts a line (DOTALL so it spans newlines).
    private static final Pattern INVOKE =
            Pattern.compile(INVOKE_START + "(.*?)</invoke>", Pattern.DOTALL | Pattern.MULTILINE);
    // The same call glued to the end of a finished sentence, on the same line: "...確かめます。<invoke name=...>...</invoke>"
    // (SquareBracketCallRepair_261116_oo01). It must be the last thing in the reply, and what comes before the tag
    // must be the end of a sentence, as for GLM's spelling below: a tag quoted in the middle of a sentence, after a
    // colon, a space or a backtick, is prose about the spelling and not a call. Groups: 1 the tool name, 2 the body.
    private static final String SENTENCE_END = "[。．.!?！？)）」』\\]】]";
    private static final String INVOKE_TRAILING_START =
            "(?<=" + SENTENCE_END + "[ \\t]{0,3})<invoke\\s+name=" + Q + NAME + Q + "\\s*>";
    private static final Pattern INVOKE_TRAILING = Pattern.compile(
            INVOKE_TRAILING_START + "(.*?)</invoke>(?:\\s*</function_calls>)?\\s*\\z", Pattern.DOTALL);
    // A call written the way the prompt tells a model to show an example, with square brackets. It is never run,
    // since an answer may quote one; analyze() calls it a broken attempt only when it names a tool of the
    // conversation and carries a parameter block.
    private static final Pattern BRACKET_INVOKE = Pattern.compile(
            "^[ \\t]*(?:\\[function_calls\\]\\s*)?\\[invoke\\s+name=" + Q + NAME + Q + "\\s*\\](.*?)(?:\\[/invoke\\]|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final Pattern BRACKET_PARAM =
            Pattern.compile("\\[parameter\\s+name=" + Q + NAME + Q + "\\s*\\](.*?)\\[/parameter\\]", Pattern.DOTALL);
    // The start of a call, whether or not its </invoke> was written.
    private static final Pattern INVOKE_OPEN = Pattern.compile(INVOKE_START, Pattern.MULTILINE);
    // The opening tag of a <parameter name="KEY">VALUE</parameter> pair. Where VALUE ends is decided by
    // valueEnd: a value may itself contain these tags as text (a prompt that tells another conversation
    // how to write a call), so the next "<parameter" alone does not end it.
    private static final Pattern PARAM_OPEN = Pattern.compile("<parameter\\s+name=" + Q + NAME + Q + "\\s*>");
    // A parameter or <reason> that starts a line: where a value whose </parameter> is missing ends.
    private static final Pattern NEXT_AT_LINE_START =
            Pattern.compile("^[ \\t]*(?:<parameter\\s+name=" + Q + "|<reason\\s*>)", Pattern.MULTILINE);
    private static final String PARAM_CLOSE = "</parameter>";
    // A bare <reason>VALUE (with or without a closing tag) some outputs use instead of a parameter.
    private static final Pattern REASON = Pattern.compile(
            "<reason\\s*>(.*?)(?:</reason>|(?=</invoke>)|(?=<parameter\\s+name=)|$)", Pattern.DOTALL);
    // Anything at the start of a line that begins a call, whether or not the rest of it can be read: what
    // analyze() checks a reply for, to tell a broken call attempt from prose.
    private static final Pattern ATTEMPT = Pattern.compile(
            "^[ \\t]*(?:<function_calls>\\s*)?(?:<invoke\\b|<tool_call>|<function=)", Pattern.MULTILINE);
    // The <function=TOOL> ... </function> spelling. A call begins at the start of a line (optionally
    // behind <tool_call>), the way a model writes one: the same tags quoted in the middle of a
    // sentence or a table row are prose about the spelling, not a call. The body ends at
    // </function>, at the next call, or at the end of the text, so a missing close tag still yields
    // the call.
    private static final Pattern FUNCTION = Pattern.compile(
            "^[ \\t]*(?:<tool_call>\\s*)?<function=([^>\\s]+)\\s*>(.*?)"
                    + "(?:</function>|(?=^[ \\t]*(?:<tool_call>\\s*)?<function=)|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);
    // GLM's spelling: <tool_call>NAME<arg_key>K</arg_key><arg_value>V</arg_value>...</tool_call>. A call begins at
    // the start of a line, like the others, with the name directly after <tool_call> and then either an
    // <arg_key> or the close tag: "<tool_call> is the tag" in prose is not a call named "is". The body ends at
    // </tool_call>, at the next call that starts a line, or at the end of the text, so a missing close tag still
    // yields the call. <function= after <tool_call> is the other spelling: a name starts with a letter.
    private static final String GLM_START =
            "^[ \\t]*<tool_call>[ \\t]*\\n?[ \\t]*([A-Za-z_][\\w.\\-]*+)\\s*(?=<arg_key>|</tool_call>)";
    private static final Pattern GLM = Pattern.compile(
            GLM_START + "(.*?)(?:</tool_call>|(?=^[ \\t]*<tool_call>)|\\z)", Pattern.DOTALL | Pattern.MULTILINE);
    // The same call glued to the end of the reply, as GLM appends accept_reply to a finished answer (measured on
    // project9/chat-01: "...完了済みです。<tool_call>accept_reply<arg_key>...</tool_call>" with no line break). A
    // quotation in prose is not this: it is not the last thing in the reply, and does not follow a finished
    // sentence (a colon, a space, a backtick or a quote mark before it).
    private static final String GLM_TRAILING_START = "(?<=[。．.!?！？)）」』\\]】])<tool_call>[ \\t]*"
            + "([A-Za-z_][\\w.\\-]*+)\\s*(?=<arg_key>|</tool_call>)";
    private static final Pattern GLM_TRAILING =
            Pattern.compile(GLM_TRAILING_START + "(.*?)</tool_call>\\s*\\z", Pattern.DOTALL);
    // A <arg_key>KEY</arg_key><arg_value>VALUE</arg_value> pair. VALUE ends at </arg_value>, the next <arg_key>,
    // </tool_call> or the end of the text; the <arg_value> open tag may be missing (measured), and so the close.
    private static final Pattern GLM_ARG = Pattern.compile(
            "<arg_key>(.*?)</arg_key>\\s*(?:<arg_value>)?(.*?)(?:</arg_value>|(?=<arg_key>)|(?=</tool_call>)|$)",
            Pattern.DOTALL);
    // Fenced code blocks are examples, not calls.
    private static final Pattern CODE_FENCE = Pattern.compile("```.*?```", Pattern.DOTALL);
    // A <parameter=KEY>VALUE</parameter> pair of that spelling; VALUE ends at </parameter>, the next
    // <parameter=, </function> or </tool_call>, or the end of the text.
    private static final Pattern FUNCTION_PARAM = Pattern.compile(
            "<parameter=([^>\\s]+)\\s*>(.*?)(?:</parameter>|(?=<parameter=)|(?=</function>)|(?=</tool_call>)|$)",
            Pattern.DOTALL);
    // Stray special tokens the model leaks into values, e.g. <|"|>.
    private static final Pattern JUNK_TOKEN = Pattern.compile("<\\|[^>]*\\|>");

    /**
     * True when {@code content} contains at least one textual tool-call block, in either spelling.
     * A block inside a fenced code block, or not at the start of a line, is a quotation and does not count.
     */
    public static boolean looksLikeTextToolCall(String content) {
        if (content == null) return false;
        String visible = withoutCodeFences(content);
        return INVOKE_OPEN.matcher(visible).find() || INVOKE_TRAILING.matcher(visible).find()
                || FUNCTION.matcher(visible).find() || GLM.matcher(visible).find() || GLM_TRAILING.matcher(visible).find();
    }

    /**
     * Parses every textual tool call in {@code content}. Returns synthetic {@link ToolCall}s
     * (ids {@code text-call-0}, {@code text-call-1}, …) whose {@code argumentsJson} is a JSON object
     * built from the extracted parameters, ready to feed to the tool dispatch in {@code runTool()}.
     *
     * @return the parsed calls, or an empty list when none are present
     */
    public static List<ToolCall> parse(String content) {
        return analyze(content, name -> List.of()).calls();
    }

    /**
     * What a reply's tool calls are, and what is wrong with any it tried to write
     * ({@code BrokenToolCallRepair_261115_oo01}).
     *
     * @param calls    every call that could be read, in order
     * @param problems one sentence per thing that kept a call from being run, empty when every call is
     *                 sound; when not empty, the reply is a broken call attempt and none of its calls runs
     * @param example  the call written out in the exact format, for the tool the first broken attempt
     *                 named (or {@code read} when it named none)
     */
    public record Analysis(List<ToolCall> calls, List<String> problems, String exampleTool) {
        /** @return whether the reply tried to call a tool and the call cannot be run as written */
        public boolean broken() { return !problems.isEmpty(); }
    }

    /**
     * Reads every call in {@code content} and checks each attempt to write one. A line that starts a
     * call ({@code <invoke}, {@code <tool_call>}, {@code <function=}) but yields none, a call whose
     * tool needs arguments it was not given, and the quote marks a model sometimes writes in place of
     * {@code "} are named, so the model can be told exactly what to write again. A call quoted in a
     * fenced code block or in the middle of a sentence is prose, not an attempt.
     *
     * @param content        the reply
     * @param requiredParams the arguments each tool cannot run without, by tool name (empty for a tool
     *                       this does not know)
     */
    public static Analysis analyze(String content, Function<String, List<String>> requiredParams) {
        return analyze(content, requiredParams, name -> false);
    }

    /**
     * As {@link #analyze(String, Function)}, and a call written in the example form (square brackets) that names a
     * tool of this conversation and carries a parameter block is a broken attempt too, when the reply holds no other
     * call ({@code SquareBracketCallRepair_261116_oo01}): the model that was told to write an example in square
     * brackets sometimes writes a real call that way. It is never run; the model is told to write it again.
     *
     * @param toolHere whether a tool of this name is one this conversation may call
     */
    public static Analysis analyze(String content, Function<String, List<String>> requiredParams,
                                   Predicate<String> toolHere) {
        List<ToolCall> calls = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String[] exampleTool = {null};
        if (content == null) return new Analysis(calls, problems, "read");
        String visible = withoutCodeFences(content);
        // Where each readable call lies, to find the attempts no pattern read.
        List<int[]> readSpans = new ArrayList<>();
        int idx = 0;

        for (Pattern invokePattern : List.of(INVOKE, INVOKE_TRAILING)) {
        Matcher inv = invokePattern.matcher(visible);
        while (inv.find()) {
            readSpans.add(new int[]{inv.start(), inv.end()});
            String name = inv.group(1).trim();
            String opener = visible.substring(inv.start(), inv.end(1) + 1);
            Map<String, String> args = invokeArgs(inv.group(2));
            // <reason> is looked for outside the parameter values: a value may mention the tag as text.
            Matcher rm = REASON.matcher(outsideValues(inv.group(2)));
            if (rm.find() && !args.containsKey("reason")) args.put("reason", clean(rm.group(1)));
            calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
            List<String> wrong = argumentProblems(name, args, inv.group(2), requiredParams);
            if (!wrong.isEmpty()) {
                if (!opener.trim().matches("(?s).*name=\".*\"")) {
                    wrong.add(0, "the quote around name= in " + opener.trim() + " is not the ASCII quote \"");
                }
                problems.addAll(wrong);
                if (exampleTool[0] == null) exampleTool[0] = name;
            }
        }
        }

        Matcher fn = FUNCTION.matcher(visible);
        while (fn.find()) {
            readSpans.add(new int[]{fn.start(), fn.end()});
            String name = fn.group(1).trim();
            Map<String, String> args = new LinkedHashMap<>();
            Matcher pm = FUNCTION_PARAM.matcher(fn.group(2));
            while (pm.find()) {
                args.put(pm.group(1).trim(), clean(pm.group(2)));
            }
            calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
            List<String> wrong = argumentProblems(name, args, null, requiredParams);
            if (!wrong.isEmpty()) {
                problems.addAll(wrong);
                if (exampleTool[0] == null) exampleTool[0] = name;
            }
        }

        for (Pattern glmSpelling : List.of(GLM, GLM_TRAILING)) {
            Matcher glm = glmSpelling.matcher(visible);
            while (glm.find()) {
                readSpans.add(new int[]{glm.start(), glm.end()});
                String name = glm.group(1).trim();
                Map<String, String> args = new LinkedHashMap<>();
                Matcher am = GLM_ARG.matcher(glm.group(2));
                while (am.find()) {
                    args.put(am.group(1).trim(), clean(am.group(2)));
                }
                calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
                List<String> wrong = argumentProblems(name, args, null, requiredParams);
                if (!wrong.isEmpty()) {
                    problems.addAll(wrong);
                    if (exampleTool[0] == null) exampleTool[0] = name;
                }
            }
        }

        // A line that starts a call no pattern above could read.
        List<Integer> attempts = new ArrayList<>();
        Matcher attempt = ATTEMPT.matcher(visible);
        while (attempt.find()) attempts.add(attempt.start());
        for (int i = 0; i < attempts.size(); i++) {
            int at = attempts.get(i);
            if (readSpans.stream().anyMatch(span -> at >= span[0] && at < span[1])) continue;
            int until = i + 1 < attempts.size() ? attempts.get(i + 1) : visible.length();
            int lineEnd = visible.indexOf('\n', at);
            String line = visible.substring(at, lineEnd < 0 || lineEnd > until ? until : lineEnd).trim();
            Matcher open = INVOKE_OPEN.matcher(visible);
            if (open.find(at) && open.start() == at) {
                // An <invoke name="TOOL"> whose </invoke> never came: say everything wrong with it, not
                // only the missing close tag, so one repair can fix it all.
                String name = open.group(1).trim();
                String opener = visible.substring(at, open.end()).trim();
                if (!opener.matches("(?s).*name=\".*\".*")) {
                    problems.add("the quote around name= in " + opener + " is not the ASCII quote \"");
                }
                problems.add("your call to " + name + " is not closed with </invoke>");
                String body = visible.substring(open.end(), until);
                problems.addAll(argumentProblems(name, invokeArgs(body), body, requiredParams));
                if (exampleTool[0] == null) exampleTool[0] = name;
            } else {
                problems.add(unreadableAttempt(line, visible.substring(at, until)));
                if (exampleTool[0] == null) exampleTool[0] = "read";
            }
        }
        // Nothing could be run, and nothing was found wrong: a call in the example form is an attempt, not an answer.
        if (calls.isEmpty() && problems.isEmpty()) {
            Matcher square = BRACKET_INVOKE.matcher(visible);
            while (square.find()) {
                String name = square.group(1).trim();
                if (!toolHere.test(name) || !BRACKET_PARAM.matcher(square.group(2)).find()) continue;
                problems.add("your call to " + name + " is written with square brackets ([invoke ...], [parameter ...]), "
                        + "the form for showing a call as an example, so it was not run; write the call again with "
                        + "angle brackets (< and >). If you meant it as an example, write your answer again without "
                        + "a complete call block");
                if (exampleTool[0] == null) exampleTool[0] = name;
            }
        }
        return new Analysis(calls, problems, exampleTool[0] == null ? "read" : exampleTool[0]);
    }

    /** What keeps a call that was read from running: required arguments it was not given. */
    private static List<String> argumentProblems(String name, Map<String, String> args, String body,
                                                 Function<String, List<String>> requiredParams) {
        List<String> wrong = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        // Only an argument left out counts as missing: a tool decides what an empty one means (set_chat_name
        // clears the name with it).
        for (String p : requiredParams.apply(name)) {
            if (!args.containsKey(p)) missing.add(p);
        }
        if (missing.isEmpty()) return wrong;
        boolean noTags = args.keySet().stream().allMatch("reason"::equals);
        if (noTags) {
            wrong.add("your call to " + name + " has no <parameter> tag: each argument must be written inside "
                    + "<parameter name=\"...\">...</parameter>; lines without that tag are not read as arguments");
        } else {
            wrong.add("your call to " + name + " lacks the parameter" + (missing.size() > 1 ? "s " : " ")
                    + String.join(", ", missing.stream().map(m -> "\"" + m + "\"").toList()));
        }
        if ("read".equals(name) && missing.contains("path") && body != null && hasBareLines(body)) {
            wrong.add("read takes all its paths in one \"path\" parameter, one path per line");
        }
        return wrong;
    }

    /** Whether a call's body has lines of text outside any tag, as a model writes arguments it did not tag. */
    private static boolean hasBareLines(String body) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("<")) return true;
        }
        return false;
    }

    /** Says why a line that starts a call could not be read as one. */
    private static String unreadableAttempt(String line, String fromLine) {
        if (line.startsWith("<invoke") || line.startsWith("<function_calls>")) {
            if (!line.contains("name=")) return "the line " + line + " starts a call but names no tool with name=\"...\"";
            if (!fromLine.contains("</invoke>")) return "the call " + line + " is not closed with </invoke>";
            return "the line " + line + " starts a call that could not be read; the opening tag must be "
                    + "<invoke name=\"TOOL\"> with ASCII quotes";
        }
        return "the line " + line + " starts a call that names no tool";
    }

    /**
     * The arguments of one {@code <invoke>} call's body. Each value runs from its opening tag to the
     * {@code </parameter>} that is followed by the next parameter, a {@code <reason>}, or the end of
     * the call, so a value may contain these tags as text. When that close tag is missing, the value
     * ends at the next parameter or {@code <reason>} that starts a line, or at the end of the call.
     */
    private static Map<String, String> invokeArgs(String body) {
        Map<String, String> args = new LinkedHashMap<>();
        Matcher open = PARAM_OPEN.matcher(body);
        int from = 0;
        while (from < body.length() && open.find(from)) {
            String key = open.group(1).trim();
            int valueStart = open.end();
            int[] end = valueEnd(body, valueStart);
            args.put(key, clean(body.substring(valueStart, end[0])));
            from = end[1];
        }
        return args;
    }

    /** @return a call's body with every parameter, value and tags, taken out */
    private static String outsideValues(String body) {
        StringBuilder outside = new StringBuilder();
        Matcher open = PARAM_OPEN.matcher(body);
        int from = 0;
        while (from < body.length() && open.find(from)) {
            outside.append(body, from, open.start());
            from = valueEnd(body, open.end())[1];
        }
        if (from < body.length()) outside.append(body.substring(from));
        return outside.toString();
    }

    /** @return {where the value ends, where the search for the next parameter goes on} */
    private static int[] valueEnd(String body, int valueStart) {
        // A parameter that starts a line before any fitting close tag means this value's close tag is
        // missing: the value ends there rather than taking in the next parameter.
        Matcher next = NEXT_AT_LINE_START.matcher(body);
        int nextAt = next.find(valueStart) ? next.start() : -1;
        int close = body.indexOf(PARAM_CLOSE, valueStart);
        while (close >= 0) {
            if (nextAt >= 0 && nextAt < close) return new int[]{nextAt, nextAt};
            int after = close + PARAM_CLOSE.length();
            String rest = body.substring(after).stripLeading();
            if (rest.isEmpty() || rest.startsWith("<reason") || PARAM_OPEN.matcher(rest).lookingAt()) {
                return new int[]{close, after};
            }
            close = body.indexOf(PARAM_CLOSE, after);
        }
        if (nextAt >= 0) return new int[]{nextAt, nextAt};
        return new int[]{body.length(), body.length()};
    }

    /** @return {@code content} with its fenced code blocks removed */
    private static String withoutCodeFences(String content) {
        return CODE_FENCE.matcher(content).replaceAll("");
    }

    /** Strips leaked special tokens and surrounding whitespace from an extracted value. */
    private static String clean(String v) {
        if (v == null) return "";
        return JUNK_TOKEN.matcher(v).replaceAll("").trim();
    }

    /** Minimal JSON object serialisation for the extracted string parameters. */
    private static String toJson(Map<String, String> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Removes the textual {@code <function_calls>…</function_calls>} block(s) from prose to keep. */
    public static String stripToolCallBlocks(String content) {
        if (content == null) return "";
        String out = content.replaceAll("(?s)<function_calls>.*?</function_calls>", "");
        // Some outputs omit the wrapper and emit a bare <invoke>…</invoke>; drop those too.
        out = out.replaceAll("(?ms)^[ \\t]*<invoke\\s+name=" + Q + NAME + Q + "\\s*>.*?</invoke>", "");
        // The <tool_call><function=…> spelling, with or without the <tool_call> wrapper.
        out = out.replaceAll("(?ms)^[ \\t]*<tool_call>.*?</tool_call>", "");
        out = out.replaceAll("(?ms)^[ \\t]*<function=[^>\\s]+\\s*>.*?</function>", "");
        // GLM's spelling with its </tool_call> missing: to the next call that starts a line, or the end.
        out = out.replaceAll("(?ms)" + GLM_START + ".*?(?=^[ \\t]*<tool_call>|\\z)", "");
        // ... and the call glued to the end of a finished sentence.
        out = out.replaceAll("(?s)" + GLM_TRAILING_START + ".*?</tool_call>\\s*\\z", "");
        out = out.replaceAll("(?s)" + INVOKE_TRAILING_START + ".*?</invoke>(?:\\s*</function_calls>)?\\s*\\z", "");
        return JUNK_TOKEN.matcher(out).replaceAll("").trim();
    }
}
