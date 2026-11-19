package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.PersistedLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads one Claude Code conversation's history back from the transcript Claude Code keeps for its
 * session, for {@code get_chat_log} after a restart ({@code GetChatLogReadsPersistedHistory_261119_oo01}).
 *
 * <p>The session id is the {@code sessionId=} line of chat-ui's session file for the conversation;
 * the transcript is {@code <sessionId>.jsonl} in one of the per-working-directory subdirectories of
 * Claude Code's projects directory. Only the last {@code tailBytes} of it are read, so a transcript
 * of many megabytes is never parsed whole. Both files are only read.</p>
 *
 * <p>Of the transcript's lines, only {@code user} and {@code assistant} messages that Claude Code
 * did not insert itself ({@code isMeta}) become entries: a plain-text content, and each
 * {@code text}, {@code tool_use} and {@code tool_result} block of a list content. Thinking blocks
 * and lines that cannot be read are skipped.</p>
 */
public final class ClaudeTranscriptLog {

    /** The {@code source} every entry read here is shown with. */
    public static final String SOURCE = "history:transcript";

    /** How much of a transcript's end is read at most. */
    public static final long DEFAULT_TAIL_BYTES = 2L * 1024 * 1024;

    /** What Claude Code's session ids look like; anything else is not used to build a path. */
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9-]{1,100}");

    private final Path sessionFile;
    private final Path projectsDir;
    private final long tailBytes;

    /**
     * @param sessionFile the conversation's session file, {@code sessionId=...} on one of its lines
     * @param projectsDir the directory holding one subdirectory of transcripts per working directory
     * @param tailBytes   how much of the transcript's end to read at most
     */
    public ClaudeTranscriptLog(Path sessionFile, Path projectsDir, long tailBytes) {
        this.sessionFile = sessionFile;
        this.projectsDir = projectsDir;
        this.tailBytes = tailBytes;
    }

    /**
     * @param limit  how many entries at most
     * @param before epoch milliseconds; only lines stamped earlier
     * @return the newest entries, oldest first; never {@code null}, and an exception is never thrown
     */
    public PersistedLog read(int limit, long before) {
        try {
            if (!Files.isRegularFile(sessionFile)) {
                return PersistedLog.none(SOURCE, "no Claude Code session file for this conversation (" + sessionFile + ")");
            }
            String sessionId = sessionIdIn(sessionFile);
            if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
                return PersistedLog.none(SOURCE, "the session file " + sessionFile + " holds not a session id: " + sessionId);
            }
            Path transcript = find(sessionId + ".jsonl");
            if (transcript == null) {
                return PersistedLog.none(SOURCE, "Claude Code transcript " + sessionId + ".jsonl not found under " + projectsDir);
            }
            List<PersistedLog.Entry> all = new ArrayList<>();
            for (String line : tailLines(transcript, tailBytes)) {
                entriesOf(line, before, all);
            }
            int from = limit <= 0 ? all.size() : Math.max(0, all.size() - limit);
            return new PersistedLog(SOURCE, all.subList(from, all.size()), null);
        } catch (Exception e) {
            return PersistedLog.none(SOURCE, "the Claude Code transcript could not be read: " + e.getMessage());
        }
    }

    private static String sessionIdIn(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("sessionId=")) return line.substring("sessionId=".length()).trim();
        }
        return null;
    }

    /** @return the newest file of that name one level below the projects directory, or {@code null} */
    private Path find(String fileName) throws IOException {
        if (!Files.isDirectory(projectsDir)) return null;
        Path found = null;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(projectsDir, Files::isDirectory)) {
            for (Path d : dirs) {
                Path candidate = d.resolve(fileName);
                if (Files.isRegularFile(candidate) && (found == null
                        || Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(found)) > 0)) {
                    found = candidate;
                }
            }
        }
        return found;
    }

    /**
     * The whole lines in the last {@code maxBytes} of a file. When the read starts inside the file,
     * everything up to the first line break is dropped, since that line began before the read did;
     * cutting on a byte rather than a character is harmless for the same reason.
     */
    static List<String> tailLines(Path file, long maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            byte[] bytes = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(bytes);
            int from = 0;
            if (start > 0) {
                while (from < bytes.length && bytes[from] != '\n') from++;
                from++;
            }
            if (from >= bytes.length) return List.of();
            String text = new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isBlank()) lines.add(line);
            }
            return lines;
        }
    }

    private static void entriesOf(String line, long before, List<PersistedLog.Entry> out) {
        JSONObject json;
        try {
            json = new JSONObject(line);
        } catch (Exception e) {
            return;
        }
        String type = json.optString("type", "");
        if (!type.equals("user") && !type.equals("assistant")) return;
        if (json.optBoolean("isMeta", false)) return;
        long time;
        try {
            time = Instant.parse(json.optString("timestamp", "")).toEpochMilli();
        } catch (Exception e) {
            return;
        }
        if (time >= before) return;
        JSONObject message = json.optJSONObject("message");
        if (message == null) return;
        Object content = message.opt("content");
        if (content instanceof String s) {
            out.add(new PersistedLog.Entry(time, type, s));
        } else if (content instanceof JSONArray blocks) {
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject b = blocks.optJSONObject(i);
                if (b == null) continue;
                switch (b.optString("type", "")) {
                    case "text" -> out.add(new PersistedLog.Entry(time, type, b.optString("text", "")));
                    case "tool_use" -> out.add(new PersistedLog.Entry(time, "tool_use",
                            b.optString("name", "") + " " + String.valueOf(b.opt("input"))));
                    case "tool_result" -> out.add(new PersistedLog.Entry(time, "tool_result", resultText(b.opt("content"))));
                    default -> { }
                }
            }
        }
    }

    private static String resultText(Object content) {
        if (content instanceof JSONArray parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                if (p != null && p.has("text")) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(p.optString("text"));
                }
            }
            return sb.toString();
        }
        return content == null ? "" : String.valueOf(content);
    }
}
