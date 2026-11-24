package com.scivicslab.chatui.audittrail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of the block a conversation's first request starts with, counted in characters and estimated in
 * tokens, for the Tokens tab. Plain Java: the files, the MCP tool lists and the measured total are handed in
 * by {@link HeadBlockResource}.
 *
 * <p>The token counts are estimates. Characters are counted exactly; a token count needs the model's own
 * tokenizer, so {@link #estimateTokens} divides the characters by a fixed factor: {@value #CHARS_PER_TOKEN_JAPANESE}
 * characters per token when at least {@value #JAPANESE_HEAVY_SHARE_PERCENT} percent of the characters are not
 * ASCII, and {@value #CHARS_PER_TOKEN_ENGLISH} otherwise (English text and JSON). The measured first-request
 * total beside the estimate shows how far off the estimate is.</p>
 */
public final class HeadBlock {

    /** Characters per token assumed for text that is mostly Japanese. */
    public static final double CHARS_PER_TOKEN_JAPANESE = 1.5;
    /** Characters per token assumed for English text and JSON. */
    public static final double CHARS_PER_TOKEN_ENGLISH = 3.5;
    /** The share of non-ASCII characters, in percent, from which a text counts as Japanese-heavy. */
    public static final int JAPANESE_HEAVY_SHARE_PERCENT = 30;

    /** Who may cut a part: a file a person edits. */
    public static final String PERSON_EDITED_FILE = "person-edited file";
    /** Who may cut a part: a setting of the conversation. */
    public static final String CONVERSATION_SETTING = "conversation setting";
    /** Who may cut a part: nobody here, the CLI fixes it. */
    public static final String FIXED_BY_THE_CLI = "fixed by the CLI";

    /**
     * One part of the head block.
     *
     * @param name            what the part is, including how many files, servers or tools it holds
     * @param chars           the characters counted; 0 when {@code measured} is false
     * @param estimatedTokens {@link #estimateTokens} of the part's text; 0 when {@code measured} is false
     * @param measured        whether the characters were counted here (the tokens are always an estimate)
     * @param whoCanCut       {@link #PERSON_EDITED_FILE}, {@link #CONVERSATION_SETTING} or {@link #FIXED_BY_THE_CLI}
     */
    public record Part(String name, int chars, int estimatedTokens, boolean measured, String whoCanCut) {}

    /**
     * The answer of {@code GET /api/chats/{id}/head-block}.
     *
     * @param parts                      the parts, in the order the CLI reads them
     * @param estimatedTotal             the sum of the parts' estimated tokens
     * @param measuredFirstRequestTotal  the first request's input tokens as the provider reported them, or null
     */
    public record Body(List<Part> parts, int estimatedTotal, Integer measuredFirstRequestTotal) {}

    private HeadBlock() {}

    /** @return the estimated tokens of {@code text}, by the factors in the class comment; 0 for no text */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int total = text.codePointCount(0, text.length());
        int nonAscii = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp > 127) nonAscii++;
            i += Character.charCount(cp);
        }
        boolean japaneseHeavy = nonAscii * 100 >= total * JAPANESE_HEAVY_SHARE_PERCENT;
        double factor = japaneseHeavy ? CHARS_PER_TOKEN_JAPANESE : CHARS_PER_TOKEN_ENGLISH;
        return (int) Math.ceil(total / factor);
    }

    /** @return a part whose characters were counted from {@code text} */
    public static Part measuredPart(String name, String text, String whoCanCut) {
        String t = text == null ? "" : text;
        return new Part(name, t.codePointCount(0, t.length()), estimateTokens(t), true, whoCanCut);
    }

    /** @return a part that could not be counted here: no characters, no tokens, and a name that says why */
    public static Part unmeasuredPart(String name, String whoCanCut) {
        return new Part(name, 0, 0, false, whoCanCut);
    }

    /** @return the parts with their estimated total and the measured first-request total, which may be null */
    public static Body body(List<Part> parts, Integer measuredFirstRequestTotal) {
        int sum = 0;
        for (Part p : parts) sum += p.estimatedTokens();
        return new Body(List.copyOf(parts), sum, measuredFirstRequestTotal);
    }

    /**
     * The CLAUDE.md files the Claude Code CLI reads at the start for a working directory: the user's
     * {@code ~/.claude/CLAUDE.md}, then, from the file system root down to the working directory, each
     * directory's {@code CLAUDE.md}, {@code .claude/CLAUDE.md} and {@code CLAUDE.local.md}. Only files that
     * exist are listed. {@code @}-imports inside them are not followed.
     *
     * @param workingDir the conversation's working directory, or null for none
     * @param home       the user's home directory
     */
    public static List<Path> claudeMdFiles(Path workingDir, Path home) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(home.resolve(".claude").resolve("CLAUDE.md"));
        if (workingDir != null) {
            List<Path> chain = new ArrayList<>();
            for (Path p = workingDir.toAbsolutePath().normalize(); p != null; p = p.getParent()) chain.add(0, p);
            for (Path dir : chain) {
                candidates.add(dir.resolve("CLAUDE.md"));
                candidates.add(dir.resolve(".claude").resolve("CLAUDE.md"));
                candidates.add(dir.resolve("CLAUDE.local.md"));
            }
        }
        List<Path> found = new ArrayList<>();
        for (Path c : candidates) if (Files.isRegularFile(c) && !found.contains(c)) found.add(c);
        return found;
    }

    /**
     * The MEMORY.md index the CLI keeps for a project: {@code <home>/.claude/projects/<dir>/memory/MEMORY.md},
     * where {@code <dir>} is the working directory with every character that is not a letter or digit
     * replaced by {@code -}.
     *
     * @return the path; it may not exist
     */
    public static Path memoryIndex(Path workingDir, Path home) {
        String dir = workingDir.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
        return home.resolve(".claude").resolve("projects").resolve(dir).resolve("memory").resolve("MEMORY.md");
    }

    /** @return the files' contents, one after the other, or "" for a file that cannot be read */
    public static String readAll(List<Path> files) {
        StringBuilder b = new StringBuilder();
        for (Path f : files) {
            try {
                b.append(Files.readString(f));
            } catch (IOException | RuntimeException e) {
                // A file that cannot be read adds nothing: the CLI could not have read it either.
            }
        }
        return b.toString();
    }

    /**
     * The tool list of one MCP server as the model receives it: a JSON array of
     * {@code {name, description, inputSchema}}.
     *
     * @param tools one entry per tool: name, description, and the input schema as JSON text
     */
    public static String toolsJson(List<String[]> tools) {
        JSONArray array = new JSONArray();
        for (String[] t : tools) {
            JSONObject o = new JSONObject().put("name", t[0]).put("description", t[1]);
            try {
                o.put("inputSchema", new JSONObject(t[2]));
            } catch (JSONException e) {
                o.put("inputSchema", new JSONObject().put("type", "object"));
            }
            array.put(o);
        }
        return array.toString();
    }

    /**
     * The first request's input tokens, from the answer of {@code GET /api/sessions/{id}/tokens}
     * ({@code {"requests":[...],"turns":[{"turn","firstRequestTotal","growth"}]}}).
     *
     * @return {@code turns[0].firstRequestTotal}, or null when the answer has no first turn or no number there
     */
    public static Integer firstRequestTotal(String tokensJson) {
        if (tokensJson == null) return null;
        try {
            JSONArray turns = new JSONObject(tokensJson).optJSONArray("turns");
            if (turns == null || turns.isEmpty()) return null;
            JSONObject first = turns.optJSONObject(0);
            if (first == null || first.isNull("firstRequestTotal") || !first.has("firstRequestTotal")) return null;
            return first.getInt("firstRequestTotal");
        } catch (JSONException e) {
            return null;
        }
    }
}
