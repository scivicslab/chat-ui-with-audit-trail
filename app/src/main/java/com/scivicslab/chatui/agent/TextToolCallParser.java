package com.scivicslab.chatui.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
    private static final String INVOKE_START =
            "^[ \\t]*(?:<function_calls>\\s*)?<invoke\\s+name=\"([^\"]+)\"\\s*>";
    // Each <invoke name="TOOL"> ... </invoke> block that starts a line (DOTALL so it spans newlines).
    private static final Pattern INVOKE =
            Pattern.compile(INVOKE_START + "(.*?)</invoke>", Pattern.DOTALL | Pattern.MULTILINE);
    // The start of a call, whether or not its </invoke> was written.
    private static final Pattern INVOKE_OPEN = Pattern.compile(INVOKE_START, Pattern.MULTILINE);
    // A <parameter name="KEY">VALUE</parameter> pair; VALUE ends at </parameter>, the next <parameter,
    // <reason>, or </invoke> — tolerating a missing close tag.
    private static final Pattern PARAM = Pattern.compile(
            "<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)(?:</parameter>|(?=<parameter)|(?=<reason)|(?=</invoke>)|$)",
            Pattern.DOTALL);
    // A bare <reason>VALUE (with or without a closing tag) some outputs use instead of a parameter.
    private static final Pattern REASON = Pattern.compile(
            "<reason\\s*>(.*?)(?:</reason>|(?=</invoke>)|(?=<parameter)|$)", Pattern.DOTALL);
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
        return INVOKE_OPEN.matcher(visible).find() || FUNCTION.matcher(visible).find()
                || GLM.matcher(visible).find() || GLM_TRAILING.matcher(visible).find();
    }

    /**
     * Parses every textual tool call in {@code content}. Returns synthetic {@link ToolCall}s
     * (ids {@code text-call-0}, {@code text-call-1}, …) whose {@code argumentsJson} is a JSON object
     * built from the extracted parameters, ready to feed to the tool dispatch in {@code runTool()}.
     *
     * @return the parsed calls, or an empty list when none are present
     */
    public static List<ToolCall> parse(String content) {
        List<ToolCall> calls = new ArrayList<>();
        if (!looksLikeTextToolCall(content)) return calls;

        Matcher inv = INVOKE.matcher(withoutCodeFences(content));
        int idx = 0;
        while (inv.find()) {
            String name = inv.group(1).trim();
            String body = inv.group(2);

            Map<String, String> args = new LinkedHashMap<>();
            Matcher pm = PARAM.matcher(body);
            while (pm.find()) {
                args.put(pm.group(1).trim(), clean(pm.group(2)));
            }
            Matcher rm = REASON.matcher(body);
            if (rm.find() && !args.containsKey("reason")) {
                args.put("reason", clean(rm.group(1)));
            }
            calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
        }

        Matcher fn = FUNCTION.matcher(withoutCodeFences(content));
        while (fn.find()) {
            String name = fn.group(1).trim();
            Map<String, String> args = new LinkedHashMap<>();
            Matcher pm = FUNCTION_PARAM.matcher(fn.group(2));
            while (pm.find()) {
                args.put(pm.group(1).trim(), clean(pm.group(2)));
            }
            calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
        }

        String visible = withoutCodeFences(content);
        for (Pattern glmSpelling : List.of(GLM, GLM_TRAILING)) {
            Matcher glm = glmSpelling.matcher(visible);
            while (glm.find()) {
                String name = glm.group(1).trim();
                Map<String, String> args = new LinkedHashMap<>();
                Matcher am = GLM_ARG.matcher(glm.group(2));
                while (am.find()) {
                    args.put(am.group(1).trim(), clean(am.group(2)));
                }
                calls.add(new ToolCall("text-call-" + idx++, name, toJson(args)));
            }
        }
        return calls;
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
        out = out.replaceAll("(?ms)^[ \\t]*<invoke\\s+name=\"[^\"]+\"\\s*>.*?</invoke>", "");
        // The <tool_call><function=…> spelling, with or without the <tool_call> wrapper.
        out = out.replaceAll("(?ms)^[ \\t]*<tool_call>.*?</tool_call>", "");
        out = out.replaceAll("(?ms)^[ \\t]*<function=[^>\\s]+\\s*>.*?</function>", "");
        // GLM's spelling with its </tool_call> missing: to the next call that starts a line, or the end.
        out = out.replaceAll("(?ms)" + GLM_START + ".*?(?=^[ \\t]*<tool_call>|\\z)", "");
        // ... and the call glued to the end of a finished sentence.
        out = out.replaceAll("(?s)" + GLM_TRAILING_START + ".*?</tool_call>\\s*\\z", "");
        return JUNK_TOKEN.matcher(out).replaceAll("").trim();
    }
}
