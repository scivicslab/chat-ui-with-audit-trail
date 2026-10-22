package com.scivicslab.chatui.core.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code files} actor: a job's workflow reads, writes and lists files through it, within the instance's range.
 */
@DisplayName("files: ジョブのワークフローがファイルを読み書きする口")
class ScopedFilesTest {

    @TempDir Path dir;
    @TempDir Path outside;
    private IIActorSystem system;
    private ScopedFilesIIAR files;
    private PlanRunnerIIAR plan;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("scoped-files-test");
        files = new ScopedFilesIIAR("files", new ScopedFiles(new FileAccessScope(dir, List.of(dir))), system);
        system.addIIActor(files);
        PlanRunner runner = new PlanRunner("plan", system, null);
        plan = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(plan);
        runner.setSelfActorRef(plan);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private ActionResult write(Path file, String text) {
        return files.callByActionName("write", new JSONObject().put("path", file.toString()).put("text", text).toString());
    }

    @Test
    @DisplayName("書く本文が改行で終わっていなければ1つ足し、空の本文は空のまま書く")
    void aWrittenTextEndsWithOneNewline() throws Exception {
        assertTrue(write(dir.resolve("a.md"), "# 見本\n\n本文です。").isSuccess());
        write(dir.resolve("b.md"), "# 見本\n");
        write(dir.resolve("sub/c.md"), "");
        assertEquals("# 見本\n\n本文です。\n", Files.readString(dir.resolve("a.md")));
        assertEquals("# 見本\n", Files.readString(dir.resolve("b.md")));
        assertEquals("", Files.readString(dir.resolve("sub/c.md")));
    }

    @Test
    @DisplayName("範囲の外は読めず書けない")
    void outsideTheRangeIsRefused() throws Exception {
        Files.writeString(outside.resolve("secret.md"), "x");
        ActionResult read = files.callByActionName("read", new JSONObject().put("path", outside.resolve("secret.md").toString()).toString());
        assertFalse(read.isSuccess());
        assertTrue(read.getResult().contains("outside the readable range"), read.getResult());
        ActionResult wrote = write(outside.resolve("new.md"), "x");
        assertFalse(wrote.isSuccess());
        assertTrue(wrote.getResult().contains("outside the writable range"), wrote.getResult());
        assertFalse(Files.exists(outside.resolve("new.md")));
        ActionResult listed = files.callByActionName("list", new JSONObject().put("dir", outside.toString()).toString());
        assertFalse(listed.isSuccess());
    }

    @Test
    @DisplayName("ワークフローは一覧を取り、読んだ本文を状態に置き、状態から書く")
    void aWorkflowListsReadsKeepsAndWrites() throws Exception {
        Files.createDirectories(dir.resolve("in/d1"));
        Files.writeString(dir.resolve("in/d1/one.md"), "first\n");
        Files.writeString(dir.resolve("in/skip.txt"), "not listed\n");
        String yaml = """
                name: files-round-trip
                steps:
                  - states: ["0", "1"]
                    actions:
                      - actor: files
                        method: list
                        arguments: {dir: 'jexl:state.getString("in")', suffix: .md, depth: "2"}
                      - actor: this
                        method: putJson
                        arguments: {path: found, value: "jexl:result"}
                      - actor: files
                        method: read
                        arguments: {path: 'jexl:state.getString("found")'}
                      - actor: this
                        method: putJson
                        arguments: {path: text, value: "jexl:result"}
                      - actor: files
                        method: write
                        arguments: {path: 'jexl:state.getString("out")', text: 'jexl:state.getString("text") + "second"'}
                  - states: ["1", "end"]
                    actions:
                      - actor: this
                        method: finish
                        arguments: ["text"]
                """;
        plan.callByActionName("putJson", new JSONObject().put("path", "in").put("value", dir.resolve("in").toString()).toString());
        plan.callByActionName("putJson", new JSONObject().put("path", "out").put("value", dir.resolve("out/one.md").toString()).toString());
        CompletableFuture<String> done = new CompletableFuture<>();
        plan.tellNow(interp -> {
            PlanRunner r = (PlanRunner) interp;
            r.setDone(done);
            r.reset();
            r.readYaml(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            r.runUntilEnd();
            r.complete("(plan ended without finish)");
        }).join();
        assertEquals("first\n", done.get(30, TimeUnit.SECONDS));
        assertEquals(dir.resolve("in/d1/one.md").toRealPath().toString(), plan.getJsonString("found"));
        assertEquals("first\nsecond\n", Files.readString(dir.resolve("out/one.md")));
    }
}
