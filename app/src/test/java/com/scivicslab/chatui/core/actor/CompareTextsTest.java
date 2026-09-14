package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checking that a rewrite broke nothing, by comparing rather than by asking
 * ({@code WhatAProgramCanDo_260915_oo01}).
 *
 * <p>A judge given the two texts and asked to list what had been added or altered answered ACCEPT
 * for all nine documents of a run, one of which had had a YAML block rewritten into something that
 * does not run and {@code OpenAlex} turned into {@code Open Alex}. Reading 21,000 characters and
 * reporting the differences is not a judgement — it is a comparison, and a program does it
 * exactly.</p>
 */
@DisplayName("PlanRunner — compareTexts")
class CompareTextsTest {

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("compare-texts-test");
        runner = new PlanRunner("plan", system, null);
        iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private String compare(String before, String after) {
        iiar.putJson("original", before);
        iiar.putJson("text", after);
        ActionResult result = runner.compareTexts("original", "text", "verdict");
        assertTrue(result.isSuccess(), "the comparison itself always succeeds: " + result.getResult());
        return iiar.getJsonString("verdict");
    }

    @Test
    void proseThatWasRewrittenIsNotAComplaint() {
        String before = "# 見本\n\n`chat-01` は 28030 番で動きます。\n\n```bash\ncurl http://localhost:28030/\n```\n";
        String after = "# 見本\n\n`chat-01` が使うポートは 28030 番である。\n\n```bash\ncurl http://localhost:28030/\n```\n";

        assertEquals("ACCEPT", compare(before, after));
    }

    @Test
    void aCodeBlockThatChangedIsReported() {
        String before = "# 見本\n\n```yaml\n- actor: loader\n  method: loadJar\n```\n";
        String after = "# 見本\n\n```yaml\n- actor: this\n  method: askWorker\n```\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("loadJar"), "says which block went missing: " + verdict);
    }

    @Test
    void aCodeBlockThatWasAddedIsReported() {
        String before = "# 見本\n\n本文です。\n";
        String after = "# 見本\n\n本文です。\n\n```bash\necho hello\n```\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("echo hello"), verdict);
    }

    /** OpenAlex became Open Alex in one run, and the judge did not notice. */
    @Test
    void anIdentifierThatWentMissingIsReported() {
        String before = "# 見本\n\n`OpenAlex` に聞きます。\n";
        String after = "# 見本\n\n`Open Alex` に聞きます。\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("OpenAlex"), verdict);
    }

    /** OpenAlex is a name whether or not it is in backquotes. */
    @Test
    void aNameInPlainProseThatWentMissingIsReported() {
        String before = "結果は OpenAlex の書誌 5 件だった。\n";
        String after = "結果は Open Alex の書誌 5 件だった。\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("OpenAlex"), verdict);
    }

    /** Ordinary words are not names: rewriting a sentence must not be reported. */
    @Test
    void ordinaryWordsAreNotTreatedAsNames() {
        String before = "This is the text. It says something.\n";
        String after = "The text says something else entirely.\n";

        assertEquals("ACCEPT", compare(before, after));
    }

    /** 28030 became "2 8030" in another run: the number is gone, and a new one appeared. */
    @Test
    void aNumberThatChangedIsReported() {
        String before = "ポートは 28030 とします。\n";
        String after = "ポートは 2 8030 とします。\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("28030"), verdict);
    }

    @Test
    void aNumberThatAppearedFromNowhereIsReported() {
        String before = "この節には数字がありません。\n";
        String after = "この節には数字がありません。ポートは 28030 です。\n";

        String verdict = compare(before, after);

        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("28030"), verdict);
    }

    /** A heading the section criterion renames is prose, not an identifier or a number. */
    @Test
    void renamingASectionIsNotAComplaint() {
        String before = "## 前提条件\n\nポート 28030 で動いていること。\n";
        String after = "## Problem Definition\n\n動いているかどうかが分からない。ポート 28030 で動いていること。\n";

        assertEquals("ACCEPT", compare(before, after));
    }

    @Test
    void nothingKeptUnderThoseNamesIsSaidPlainly() {
        ActionResult result = runner.compareTexts("missing", "alsoMissing", "verdict");

        assertTrue(!result.isSuccess(), result.getResult());
    }
}
