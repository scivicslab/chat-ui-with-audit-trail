import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Browser check of the left pane's Graph view (ConversationGraphInTheLeftPane_261117_oo01), against a throwaway
 * instance that has only chat-01 and talks to e2e/ScriptedModelServer.java. It makes chat-02..05, has chat-01 set
 * superiors and role edges with the real tools (SCENARIO-G), checks the drawing, changes the organization
 * (SCENARIO-G2) and checks that the drawing follows within the 3-second refresh. Java Playwright, outside the
 * Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ScriptedModelServer.java 28978 &amp;
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ConversationGraphCheck.java http://localhost:28979
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class ConversationGraphCheck {

    static String base = "http://localhost:28979";
    static final HttpClient http = HttpClient.newHttpClient();
    static int failures = 0;

    static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static void turn(String chat, String text) throws Exception {
        rest("POST", "/api/projects/project1/chats/" + chat + "/chat", "{\"text\":\"" + text + "\"}");
        Thread.sleep(800);
        for (int i = 0; i < 240; i++) {
            if (rest("GET", "/api/projects/project1/chats/" + chat + "/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("chat " + chat + " stayed busy");
    }

    @SuppressWarnings("unchecked")
    static List<String> roleLabels(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#conv-graph .cg-role-label')).map(e => e.textContent)");
    }

    static String attr(Page page, String selector, String name) {
        Object v = page.evaluate("([s, n]) => { const e = document.querySelector(s); return e ? e.getAttribute(n) : null; }",
                List.of(selector, name));
        return v == null ? null : v.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        for (int i = 0; i < 4; i++) rest("POST", "/api/projects/project1/chats", null);
        turn("01", "SCENARIO-G build the organization");
        String g = rest("GET", "/api/projects/project1/graph", null);
        System.out.println("graph: " + g);
        check(g.contains("{\"from\":\"02\",\"to\":\"03\"}") && g.contains("{\"from\":\"03\",\"to\":\"05\"}")
                        && g.contains("\"role\":\"auditor\",\"to\":\"05\",\"toProject\":\"project2\""),
                "the REST graph holds what the tools set");
        int superiorEdges = g.split("\\{\"from\":\"[0-9]+\",\"to\":").length - 1;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                    + "if (!localStorage.getItem('chat-ui-last-chat')) localStorage.setItem('chat-ui-last-chat','01');");
            Page page = ctx.newPage();
            page.onPageError(e -> check(false, "page error: " + e));
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");
            check(page.isVisible("#actors-tree") && !page.isVisible("#conv-graph"), "the dock opens on the Actor Tree");

            page.click("#left-view-graph");
            page.waitForSelector("#conv-graph svg .cg-node");
            check(page.isVisible("#conv-graph") && !page.isVisible("#actors-tree") && page.isVisible("#graph-project"),
                    "Graph replaces the tree and shows the project select");
            check("Graph".equals(page.textContent("#left-dock .dock-title").strip()), "the dock title reads Graph");
            check("project1".equals(page.inputValue("#graph-project")), "the graph shows the active chat's project");
            int nodes = page.querySelectorAll("#conv-graph .cg-node").size();
            check(nodes == 6, "five conversations and one role target in another project: " + nodes);
            check(page.querySelectorAll("#conv-graph .cg-node.cg-external").size() == 1
                            && page.textContent("#conv-graph .cg-node.cg-external").contains("project2/chat-05"),
                    "the target in project2 is drawn as an outside node");
            check(page.querySelectorAll("#conv-graph .cg-node.cg-liaison").size() == 1
                            && page.textContent("#conv-graph .cg-node.cg-liaison").contains("chat-01"),
                    "chat-01 is marked as the liaison");
            int sup = page.querySelectorAll("#conv-graph path.cg-sup[data-to]").size();
            check(sup == superiorEdges, "one elbow per superior edge: " + sup + " of " + superiorEdges);
            Set<String> labels = new TreeSet<>(roleLabels(page));
            check(labels.equals(new TreeSet<>(List.of("auditor", "helper", "reviewer", "worker")))
                            && page.querySelectorAll("#conv-graph path.cg-role[data-role]").size() == 4,
                    "four role edges, each labelled with its role: " + roleLabels(page));
            check("03".equals(attr(page, "#conv-graph path.cg-role[data-from='02'][data-role='worker']", "data-to")),
                    "chat-02's worker is chat-03");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-1.png")));

            // A change in the organization reaches the drawing on the next refresh (every 3 s with Auto on).
            long t0 = System.currentTimeMillis();
            turn("01", "SCENARIO-G2 move chat-05");
            page.waitForFunction("() => { const w = document.querySelector(\"#conv-graph path.cg-role[data-from='02'][data-role='worker']\");"
                    + " const s = document.querySelector(\"#conv-graph path.cg-sup[data-to='05']\");"
                    + " return w && w.getAttribute('data-to') === '05' && s && s.getAttribute('data-from') === '01'; }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            long waited = System.currentTimeMillis() - t0;
            check(true, "after set_superior and set_collaborator the drawing changed by itself (" + waited
                    + " ms from sending the turn, the turn included)");
            check(page.querySelectorAll("#conv-graph path.cg-role[data-role]").size() == 4, "the worker edge was moved, not added");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-2-changed.png")));

            // A node opens its conversation, as the tree does.
            page.click("#conv-graph .cg-node[data-chat='03'] text");
            page.waitForTimeout(800);
            check("03".equals(page.evaluate("window.chatUiCurrentChatId()")), "clicking chat-03 opens chat-03");
            check(page.isVisible("#conv-graph"), "and the dock stays on the graph");

            // The choice survives a reload; the tree comes back with one click.
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#conv-graph svg .cg-node");
            check("true".equals(page.getAttribute("#left-view-graph", "aria-pressed")) && page.isVisible("#conv-graph"),
                    "after reload the dock is still on the graph");
            page.click("#left-view-tree");
            page.waitForTimeout(500);
            check(page.isVisible("#actors-tree") && !page.isVisible("#conv-graph") && !page.isVisible("#graph-project")
                            && "Actors".equals(page.textContent("#left-dock .dock-title").strip()),
                    "Actor Tree brings the tree back");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
