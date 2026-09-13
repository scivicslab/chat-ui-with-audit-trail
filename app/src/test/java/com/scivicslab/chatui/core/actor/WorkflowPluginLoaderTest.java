package com.scivicslab.chatui.core.actor;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for which plugins a job's workflow may load
 * ({@code WorkflowPluginLoader_260913_oo01}).
 *
 * <p>Exercises the load-bearing path: a coordinate the instance was not started with is refused
 * before anything is read, whatever shape the workflow wrote its argument in, and the refusal says
 * what was asked for and what was allowed.</p>
 */
@Tag("WorkflowPluginLoader_260913_oo01")
class WorkflowPluginLoaderTest {

    private static final String ALLOWED = "com.scivicslab.turingworkflow.plugins:plugin-openalex:4.1.0";
    private static final String OTHER = "com.scivicslab.turingworkflow.plugins:plugin-ssh:4.1.0";

    private IIActorSystem system;
    private WorkflowPluginLoader loader;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("loader-test");
        loader = new WorkflowPluginLoader("loader", system, List.of(ALLOWED));
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void aPluginTheInstanceWasNotStartedWith_isRefused() {
        ActionResult result = loader.callByActionName("loadJar", "[\"" + OTHER + "\"]");

        assertFalse(result.isSuccess());
        assertTrue(result.getResult().contains("refused to load"), result.getResult());
        assertTrue(result.getResult().contains("plugin-ssh"), "says what was asked for: " + result.getResult());
        assertTrue(result.getResult().contains("plugin-openalex"), "and what was allowed: " + result.getResult());
    }

    @Test
    void theArgumentIsReadInEveryShapeAWorkflowWritesIt() {
        assertTrue(loader.allows(ALLOWED));
        assertFalse(loader.callByActionName("loadJar", OTHER).isSuccess(), "a bare string");
        assertFalse(loader.callByActionName("loadJar", "[\"" + OTHER + "\"]").isSuccess(), "a JSON array");
        assertFalse(loader.callByActionName("loadJar", "{\"jar\":\"" + OTHER + "\"}").isSuccess(),
                "a JSON object");
    }

    @Test
    void theCheckIgnoresSurroundingSpaceAndCase() {
        assertTrue(loader.allows("  " + ALLOWED.toUpperCase(java.util.Locale.ROOT) + "  "));
        assertFalse(loader.allows(""));
        assertFalse(loader.allows(null));
    }

    @Test
    void aLoaderAllowedNothing_refusesEverything() {
        WorkflowPluginLoader empty = new WorkflowPluginLoader("loader-empty", system, List.of());

        assertFalse(empty.allows(ALLOWED));
        assertFalse(empty.callByActionName("loadJar", ALLOWED).isSuccess());
    }
}
