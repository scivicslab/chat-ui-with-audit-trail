package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E check of {@code JobParameterForm_260913_oo01} against a running instance: the Run tab draws
 * one field per input the open workflow declares, with the declared label, description, default and
 * kind of field; Run refuses to start while a required field is empty; and a filled form starts a
 * job whose first log line says what it ran with.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01). Creates its own
 * project, points it at {@code /tmp/jpf-e2e} and writes its own workflow there, so it needs nothing
 * of the instance beyond being up. No LLM call is made here.</p>
 */
public class JobParameterFormE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28014/");

    private static final Path WORKING_DIR = Path.of("/tmp/jpf-e2e");

    private static final String WORKFLOW = """
            name: e2e-params
            description: Reports the values the run was given.
            params:
              query:
                label: "Search terms"
                description: "What to look for"
                type: text
              perPage:
                description: "How many to take"
                type: int
                default: 10
              sort:
                type: select
                options: ["citations", "newest"]
                default: citations
            steps:
              - states: ["0", "1"]
                label: say-done
                actions:
                  - actor: this
                    method: finish
                    arguments: []
                    execution: direct
            """;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        new JobParameterFormE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() throws Exception {
        System.out.println("--- JobParameterFormE2E against " + BASE_URL + " ---");
        Files.createDirectories(WORKING_DIR);
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForSelector("#right-tab-bar");

                // A project of this test's own, pointed at a directory it writes the workflow into.
                String projectId = page.evaluate(
                        "async () => (await (await fetch('api/projects', {method:'POST'})).json()).projectId")
                        .toString();
                page.evaluate("async (p) => fetch('api/projects/' + p + '/working-dir',"
                        + " {method:'POST', headers:{'Content-Type':'text/plain'}, body:'"
                        + WORKING_DIR + "'})", projectId);
                page.evaluate("async (a) => fetch('api/projects/' + a[0] + '/workflows/e2e-params',"
                        + " {method:'PUT', headers:{'Content-Type':'text/plain'}, body:a[1]})",
                        new Object[]{projectId, WORKFLOW});

                // Into that project's perspective, with its workflow open in the centre pane.
                page.evaluate("(p) => { localStorage.setItem('chat-ui-perspective','project');"
                        + " localStorage.setItem('chat-ui-perspective-project', p); }", projectId);
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForSelector("#project-wf-list .pwf-row");
                page.click("#project-wf-list .pwf-row:has-text('e2e-params')");
                page.waitForTimeout(500);
                page.click("#right-tab-bar .rtab-btn[data-tab='jobrun']");
                page.waitForSelector("#jobrun-params .jobrun-param");

                int fields = (int) page.evaluate(
                        "() => document.querySelectorAll('#jobrun-params .jobrun-param').length");
                check(fields == 3, "one field per declared input (found " + fields + ")");

                String firstLabel = page.evaluate(
                        "() => document.querySelector('#jobrun-params .jobrun-param-key').textContent").toString();
                check(firstLabel.startsWith("Search terms"), "the declared label names the field: " + firstLabel);
                check(firstLabel.contains("*"), "a required input is marked: " + firstLabel);

                String desc = page.evaluate(
                        "() => document.querySelector('#jobrun-params .jobrun-param-desc').textContent").toString();
                check("What to look for".equals(desc), "the declared description is shown: " + desc);

                String perPage = page.evaluate("() => document.querySelector("
                        + "'#jobrun-params .jobrun-param[data-param-key=\"perPage\"] .jobrun-param-input').value").toString();
                check("10".equals(perPage), "a declared default fills the field: " + perPage);

                String sortTag = page.evaluate("() => document.querySelector("
                        + "'#jobrun-params .jobrun-param[data-param-key=\"sort\"] .jobrun-param-input').tagName").toString();
                check("SELECT".equals(sortTag), "a select input is drawn as a dropdown: " + sortTag);

                // Required and empty: Run says so and starts nothing.
                page.click("#jobrun-run");
                page.waitForTimeout(400);
                String status = page.evaluate("() => document.getElementById('jobrun-status').textContent").toString();
                check(status.contains("query is required"), "an empty required field stops the run: " + status);
                boolean marked = (boolean) page.evaluate("() => !!document.querySelector("
                        + "'#jobrun-params .jobrun-param[data-param-key=\"query\"].jobrun-param-missing')");
                check(marked, "and the field it stopped on is marked");

                // Filled: the job starts, and its log says what it ran with.
                page.fill("#jobrun-params .jobrun-param[data-param-key='query'] .jobrun-param-input",
                        "protein folding");
                page.click("#jobrun-run");
                page.waitForTimeout(800);
                String started = page.evaluate("() => document.getElementById('jobrun-status').textContent").toString();
                check(started.startsWith("started "), "a filled form starts a job: " + started);

                String log = page.evaluate("async (p) => (await (await fetch('api/projects/' + p"
                        + " + '/jobs/job-01/log')).json()).map(e => e.data).join('\\n')", projectId).toString();
                check(log.contains("with query=protein folding"), "the first log line says what it ran with: " + log);
                check(log.contains("perPage=10") && log.contains("sort=citations"),
                        "including the defaults the form filled in: " + log);
            } finally {
                browser.close();
            }
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (ok) passed++; else failed++;
    }
}
