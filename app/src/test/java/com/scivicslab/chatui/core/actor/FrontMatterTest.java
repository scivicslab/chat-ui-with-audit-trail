package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping a document's front matter out of a plan's reach.
 *
 * <p>A plan that hands a document to a model and writes back what comes out can lose the front
 * matter: one run dropped the {@code ---} block whole, and with it the {@code id} other documents
 * are linked by. Nothing in a rewrite concerns those lines, so they are taken off before the text
 * is sent and put back when it is written ({@code UnattendedFileRuns_260913_oo01}).</p>
 */
@DisplayName("PlanRunner — front matter")
class FrontMatterTest {

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("front-matter-test");
        runner = new PlanRunner("plan", system, null);
        iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private static final String HEAD = """
            ---
            id: Tiny_260914_oo01
            title: 小さな見本
            ---
            """;
    private static final String BODY = """

            # 小さな見本

            本文です。
            """;

    private void put(String key, String value) {
        iiar.putJson(key, value);
    }

    @Test
    void aDocumentWithFrontMatterIsSplitAtItsClosingLine() {
        put("text", HEAD + BODY);

        ActionResult result = runner.takeFrontMatter("text", "head", "text");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(HEAD, iiar.getJsonString("head"));
        assertEquals(BODY, iiar.getJsonString("text"), "what is sent on holds no front matter");
        assertFalse(iiar.getJsonString("text").contains("id:"));
    }

    @Test
    void aDocumentWithoutFrontMatterIsLeftWhole() {
        put("text", BODY);

        ActionResult result = runner.takeFrontMatter("text", "head", "text");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("", iiar.getJsonString("head"));
        assertEquals(BODY, iiar.getJsonString("text"));
    }

    /** A line of dashes inside the text is not a closing line: only the block at the very top is. */
    @Test
    void aRuleLaterInTheTextIsNotMistakenForTheClosingLine() {
        String text = "# 見本\n\n本文です。\n\n---\n\n続きです。\n";
        put("text", text);

        runner.takeFrontMatter("text", "head", "text");

        assertEquals("", iiar.getJsonString("head"));
        assertEquals(text, iiar.getJsonString("text"));
    }

    @Test
    void whatWasTakenOffIsPutBackInFront() {
        put("head", HEAD);
        put("text", BODY);

        ActionResult result = runner.joinFrontMatter("head", "text", "whole");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals(HEAD + BODY, iiar.getJsonString("whole"));
    }

    @Test
    void nothingTakenOffMeansNothingPutBack() {
        put("head", "");
        put("text", BODY);

        runner.joinFrontMatter("head", "text", "whole");

        assertEquals(BODY, iiar.getJsonString("whole"));
    }

    @Test
    void theTwoStepsTogetherLeaveADocumentAsItWas() {
        String whole = HEAD + BODY;
        put("text", whole);

        runner.takeFrontMatter("text", "head", "text");
        runner.joinFrontMatter("head", "text", "again");

        assertEquals(whole, iiar.getJsonString("again"));
    }

    /**
     * A document has a blank line between its front matter and its first heading.
     *
     * <p>What comes back from a model does not always keep the blank line it was sent with, and
     * {@code ---} immediately followed by {@code #} is not how these documents are written. One
     * blank line is put between them, and never a second.</p>
     */
    @Test
    void oneBlankLineSitsBetweenTheFrontMatterAndTheText() {
        put("head", HEAD);
        put("text", "# 小さな見本\n\n本文です。\n");

        runner.joinFrontMatter("head", "text", "whole");

        assertEquals(HEAD + "\n# 小さな見本\n\n本文です。\n", iiar.getJsonString("whole"));
    }

    @Test
    void aBlankLineThatIsAlreadyThereIsNotDoubled() {
        put("head", HEAD);
        put("text", BODY);

        runner.joinFrontMatter("head", "text", "whole");

        assertEquals(HEAD + BODY, iiar.getJsonString("whole"));
    }

    @Test
    void aWorkflowCallsThemByName() {
        put("text", HEAD + BODY);

        assertTrue(iiar.callByActionName("takeFrontMatter", "[\"text\",\"head\",\"text\"]").isSuccess());
        assertTrue(iiar.callByActionName("joinFrontMatter", "[\"head\",\"text\",\"again\"]").isSuccess());
        assertEquals(HEAD + BODY, iiar.getJsonString("again"));
    }
}
