package com.scivicslab.chatui.audittrail;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the inputs a workflow asks a run to give it.
 *
 * <p>Exercises the load-bearing path: a declared {@code params} section is read as declared and in
 * order, a workflow without one falls back to the <code>${key}</code> of its body, and
 * <code>${result}</code> never becomes an input.</p>
 */
@Tag("JobParameterForm_260913_oo01")
class ProjectWorkflowParamsTest {

    private static final String DECLARED = """
            name: openalex-search
            params:
              query:
                label: "検索語"
                description: "OpenAlex に投げる語"
                type: text
              perPage:
                description: "取得件数"
                type: int
                default: 10
              sort:
                type: select
                options: ["citations", "newest"]
                default: citations
                required: true
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: openalex
                    method: searchWorksTopK
                    arguments: "${query} ${perPage} ${sort} ${result}"
            """;

    private static final String UNDECLARED = """
            name: arxiv-translate
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: out
                    method: error
                    arguments: "translating ${pdf} on ${ocr.device}"
              - states: ["1", "end"]
                actions:
                  - actor: out
                    method: print
                    arguments: "${result} of ${pdf}"
            """;

    @Test
    void declaredParams_areReadInOrder_withTheirSixFields() {
        List<ProjectWorkflowCatalog.ParamSpec> params = ProjectWorkflowCatalog.paramsOf(DECLARED);

        assertEquals(List.of("query", "perPage", "sort"), params.stream().map(ProjectWorkflowCatalog.ParamSpec::key).toList(),
                "declared order is the order of the form");

        ProjectWorkflowCatalog.ParamSpec query = params.get(0);
        assertEquals("検索語", query.label());
        assertEquals("OpenAlex に投げる語", query.description());
        assertEquals("text", query.type());
        assertTrue(query.required(), "no default, so a run must give one");
        assertNull(query.defaultValue());
        assertTrue(query.options().isEmpty());

        ProjectWorkflowCatalog.ParamSpec perPage = params.get(1);
        assertNull(perPage.label(), "no label declared: the key stands in for it");
        assertEquals("int", perPage.type());
        assertEquals("10", perPage.defaultValue());
        assertFalse(perPage.required(), "a default makes it optional unless declared otherwise");

        ProjectWorkflowCatalog.ParamSpec sort = params.get(2);
        assertEquals("select", sort.type());
        assertEquals(List.of("citations", "newest"), sort.options());
        assertEquals("citations", sort.defaultValue());
        assertTrue(sort.required(), "declared required, even with a default");
    }

    @Test
    void declaredParams_winOverTheBodysPlaceholders() {
        List<String> keys = ProjectWorkflowCatalog.paramsOf(DECLARED).stream()
                .map(ProjectWorkflowCatalog.ParamSpec::key).toList();

        assertFalse(keys.contains("result"), "the previous transition's result is not an input");
        assertEquals(3, keys.size(), "the declaration is read, the body is not scanned");
    }

    @Test
    void withoutADeclaration_theBodysPlaceholdersAreTheInputs() {
        List<ProjectWorkflowCatalog.ParamSpec> params = ProjectWorkflowCatalog.paramsOf(UNDECLARED);

        assertEquals(List.of("pdf", "ocr.device"), params.stream().map(ProjectWorkflowCatalog.ParamSpec::key).toList(),
                "in the order they appear, each one once, without ${result}");
        for (ProjectWorkflowCatalog.ParamSpec p : params) {
            assertEquals("text", p.type());
            assertTrue(p.required());
            assertNull(p.defaultValue());
            assertEquals("", p.description());
        }
    }

    @Test
    void withoutADeclaration_theStateReadsAreTheInputsToo() {
        String yaml = """
                name: search
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: openalex
                        method: searchWorks
                        arguments: "jexl:state.get('query')"
                      - actor: out
                        method: print
                        arguments: "jexl:state.getInt(\"perPage\", 10)"
                """;

        assertEquals(List.of("query", "perPage"), ProjectWorkflowCatalog.paramsOf(yaml).stream()
                .map(ProjectWorkflowCatalog.ParamSpec::key).toList());
    }

    @Test
    void aWorkflowWithNeither_hasNoInputs() {
        assertTrue(ProjectWorkflowCatalog.paramsOf("name: hello\nsteps: []\n").isEmpty());
        assertTrue(ProjectWorkflowCatalog.paramsOf("").isEmpty());
        assertTrue(ProjectWorkflowCatalog.paramsOf(null).isEmpty());
    }

    @Test
    void unreadableText_hasNoInputsRatherThanFailing() {
        assertTrue(ProjectWorkflowCatalog.paramsOf("name: [unclosed\n").isEmpty());
    }
}
