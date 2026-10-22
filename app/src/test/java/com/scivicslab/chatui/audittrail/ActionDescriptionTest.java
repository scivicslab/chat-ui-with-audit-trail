package com.scivicslab.chatui.audittrail;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.actor.ChatSessionIIAR;
import com.scivicslab.chatui.core.actor.PlanRunnerIIAR;
import com.scivicslab.chatui.core.actor.PlanWorkerIIAR;
import com.scivicslab.pojoactor.action.schema.ActionCatalog;
import com.scivicslab.pojoactor.action.schema.ActionManifest;
import com.scivicslab.pojoactor.action.schema.ActionSchemaRegistry;
import com.scivicslab.pojoactor.action.schema.ActionStepYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit 5: what the Actions tab shows for the plan's own steps and a conversation's actions
 * comes from the build's two generated artefacts — action-schemas/ from the argument records and the
 * module's action manifest from the Javadoc — combined per action, by class, without a running actor
 * (ActionCatalogWithJavadoc_260930_oo01).
 */
class ActionDescriptionTest {

    private static final ActionSchemaRegistry SCHEMAS = new ActionSchemaRegistry();
    private static final ActionManifest MANIFEST = new ActionManifest();

    @Test
    @DisplayName("this: the plan's own steps and the engine's, all @Action methods now")
    void planRunnerActions() {
        SortedSet<String> names = ActionCatalog.actionNamesOf(PlanRunnerIIAR.class);
        assertTrue(names.containsAll(List.of("askChat", "addWorker", "askWorker", "copyState", "checkState", "countUp",
                "takeNext", "finish", "reportFailure")), names.toString());
        assertTrue(names.containsAll(List.of("putJson", "appendJson", "onlyIf", "call", "apply")),
                "the JSON State and interpreter actions come with the superclasses: " + names);
    }

    @Test
    @DisplayName("this.askWorker: the first sentence, the argument positions, and the step the bundled YAML writes")
    void askWorker() {
        ObjectNode d = ActionCatalog.describe(PlanRunnerIIAR.class, "askWorker", SCHEMAS, MANIFEST);
        assertTrue(d.get("description").asText().startsWith("Hands a prompt to one worker slot"), d.toString());
        assertTrue(d.get("argument").get("description").asText().startsWith("[workerId, prompt]"), d.toString());
        assertTrue(d.get("example").asText().startsWith("- actor: this\n  method: askWorker\n  arguments: [\"judge\""), d.toString());
        assertEquals(d.get("example").asText(), ActionStepYaml.of("this", "askWorker", d));
    }

    @Test
    @DisplayName("worker.ask: reached through apply, and documented so")
    void workerAsk() {
        ObjectNode d = ActionCatalog.describe(PlanWorkerIIAR.class, "ask", SCHEMAS, MANIFEST);
        assertTrue(d.get("description").asText().startsWith("Sends a prompt to this slot's conversation"), d.toString());
        assertTrue(d.get("example").asText().contains("method: apply"), d.toString());
    }

    @Test
    @DisplayName("a conversation's sendPrompt: the record's schema with the @param prose on each field")
    void chatSendPrompt() {
        ObjectNode d = ActionCatalog.describe(ChatSessionIIAR.class, "sendPrompt", SCHEMAS, MANIFEST);
        assertEquals("string", d.get("schema").get("properties").get("prompt").get("type").asText(), d.toString());
        assertTrue(d.get("schema").get("properties").get("prompt").get("description").asText().startsWith("what to say"), d.toString());
        assertTrue(ActionStepYaml.of("project1/chat-02", "sendPrompt", d).startsWith("- actor: project1/chat-02\n  method: sendPrompt\n  arguments: {prompt: \"<string>\""));
    }
}
