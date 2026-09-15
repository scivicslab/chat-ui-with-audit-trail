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
 * Turning a list a plan built up into something a person reads.
 *
 * <p>A run collects what it did with {@code appendJson}, which makes a list. Read back with
 * {@code getString} a list answers the empty string, so the report a run printed at the end had
 * two empty sections under its headings — the same shape of mistake {@code finish} had.</p>
 */
@DisplayName("PlanRunner — joinLines")
class JoinLinesTest {

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("join-lines-test");
        runner = new PlanRunner("plan", system, null);
        iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void aListBecomesOneLineEach() {
        iiar.callByActionName("appendJson", "{\"path\":\"done\",\"value\":\"written: a.md\"}");
        iiar.callByActionName("appendJson", "{\"path\":\"done\",\"value\":\"written: b.md\"}");

        ActionResult result = runner.joinLines("done", "doneText");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("written: a.md\nwritten: b.md", iiar.getJsonString("doneText"));
    }

    /** An empty section says so rather than leaving a heading with nothing under it. */
    @Test
    void anEmptyListSaysThereIsNothing() {
        runner.joinLines("neverWritten", "text");

        assertEquals("なし", iiar.getJsonString("text"));
    }

    @Test
    void aPieceOfTextIsPassedThroughAsItIs() {
        iiar.putJson("one", "written: a.md");

        runner.joinLines("one", "text");

        assertEquals("written: a.md", iiar.getJsonString("text"));
    }
}
