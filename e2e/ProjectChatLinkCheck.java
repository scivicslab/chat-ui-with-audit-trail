import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

/**
 * Browser check of the conversation names in the Project Property view (ProjectPropertyChatLinks_261114_oo01):
 * clicking one must leave the screen exactly as clicking that conversation's name in the left-pane tree does.
 *
 * <p>Run against a throwaway instance: it creates a project, gives it a working directory on the instance's
 * machine and runs the {@code build-team} project workflow (two managers, three workers each, on the local-LLM
 * provider, so no LLM is called), which makes a three-level organization. Java Playwright, outside the Maven
 * build: a main() against a running instance, as the testing standard has it.</p>
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ProjectChatLinkCheck.java \
 *     http://localhost:28977 /tmp/pcl-run/wd
 * </pre>
 *
 * It exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class ProjectChatLinkCheck {

    static String base = "http://localhost:28977";
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

    /**
     * Everything the person can see of "which conversation is shown": the perspective, which centre pane is
     * visible, the active conversation, the right pane's menu and sections, and the tree's highlight and folds.
     */
    static final String STATE = """
            () => {
              const vis = e => !!e && e.offsetParent !== null;
              const sel = document.getElementById('right-tab-select');
              const tree = document.getElementById('actors-tree');
              const active = Array.from(tree.querySelectorAll('.actor-name.tab-active'));
              const box = tree.getBoundingClientRect();
              const inView = active.length === 1 && (r => r.top >= box.top - 1 && r.bottom <= box.bottom + 1)(active[0].getBoundingClientRect());
              return JSON.stringify({
                perspective: document.getElementById('console-root').getAttribute('data-perspective'),
                mainPanelShown: vis(document.getElementById('main-panel')),
                projectPanelShown: vis(document.getElementById('project-panel')),
                activeChat: window.chatUiGetActiveChat(),
                provider: (document.getElementById('provider-select') || {}).value,
                rightSelected: sel.options[sel.selectedIndex].textContent.trim(),
                rightOffered: Array.from(sel.options).filter(o => !o.hidden).map(o => o.textContent.trim()),
                rightShown: Array.from(document.querySelectorAll('.rtab-content')).filter(vis).map(c => c.id),
                sessionsStatus: (document.getElementById('io-status') || {}).textContent,
                treeActive: active.map(a => a.textContent),
                treeActiveInView: inView,
                treeFolded: Array.from(tree.querySelectorAll('.actor-children.collapsed')).length
              });
            }
            """;

    static String state(Page page) {
        return (String) page.evaluate(STATE);
    }

    /** The name of the conversation's row in the tree: "<name> (chat-NN)" or "chat-NN". */
    static Locator treeRow(Page page, String project, String chat) {
        return page.locator("#actors-tree .actor-name.tab-switchable[title='Switch to " + project + " / " + chat.substring(5) + "']");
    }

    static Locator projectRow(Page page, String project) {
        return page.locator("#actors-tree .actor-name.tab-switchable[title='Show project " + project + "']");
    }

    static void openProject(Page page, String project) {
        projectRow(page, project).click();
        page.waitForSelector("#projectprop-chats li");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        String workingDir = args.length > 1 ? args[1] : "/tmp/pcl-run/wd";

        String made = rest("POST", "/api/projects", "application/json", null);
        String project = made.replaceAll(".*\"projectId\":\"([^\"]+)\".*", "$1");
        rest("POST", "/api/projects/" + project + "/working-dir", "text/plain", workingDir);
        rest("POST", "/api/projects/" + project + "/jobs", "application/json",
                "{\"workflow\":\"build-team\",\"parameters\":{\"managers\":\"2\",\"workersPerManager\":\"3\","
                        + "\"work\":\"link e2e\",\"managerProvider\":\"openai-compat\",\"workerProvider\":\"openai-compat\"}}");
        for (int i = 0; i < 40 && !rest("GET", "/api/projects/" + project + "/jobs", "application/json", null)
                .contains("\"FINISHED\""); i++) Thread.sleep(250);
        String org = rest("GET", "/api/projects/" + project, "application/json", null);
        check(org.contains("\"04\":\"02\"") && org.contains("\"02\":\"01\""),
                "project " + project + " has a three-level organization: chat-04 reports to chat-02, which reports to chat-01");

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1500, 900));
            Page page = ctx.newPage();
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");

            // Each conversation twice, from the same start (the project's Project Property view, shown fresh
            // after a reload): once by its name in the tree, once by its name in Project Property.
            // chat-01 is the liaison; chat-04 reports to chat-02, which reports to chat-01 (two levels down); chat-09
            // is the last of the second manager's workers.
            for (String chat : List.of("chat-01", "chat-04", "chat-09")) {
                page.reload();
                page.waitForSelector("#actors-tree .actor-name");
                openProject(page, project);
                String before = state(page);
                check(before.contains("\"perspective\":\"project\"") && before.contains("Project Property"),
                        chat + ": starts in the project perspective on Project Property");
                treeRow(page, project, chat).click();
                page.waitForTimeout(800);
                String viaTree = state(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/pcl-" + chat + "-tree.png")));

                page.reload();
                page.waitForSelector("#actors-tree .actor-name");
                openProject(page, project);
                page.locator("#projectprop-chats button.projectprop-chat-link:text-is('" + chat + "')").click();
                page.waitForTimeout(800);
                String viaLink = state(page);
                page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/pcl-" + chat + "-link.png")));

                check(viaTree.equals(viaLink), chat + ": the link leaves the same state as the tree click"
                        + (viaTree.equals(viaLink) ? "" : "\n      tree: " + viaTree + "\n      link: " + viaLink));
                check(viaLink.contains("\"perspective\":\"chat\"") && viaLink.contains("\"mainPanelShown\":true")
                        && viaLink.contains("\"projectPanelShown\":false"), chat + ": the chat pane is shown");
                check(viaLink.contains("\"chatId\":\"" + chat.substring(5) + "\"")
                                && viaLink.matches(".*\"treeActive\":\\[\"[^\"]*" + chat + "[^\"]*\"\\].*"),
                        chat + ": it is the active conversation, and the one highlighted in the tree");
                check(viaLink.contains("\"treeActiveInView\":true"), chat + ": the highlighted row is in view in the tree");
            }

            // The tree folded down to the project: the link opens it, as the person would have to.
            page.reload();
            page.waitForSelector("#actors-tree .actor-name");
            openProject(page, project);
            projectRow(page, project).locator("xpath=ancestor::div[contains(@class,'actor-label')][1]/span[contains(@class,'actor-toggle')]").click();
            page.waitForTimeout(300);
            check(!treeRow(page, project, "chat-04").isVisible(), "chat-04: the project's node is folded, so its row is hidden");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/pcl-folded-before.png")));
            page.locator("#projectprop-chats button.projectprop-chat-link:text-is('chat-04')").click();
            page.waitForTimeout(800);
            check(treeRow(page, project, "chat-04").isVisible(), "chat-04: the link unfolded the tree down to it");
            String folded = state(page);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/pcl-folded-after.png")));
            check(folded.contains("\"treeActiveInView\":true") && folded.contains("(chat-04)"),
                    "chat-04: highlighted and in view after the unfold");

            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
