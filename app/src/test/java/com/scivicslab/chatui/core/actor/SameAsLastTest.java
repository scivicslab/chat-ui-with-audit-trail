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
 * Telling "not fixed yet" from "the two of them do not agree"
 * ({@code WhenTheTwoRolesDoNotAgree_260915_oo01}).
 *
 * <p>A run spent its whole allowance on one criterion: the fixer split the section as it was told
 * to, and the judge answered with the same complaint word for word, three times. The fixer was
 * working and the judge was not moved — they were reading the rule differently, and no number of
 * rounds was going to settle it. A verdict identical to the last one is that, and is worth
 * stopping on.</p>
 */
@DisplayName("PlanRunner — sameAsLast")
class SameAsLastTest {

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("same-as-last-test");
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
    void theFirstVerdictIsNeverTheSameAsTheLast() {
        iiar.putJson("verdict", "REVISE: 軸が混ざっています");

        ActionResult result = runner.sameAsLast("verdict", "last-verdict");

        assertFalse(result.isSuccess(), "nothing to compare with yet: " + result.getResult());
        assertEquals("REVISE: 軸が混ざっています", iiar.getJsonString("last-verdict"),
                "and it is remembered for the next round");
    }

    @Test
    void aVerdictThatSaysSomethingNewLetsTheFixingGoOn() {
        iiar.putJson("verdict", "REVISE: 軸が混ざっています");
        runner.sameAsLast("verdict", "last-verdict");
        iiar.putJson("verdict", "REVISE: 主語がありません");

        assertFalse(runner.sameAsLast("verdict", "last-verdict").isSuccess());
        assertEquals("REVISE: 主語がありません", iiar.getJsonString("last-verdict"));
    }

    @Test
    void thesameVerdictTwiceIsWhereItStops() {
        iiar.putJson("verdict", "REVISE: 軸が混ざっています");
        runner.sameAsLast("verdict", "last-verdict");
        iiar.putJson("verdict", "REVISE: 軸が混ざっています");

        ActionResult result = runner.sameAsLast("verdict", "last-verdict");

        assertTrue(result.isSuccess(), "the two of them are not converging");
        assertTrue(result.getResult().contains("same"), result.getResult());
    }

    /** Whitespace a model adds or drops is not a new complaint. */
    @Test
    void theSameComplaintSpacedDifferentlyIsStillTheSame() {
        iiar.putJson("verdict", "REVISE: 軸が混ざっています");
        runner.sameAsLast("verdict", "last-verdict");
        iiar.putJson("verdict", "REVISE:  軸が混ざっています\n");

        assertTrue(runner.sameAsLast("verdict", "last-verdict").isSuccess());
    }

    @Test
    void eachCriterionRemembersItsOwn() {
        iiar.putJson("verdict", "REVISE: A");
        runner.sameAsLast("verdict", "last-verdict-1");
        iiar.putJson("verdict", "REVISE: B");
        runner.sameAsLast("verdict", "last-verdict-2");
        iiar.putJson("verdict", "REVISE: A");

        assertTrue(runner.sameAsLast("verdict", "last-verdict-1").isSuccess(),
                "criterion 1 has seen this one before");
        iiar.putJson("verdict", "REVISE: A");
        assertFalse(runner.sameAsLast("verdict", "last-verdict-2").isSuccess(),
                "criterion 2 has not");
    }
}
