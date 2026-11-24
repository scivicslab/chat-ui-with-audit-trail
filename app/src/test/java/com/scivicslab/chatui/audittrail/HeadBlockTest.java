package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.audittrail.HeadBlock.Part;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of a conversation's head block, their estimate and the measured total beside it. */
class HeadBlockTest {

    @Test
    void estimateTokens_englishAndJson_divideBy3_5() {
        assertEquals(0, HeadBlock.estimateTokens(""));
        assertEquals(0, HeadBlock.estimateTokens(null));
        assertEquals(10, HeadBlock.estimateTokens("a".repeat(35)));
        assertEquals(11, HeadBlock.estimateTokens("a".repeat(36)));   // rounded up
    }

    @Test
    void estimateTokens_japaneseHeavyText_divideBy1_5() {
        assertEquals(20, HeadBlock.estimateTokens("あ".repeat(30)));
        // 30 percent non-ASCII is already Japanese-heavy; one character less is not.
        assertEquals(7, HeadBlock.estimateTokens("あ".repeat(3) + "a".repeat(7)));   // 10 chars / 1.5
        assertEquals(3, HeadBlock.estimateTokens("あ".repeat(2) + "a".repeat(8)));   // 10 chars / 3.5
    }

    @Test
    void measuredPart_countsCodePoints_notBytes() {
        Part p = HeadBlock.measuredPart("x", "日本語", HeadBlock.PERSON_EDITED_FILE);
        assertEquals(3, p.chars());
        assertTrue(p.measured());
        assertEquals(2, p.estimatedTokens());
    }

    @Test
    void unmeasuredPart_hasNoCharsAndNoTokens() {
        Part p = HeadBlock.unmeasuredPart("CLI built-in prompt and tools (not measurable here)", HeadBlock.FIXED_BY_THE_CLI);
        assertEquals(0, p.chars());
        assertEquals(0, p.estimatedTokens());
        assertFalse(p.measured());
    }

    @Test
    void body_sumsTheEstimates_andKeepsTheMeasuredTotal() {
        List<Part> parts = List.of(
                new Part("a", 35, 10, true, HeadBlock.PERSON_EDITED_FILE),
                new Part("b", 0, 0, false, HeadBlock.FIXED_BY_THE_CLI),
                new Part("c", 70, 20, true, HeadBlock.CONVERSATION_SETTING));
        HeadBlock.Body body = HeadBlock.body(parts, 41_900);
        assertEquals(30, body.estimatedTotal());
        assertEquals(41_900, body.measuredFirstRequestTotal());
        assertNull(HeadBlock.body(parts, null).measuredFirstRequestTotal());
    }

    @Test
    void claudeMdFiles_homeFirst_thenFromTheRootDownToTheWorkingDirectory(@TempDir Path root) throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path work = Files.createDirectories(root.resolve("work/sub/project"));
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/CLAUDE.md"), "user");
        Files.writeString(root.resolve("work/CLAUDE.md"), "outer");
        Files.writeString(work.resolve("CLAUDE.md"), "inner");
        Files.writeString(work.resolve("CLAUDE.local.md"), "local");
        Files.writeString(root.resolve("work/sub/README.md"), "not a CLAUDE.md");

        List<Path> found = HeadBlock.claudeMdFiles(work, home);

        assertEquals(List.of(home.resolve(".claude/CLAUDE.md"), root.resolve("work/CLAUDE.md"),
                work.resolve("CLAUDE.md"), work.resolve("CLAUDE.local.md")), found);
        assertEquals("userouterinnerlocal", HeadBlock.readAll(found));
    }

    @Test
    void claudeMdFiles_noWorkingDirectory_isOnlyTheUsersFile(@TempDir Path home) throws IOException {
        assertEquals(List.of(), HeadBlock.claudeMdFiles(null, home));
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/CLAUDE.md"), "user");
        assertEquals(List.of(home.resolve(".claude/CLAUDE.md")), HeadBlock.claudeMdFiles(null, home));
    }

    @Test
    void memoryIndex_nameTheDirectoryByReplacingEveryOtherCharacterWithADash() {
        Path index = HeadBlock.memoryIndex(Path.of("/home/devteam/works"), Path.of("/home/devteam"));
        assertEquals(Path.of("/home/devteam/.claude/projects/-home-devteam-works/memory/MEMORY.md"), index);
        assertEquals(Path.of("/h/.claude/projects/-a-b-c-d/memory/MEMORY.md"),
                HeadBlock.memoryIndex(Path.of("/a/b.c_d"), Path.of("/h")));
    }

    @Test
    void toolsJson_isTheArrayTheModelReceives() {
        String json = HeadBlock.toolsJson(List.<String[]>of(
                new String[] {"read", "Reads a file", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"},
                new String[] {"odd", "Schema that is not JSON", "not json"}));
        var array = new org.json.JSONArray(json);
        assertEquals(2, array.length());
        assertEquals("read", array.getJSONObject(0).getString("name"));
        assertEquals("string", array.getJSONObject(0).getJSONObject("inputSchema")
                .getJSONObject("properties").getJSONObject("path").getString("type"));
        assertEquals("object", array.getJSONObject(1).getJSONObject("inputSchema").getString("type"));
    }

    /** The answer of GET /api/sessions/{id}/tokens as the other half of the plan defines it (a stub, until it exists). */
    private static final String TOKENS = new JSONObject()
            .put("requests", new org.json.JSONArray().put(new JSONObject().put("turn", 1).put("step", 1)
                    .put("input", 3).put("cacheCreation", 41_000).put("cacheRead", 897).put("output", 20).put("total", 41_900)))
            .put("turns", new org.json.JSONArray()
                    .put(new JSONObject().put("turn", 1).put("firstRequestTotal", 41_900).put("growth", JSONObject.NULL))
                    .put(new JSONObject().put("turn", 2).put("firstRequestTotal", 42_500).put("growth", 600)))
            .toString();

    @Test
    void firstRequestTotal_isTheFirstTurnsFirstRequest() {
        assertEquals(41_900, HeadBlock.firstRequestTotal(TOKENS));
    }

    @Test
    void firstRequestTotal_isNullWhenTheAnswerHasNoNumber() {
        assertNull(HeadBlock.firstRequestTotal(null));
        assertNull(HeadBlock.firstRequestTotal("not json"));
        assertNull(HeadBlock.firstRequestTotal("{\"requests\":[],\"turns\":[]}"));
        assertNull(HeadBlock.firstRequestTotal("{\"turns\":[{\"turn\":1,\"firstRequestTotal\":null,\"growth\":null}]}"));
        assertNull(HeadBlock.firstRequestTotal("{\"error\":\"no such session\"}"));
    }
}
