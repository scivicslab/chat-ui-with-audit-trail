package com.scivicslab.chatui.core.actor;

import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a plan hands back when it finishes on a value it built up
 * ({@code RemovingActorsFromAWorkflow_260913_oo01}).
 *
 * <p>A plan that collects things as it goes keeps them as a list — that is what {@code appendJson}
 * is for. Read back as text, a list answers the empty string, and the job reported "(plan finished
 * with no result)" for a run that had removed two actors.</p>
 */
class PlanFinishTest {

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;
    private CompletableFuture<String> done;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("plan-finish-test");
        runner = new PlanRunner("plan", system, null);
        iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);
        done = new CompletableFuture<>();
        runner.setDone(done);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void aListThePlanBuiltUpIsWhatItHandsBack() throws Exception {
        iiar.callByActionName("appendJson", "{\"path\":\"removed\",\"value\":\"project1/chat-junk-1\"}");
        iiar.callByActionName("appendJson", "{\"path\":\"removed\",\"value\":\"project1/chat-junk-2\"}");

        runner.finish("removed");

        assertEquals("[\"project1/chat-junk-1\",\"project1/chat-junk-2\"]", done.get());
    }

    @Test
    void aPieceOfTextIsHandedBackAsItIs() throws Exception {
        iiar.callByActionName("putJson", "{\"path\":\"report\",\"value\":\"two actors removed\"}");

        runner.finish("report");

        assertEquals("two actors removed", done.get());
    }

    @Test
    void nothingUnderThatNameIsSaidPlainly() throws Exception {
        runner.finish("neverWritten");

        assertEquals("(plan finished with no result)", done.get());
    }
}
