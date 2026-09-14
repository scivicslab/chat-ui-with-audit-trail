package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file a plan writes ends with a newline.
 *
 * <p>What a model answers with does not always end with one, and a text file that does not is not
 * how these documents are kept: {@code git diff} reports the last line as changed, and the next
 * line appended to it runs into it.</p>
 */
@DisplayName("PlanRunner — writeFile")
class WriteFileNewlineTest {

    @TempDir
    Path tempDir;

    private IIActorSystem system;
    private PlanRunner runner;
    private PlanRunnerIIAR iiar;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("write-file-test");
        runner = new PlanRunner("plan", system, null);
        iiar = new PlanRunnerIIAR("plan", runner, system);
        system.addIIActor(iiar);
        runner.setSelfActorRef(iiar);
        runner.setFileScope(new FileAccessScope(tempDir, java.util.List.of(tempDir)));
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    void aTextWithoutOneGetsOne() throws Exception {
        iiar.putJson("text", "# 見本\n\n本文です。");

        ActionResult result = runner.writeFile(tempDir.resolve("a.md").toString(), "text");

        assertTrue(result.isSuccess(), result.getResult());
        assertEquals("# 見本\n\n本文です。\n", Files.readString(tempDir.resolve("a.md")));
    }

    @Test
    void aTextThatAlreadyEndsWithOneIsNotGivenASecond() throws Exception {
        iiar.putJson("text", "# 見本\n");

        runner.writeFile(tempDir.resolve("b.md").toString(), "text");

        assertEquals("# 見本\n", Files.readString(tempDir.resolve("b.md")));
    }

    /** An empty text is written as it is: a file of one newline says something that was not asked. */
    @Test
    void anEmptyTextIsLeftEmpty() throws Exception {
        iiar.putJson("text", "");

        runner.writeFile(tempDir.resolve("c.md").toString(), "text");

        assertEquals("", Files.readString(tempDir.resolve("c.md")));
    }
}
