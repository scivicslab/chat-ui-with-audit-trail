package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for which workflows a project sees and which of them it may edit.
 *
 * <p>Exercises the load-bearing path: the project's own files must come first and be editable, the
 * bundled ones must follow and not be, a project file must hide the bundled one of the same name,
 * the search must find a workflow by what it says and not only by its name, and no name may
 * reach outside the workflows directory.</p>
 */
@Tag("ProjectWorkflowCatalog_260911_oo01")
@Tag("ProjectWorkflowEditing_260911_oo01")
class ProjectWorkflowCatalogTest {

    private static Path workflowsDir(Path workingDir) throws Exception {
        Path dir = workingDir.resolve(ProjectWorkflowCatalog.SUBDIR);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void list_putsTheProjectsOwnFirst_thenTheBundled_andOnlyTheOwnAreEditable(@TempDir Path work)
            throws Exception {
        Path dir = workflowsDir(work);
        Files.writeString(dir.resolve("zz-mine.yaml"), "name: zz-mine\ndescription: Measures the queue.\nsteps: []\n");

        List<ProjectWorkflowCatalog.Entry> rows = new ProjectWorkflowCatalog(work).list(null);

        assertEquals("zz-mine", rows.get(0).name(), "the project's own file comes first even though it sorts last by name");
        assertEquals(ProjectWorkflowCatalog.ORIGIN_PROJECT, rows.get(0).origin());
        assertTrue(rows.get(0).editable());
        assertEquals("Measures the queue.", rows.get(0).description());
        assertTrue(rows.size() > 1, "the bundled workflows follow");
        for (ProjectWorkflowCatalog.Entry e : rows.subList(1, rows.size())) {
            assertEquals(ProjectWorkflowCatalog.ORIGIN_BUNDLED, e.origin(), e.name());
            assertFalse(e.editable(), e.name());
        }
    }

    @Test
    void list_withNoWorkingDirectory_listsOnlyTheBundled() {
        List<ProjectWorkflowCatalog.Entry> rows = new ProjectWorkflowCatalog(null).list(null);

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(e -> ProjectWorkflowCatalog.ORIGIN_BUNDLED.equals(e.origin())));
        assertTrue(rows.stream().anyMatch(e -> e.name().equals("parallel-workers-plan")));
    }

    @Test
    void aProjectFile_hidesTheBundledOneOfTheSameName(@TempDir Path work) throws Exception {
        Path dir = workflowsDir(work);
        Files.writeString(dir.resolve("parallel-workers-plan.yaml"), "name: mine\nsteps: []\n");

        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(work);
        List<ProjectWorkflowCatalog.Entry> rows = catalog.list(null);

        long count = rows.stream().filter(e -> e.name().equals("parallel-workers-plan")).count();
        assertEquals(1, count, "one row for that name, not two");
        assertEquals(ProjectWorkflowCatalog.ORIGIN_PROJECT,
                rows.stream().filter(e -> e.name().equals("parallel-workers-plan")).findFirst().get().origin());

        ProjectWorkflowCatalog.Document doc = catalog.read("parallel-workers-plan");
        assertNotNull(doc);
        assertEquals("name: mine\nsteps: []\n", doc.yaml());
        assertTrue(doc.editable());
    }

    @Test
    void list_search_matchesTheBodyNotOnlyTheName(@TempDir Path work) throws Exception {
        Path dir = workflowsDir(work);
        Files.writeString(dir.resolve("a.yaml"), "name: a\ndescription: |\n  Deploys html-saurus to the portal.\nsteps: []\n");
        Files.writeString(dir.resolve("b.yaml"), "name: b\ndescription: Nothing here.\nsteps: []\n");

        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(work);

        List<String> hits = catalog.list("HTML-SAURUS").stream().map(ProjectWorkflowCatalog.Entry::name).toList();
        assertTrue(hits.contains("a"), "found by a word in its description");
        assertFalse(hits.contains("b"));
        assertEquals("Deploys html-saurus to the portal.",
                catalog.list("a").stream().filter(e -> e.name().equals("a")).findFirst().get().description(),
                "the first line of a block description");
    }

    @Test
    void read_ofABundledWorkflow_isNotEditable() {
        ProjectWorkflowCatalog.Document doc = new ProjectWorkflowCatalog(null).read("parallel-workers-plan");

        assertNotNull(doc);
        assertEquals(ProjectWorkflowCatalog.ORIGIN_BUNDLED, doc.origin());
        assertFalse(doc.editable());
        assertTrue(doc.yaml().contains("addWorker"));
    }

    @Test
    void validate_acceptsABundledWorkflow_andRejectsWhatTheInterpreterCannotRead() {
        String real = new ProjectWorkflowCatalog(null).read("parallel-workers-plan").yaml();

        assertNull(ProjectWorkflowCatalog.validate(real), "a workflow that ships with this program must pass");
        assertNotNull(ProjectWorkflowCatalog.validate(""), "empty is refused");
        assertNotNull(ProjectWorkflowCatalog.validate("name: x\nsteps: []\n"), "no steps is refused");
        assertNotNull(ProjectWorkflowCatalog.validate("this: [is: not, valid yaml"), "unreadable text is refused");
        assertTrue(ProjectWorkflowCatalog.validate("this: [is: not, valid yaml").startsWith("not a workflow"),
                "the reason names the interpreter's judgement");
    }

    @Test
    void write_createsTheProjectsOwnFile_whichThenHidesTheBundledOne(@TempDir Path work) throws Exception {
        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(work);
        String yaml = "name: my-plan\ndescription: Mine.\nsteps:\n  - states: [\"0\", \"1\"]\n    label: only\n    actions: []\n";

        ProjectWorkflowCatalog.Document written = catalog.write("parallel-workers-plan", yaml);

        assertEquals(ProjectWorkflowCatalog.ORIGIN_PROJECT, written.origin());
        assertTrue(written.editable());
        assertTrue(Files.isRegularFile(work.resolve(ProjectWorkflowCatalog.SUBDIR).resolve("parallel-workers-plan.yaml")),
                "written under workflows/ in the working directory, creating the directory");
        ProjectWorkflowCatalog.Document back = catalog.read("parallel-workers-plan");
        assertEquals(yaml, back.yaml());
        assertEquals(ProjectWorkflowCatalog.ORIGIN_PROJECT, back.origin(), "the project's copy now hides the bundled one");
    }

    @Test
    void write_writesNothingThatCannotBeRun(@TempDir Path work) throws Exception {
        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(work);

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> catalog.write("broken", "steps: [\n"));
        assertTrue(e.getMessage().startsWith("not a workflow"), e.getMessage());
        assertFalse(Files.exists(work.resolve(ProjectWorkflowCatalog.SUBDIR).resolve("broken.yaml")), "nothing on disk");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> catalog.write("../escape", "name: x\nsteps:\n  - states: [\"0\",\"1\"]\n    actions: []\n"));
    }

    @Test
    void write_refusesWhenTheProjectHasNoWorkingDirectory() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectWorkflowCatalog(null).write("x", "name: x\nsteps:\n  - states: [\"0\",\"1\"]\n    actions: []\n"));
        assertTrue(e.getMessage().contains("working directory"), e.getMessage());
    }

    @Test
    void read_refusesAnythingThatIsNotAPlainBasename(@TempDir Path work) throws Exception {
        workflowsDir(work);
        Files.writeString(work.resolve("secret.yaml"), "not a workflow\n");
        ProjectWorkflowCatalog catalog = new ProjectWorkflowCatalog(work);

        assertNull(catalog.read("../secret"));
        assertNull(catalog.read("a/b"));
        assertNull(catalog.read(".hidden"));
        assertNull(catalog.read(""));
        assertNull(catalog.read(null));
        assertFalse(ProjectWorkflowCatalog.isSafeName("../x"));
        assertTrue(ProjectWorkflowCatalog.isSafeName("prompt-construction-default"));
    }
}
