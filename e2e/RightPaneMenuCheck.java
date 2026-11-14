import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

/**
 * Browser check of the right pane's menu (#right-tab-select), which replaced the row of tabs
 * (ProjectPerspective_260911_oo01). Run against a throwaway instance: it creates a project, gives it a
 * working directory on the instance's machine and a one-step workflow, and runs one job. No LLM call is
 * made. Java Playwright, outside the Maven build: a main() against a running instance, as the testing
 * standard has it.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/RightPaneMenuCheck.java \
 *     http://localhost:28976 /tmp/rpm-e2e
 * </pre>
 *
 * The second argument is a directory that exists on the instance's machine. It exits 0 when every check
 * passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class RightPaneMenuCheck {

    static final List<String> CHAT_ENTRIES = List.of("Sessions", "System Log", "Agent Loop", "Log Search", "Actions");
    static final List<String> PROJECT_ENTRIES =
            List.of("Project Property", "Run", "Job Log", "Batch Jobs", "Method Search", "Actions");

    static final String WORKFLOW = """
            name: e2e-menu
            description: Finishes at once, so the project has a job to list.
            steps:
              - states: ["0", "1"]
                label: say-done
                actions:
                  - actor: this
                    method: finish
                    arguments: []
                    execution: direct
            """;

    static String base = "http://localhost:28976";
    static String workingDir = "/tmp/rpm-e2e";
    static final HttpClient http = HttpClient.newHttpClient();
    static int failures = 0;

    static String rest(String method, String path, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", contentType);
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    /** The labels of the entries the menu offers now (not hidden). */
    @SuppressWarnings("unchecked")
    static List<String> offered(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".filter(o => !o.hidden).map(o => o.textContent.trim())");
    }

    static String selected(Page page) {
        return (String) page.evaluate("() => { const s = document.getElementById('right-tab-select');"
                + " return s.options[s.selectedIndex].textContent.trim(); }");
    }

    /** The ids of the right-pane sections that are visible. */
    @SuppressWarnings("unchecked")
    static List<String> shown(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('.rtab-content'))"
                + ".filter(c => c.offsetParent !== null).map(c => c.id)");
    }

    /** The text of the description line under the menu. */
    static String desc(Page page) {
        return page.textContent("#right-tab-desc").trim();
    }

    /** The tooltip (title) of the offered entry with this label. */
    static String titleOf(Page page, String label) {
        return (String) page.evaluate("(l) => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".find(o => !o.hidden && o.textContent.trim() === l).title", label);
    }

    /** The description line shows the entry's own description, the same text as the entry's tooltip. */
    static void describes(Page page, String label, String when) {
        String d = desc(page);
        check(!d.isEmpty() && d.equals(titleOf(page, label)) && d.length() > 40,
                when + ": the line under the menu describes \"" + label + "\": " + d);
    }

    /** Chooses the offered entry with this label, the way a person does in the menu. */
    static void choose(Page page, String label) {
        int index = ((Number) page.evaluate("(l) => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".findIndex(o => !o.hidden && o.textContent.trim() === l)", label)).intValue();
        page.selectOption("#right-tab-select", new com.microsoft.playwright.options.SelectOption().setIndex(index));
        page.waitForTimeout(300);
    }

    static String valueOf(Page page, String label) {
        return (String) page.evaluate("(l) => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".find(o => !o.hidden && o.textContent.trim() === l).value", label);
    }

    /** Clicks the actor-tree name whose tooltip starts with {@code title}. */
    static void clickTreeNode(Page page, String title) {
        page.waitForFunction("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".some(e => (e.title || '').startsWith(t))", title);
        page.evaluate("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".find(e => (e.title || '').startsWith(t)).click()", title);
        page.waitForTimeout(500);
    }

    static void everyEntryOpensItsSection(Page page, List<String> entries) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String label : entries) {
            choose(page, label);
            String id = "tab-" + valueOf(page, label);
            check(selected(page).equals(label) && shown(page).equals(List.of(id)),
                    "choosing \"" + label + "\" shows only #" + id + " (shown: " + shown(page) + ")");
            describes(page, label, "choosing \"" + label + "\"");
            seen.add(desc(page));
        }
        check(seen.size() == entries.size(), "every entry of this view has a description of its own: " + seen.size());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        if (args.length > 1) workingDir = args[1];

        // A project of this check's own, with a workflow that finishes at once, and one job of it.
        String made = rest("POST", "/api/projects", "application/json", null);
        String projectId = made.replaceAll(".*\"projectId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        rest("POST", "/api/projects/" + projectId + "/working-dir", "text/plain", workingDir);
        rest("PUT", "/api/projects/" + projectId + "/workflows/e2e-menu", "text/plain", WORKFLOW);
        String job = rest("POST", "/api/projects/" + projectId + "/jobs", "application/json", "{\"workflow\":\"e2e-menu\"}");
        String jobId = job.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        System.out.println("project " + projectId + ", job " + job);

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            // Narrow: the width at which the row of tabs used to crowd.
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1100, 800));
            Page page = ctx.newPage();
            page.onPageError(e -> check(false, "page error: " + e));
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.evaluate("() => { localStorage.setItem('chat-ui-perspective', 'chat'); }");
            page.reload();
            page.waitForSelector("#right-tab-select");
            page.waitForTimeout(1000);

            // The chat view: the entries, the first one shown, every entry opens its section.
            check(page.querySelectorAll(".rtab-btn").isEmpty(), "no tab buttons are left");
            check(offered(page).equals(CHAT_ENTRIES), "chat view offers " + CHAT_ENTRIES + ": " + offered(page));
            check(selected(page).equals("Sessions") && shown(page).equals(List.of("tab-logdb")),
                    "the chat view opens on Sessions: " + selected(page) + " " + shown(page));
            describes(page, "Sessions", "on load");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/menu-chat-sessions.png")));
            everyEntryOpensItsSection(page, CHAT_ENTRIES);

            // The tab chosen was never remembered; a reload opens the first entry again, as the tabs did.
            choose(page, "Log Search");
            page.reload();
            page.waitForSelector("#right-tab-select");
            page.waitForTimeout(1000);
            check(selected(page).equals("Sessions") && shown(page).equals(List.of("tab-logdb")),
                    "after a reload the chat view opens on Sessions, as before: " + selected(page));

            // Opened from elsewhere: outputMultiplexer in the actor tree opens Log Search.
            clickTreeNode(page, "Search every recorded conversation");
            check(selected(page).equals("Log Search") && shown(page).equals(List.of("tab-logsearch")),
                    "clicking outputMultiplexer opens Log Search: " + selected(page) + " " + shown(page));
            describes(page, "Log Search", "after clicking outputMultiplexer");

            // The project view: a Project node switches the perspective; its first entry is shown.
            clickTreeNode(page, "Show project " + projectId);
            check(offered(page).equals(PROJECT_ENTRIES), "project view offers " + PROJECT_ENTRIES + ": " + offered(page));
            check(selected(page).equals("Project Property") && shown(page).equals(List.of("tab-projectprop")),
                    "the project view opens on Project Property: " + selected(page) + " " + shown(page));
            describes(page, "Project Property", "after switching to the project view");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/menu-project-property.png")));
            everyEntryOpensItsSection(page, PROJECT_ENTRIES);

            // Opened from elsewhere: a job's Log button in Batch Jobs opens Job Log on that job.
            choose(page, "Batch Jobs");
            page.waitForSelector("#tab-jobs .job-actions button:text-is('Log')");
            page.click("#tab-jobs .job-actions button:text-is('Log')");
            page.waitForTimeout(500);
            String jobShown = page.textContent("#joblog-job").trim();
            check(selected(page).equals("Job Log") && shown(page).equals(List.of("tab-joblog")) && jobShown.equals(jobId),
                    "a job's Log button opens Job Log on " + jobId + ": " + selected(page) + " " + shown(page) + " " + jobShown);
            describes(page, "Job Log", "after a job's Log button");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/menu-project-joblog.png")));

            // The perspective is remembered across a reload, and the project view opens on its first entry.
            page.reload();
            page.waitForSelector("#right-tab-select");
            page.waitForTimeout(1500);
            check(offered(page).equals(PROJECT_ENTRIES) && selected(page).equals("Project Property")
                            && shown(page).equals(List.of("tab-projectprop")),
                    "after a reload the project view is back, on Project Property: " + selected(page) + " " + shown(page));

            // Opened from elsewhere: externalProxy.log in the actor tree opens Sessions, in the chat view
            // even when clicked from the project view.
            clickTreeNode(page, "Show calls relayed through POST /v1/chat/completions");
            check(offered(page).equals(CHAT_ENTRIES) && selected(page).equals("Sessions")
                            && shown(page).equals(List.of("tab-logdb")),
                    "clicking externalProxy.log from the project view opens Sessions in the chat view: "
                            + selected(page) + " " + shown(page));
            describes(page, "Sessions", "after clicking externalProxy.log");
            clickTreeNode(page, "Show project " + projectId);

            // Back to the chat view through a ConversationSquad actor's node: its first entry is shown.
            clickTreeNode(page, "Switch to project1 / 01");
            check(offered(page).equals(CHAT_ENTRIES) && selected(page).equals("Sessions")
                            && shown(page).equals(List.of("tab-logdb")),
                    "a ConversationSquad actor's node returns to the chat view, on Sessions: " + selected(page) + " " + shown(page));

            // The menu is a native select: keyboard choice works.
            page.focus("#right-tab-select");
            page.keyboard().press("ArrowDown");
            page.waitForTimeout(300);
            check(selected(page).equals("System Log") && shown(page).equals(List.of("tab-syslog")),
                    "ArrowDown on the focused menu moves to System Log: " + selected(page) + " " + shown(page));
            describes(page, "System Log", "after ArrowDown");
            page.keyboard().press("End");
            page.waitForTimeout(300);
            check(selected(page).equals("Actions") && shown(page).equals(List.of("tab-actions")),
                    "End on the focused menu moves to the last chat entry, Actions, not to a project entry: "
                            + selected(page) + " " + shown(page));

            // Narrower still, the menu stays one line.
            page.setViewportSize(800, 800);
            page.waitForTimeout(300);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/menu-chat-800.png")));
            double barHeight = ((Number) page.evaluate("() => document.getElementById('right-tab-bar').offsetHeight")).doubleValue();
            double descHeight = ((Number) page.evaluate("() => document.getElementById('right-tab-desc').offsetHeight")).doubleValue();
            double lineHeight = ((Number) page.evaluate("() => parseFloat(getComputedStyle(document.getElementById('right-tab-desc')).lineHeight)")).doubleValue();
            check(descHeight <= 2 * lineHeight + 1, "at 800 px wide the description takes at most two lines: "
                    + descHeight + " px, line " + lineHeight + " px");
            check(barHeight < 80, "at 800 px wide the menu and its description take under 80 px: " + barHeight + " px");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
