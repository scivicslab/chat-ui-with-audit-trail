package com.scivicslab.chatui.core.actor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link SkillRegistry} — file parsing and the Agent Skills specification's
 * validation rules, with no external service ({@code SkillAndAgentsFile_260830_oo01}).
 */
class SkillRegistryTest {

    private static void writeSkill(Path root, String dirName, String content) throws IOException {
        Path dir = Files.createDirectories(root.resolve(dirName));
        Files.writeString(dir.resolve("SKILL.md"), content);
    }

    @Test
    void indexesAConformingSkillAndServesItsBody(@TempDir Path root) throws IOException {
        writeSkill(root, "doc-search", """
                ---
                name: doc-search
                description: "Search the docs. Use when a question is answerable from them."
                metadata:
                  version: "1.0"
                ---

                # Doc search

                Call /api/search.
                """);

        SkillRegistry registry = new SkillRegistry(List.of(root));

        assertEquals(List.of("doc-search"), registry.getSkillNames());
        assertEquals(List.of(), registry.getProblems());
        assertTrue(registry.catalogText().contains(
                "- doc-search: Search the docs. Use when a question is answerable from them."));

        String body = registry.bodyOf("doc-search");
        assertNotNull(body);
        assertTrue(body.contains(root.resolve("doc-search").toString()), "body states the directory");
        assertTrue(body.contains("Call /api/search."), "body carries the instructions");
        assertFalse(body.contains("metadata:"), "the frontmatter is not part of the body");
    }

    @Test
    void reportsSkillsItCannotIndexInsteadOfSkippingThemSilently(@TempDir Path root) throws IOException {
        writeSkill(root, "no-frontmatter", "# Just a document\n");
        writeSkill(root, "no-description", "---\nname: no-description\n---\n\nbody\n");
        writeSkill(root, "wrong-dir", "---\nname: other-name\ndescription: d\n---\n\nbody\n");
        writeSkill(root, "Bad-Name", "---\nname: Bad-Name\ndescription: d\n---\n\nbody\n");
        Files.createDirectories(root.resolve("not-a-skill-at-all"));

        SkillRegistry registry = new SkillRegistry(List.of(root));

        assertEquals(List.of(), registry.getSkillNames());
        assertEquals(4, registry.getProblems().size(),
                "one message per SKILL.md that could not be indexed, and none for the plain directory");
        assertNull(registry.bodyOf("no-description"));
    }

    @Test
    void theEarlierRootWinsAWholeNameCollision(@TempDir Path first, @TempDir Path second) throws IOException {
        writeSkill(first, "shared", "---\nname: shared\ndescription: the winning one\n---\n\nfirst body\n");
        writeSkill(second, "shared", "---\nname: shared\ndescription: the shadowed one\n---\n\nsecond body\n");

        SkillRegistry registry = new SkillRegistry(List.of(first, second));

        assertEquals(List.of("shared"), registry.getSkillNames());
        assertTrue(registry.bodyOf("shared").contains("first body"));
        assertEquals(1, registry.getProblems().size(), "the shadowed skill is reported, not hidden");
    }

    @Test
    void readsBlockScalarDescriptionsAndSkipsNestedKeys(@TempDir Path root) throws IOException {
        writeSkill(root, "folded", """
                ---
                name: folded
                description: |
                  First line of the description.
                  Second line.
                metadata:
                  author: someone
                  version: "2.0"
                ---

                body
                """);

        SkillRegistry registry = new SkillRegistry(List.of(root));

        assertEquals(List.of("folded"), registry.getSkillNames());
        assertTrue(registry.catalogText().contains("First line of the description."));
        assertFalse(registry.catalogText().contains("author"), "nested keys are not read as fields");
    }

    @Test
    void aMissingRootIsReportedRatherThanThrowing(@TempDir Path root) {
        SkillRegistry registry = new SkillRegistry(List.of(root.resolve("nowhere")));

        assertEquals(List.of(), registry.getSkillNames());
        assertEquals(1, registry.getProblems().size());
        assertEquals("", registry.catalogText());
    }
}
