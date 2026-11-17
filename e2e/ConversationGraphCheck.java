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

    /** The emoji in front of a node's label, or "" when it plays no role. */
    static String prefix(Page page, String chat) {
        Object v = page.evaluate("(c) => { const e = document.querySelector(\"#conv-graph .cg-node[data-chat='\" + c + \"'] .cg-roles\");"
                + " return e ? e.textContent.trim() : ''; }", chat);
        return v == null ? "" : v.toString();
    }

    /** A node's tooltip. */
    static String tooltip(Page page, String chat) {
        Object v = page.evaluate("(c) => { const e = document.querySelector(\"#conv-graph .cg-node[data-chat='\" + c + \"'] > title\");"
                + " return e ? e.textContent : ''; }", chat);
        return v == null ? "" : v.toString();
    }

    /** The legend's role entries, as "emoji names". */
    @SuppressWarnings("unchecked")
    static List<String> legend(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#conv-graph .cg-legend-item'))"
                + ".map(e => { const s = e.querySelectorAll('tspan'); return s[0].textContent + ' ' + s[1].textContent; })");
    }

    static void chooseView(Page page, String value) {
        page.selectOption("#left-view-select", value);
        page.waitForTimeout(600);
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

            check(page.isVisible("#left-view-select") && "tree".equals(page.inputValue("#left-view-select")),
                    "the view menu shows Actor Tree");
            @SuppressWarnings("unchecked")
            List<String> options = (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#left-view-select option')).map(o => o.textContent.trim())");
            check(options.equals(List.of("Actor Tree", "Graph")), "the menu offers Actor Tree and Graph: " + options);
            chooseView(page, "graph");
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
            check(page.querySelectorAll("#conv-graph path.cg-role, #conv-graph .cg-role-label").isEmpty(),
                    "no role arcs are drawn");
            check("🔧".equals(prefix(page, "03")) && "🔍".equals(prefix(page, "04")) && "🤝".equals(prefix(page, "05"))
                            && "📋".equals(prefix(page, "project2/05")) && "".equals(prefix(page, "02")) && "".equals(prefix(page, "01")),
                    "each collaborator carries its role's emoji: 03=" + prefix(page, "03") + " 04=" + prefix(page, "04")
                            + " 05=" + prefix(page, "05") + " project2/05=" + prefix(page, "project2/05"));
            check(tooltip(page, "03").contains("worker for chat-02") && tooltip(page, "project2/05").contains("auditor for chat-05"),
                    "the tooltip states the relation: " + tooltip(page, "03").replace('\n', '|'));
            Set<String> leg = new TreeSet<>(legend(page));
            check(leg.equals(new TreeSet<>(List.of("📋 auditor", "🤝 helper", "🔍 reviewer", "🔧 worker"))),
                    "the legend maps each emoji in use to its role: " + legend(page));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-1.png")));

            // A change in the organization reaches the drawing on the next refresh (every 3 s with Auto on).
            long t0 = System.currentTimeMillis();
            turn("01", "SCENARIO-G2 move chat-05");
            page.waitForFunction("() => { const n = document.querySelector(\"#conv-graph .cg-node[data-chat='05']\");"
                    + " const s = document.querySelector(\"#conv-graph path.cg-sup[data-to='05']\");"
                    + " return n && (n.getAttribute('data-roles') || '').includes('worker:02') && s && s.getAttribute('data-from') === '01'; }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            long waited = System.currentTimeMillis() - t0;
            check(true, "after set_superior and set_collaborator the drawing changed by itself (" + waited
                    + " ms from sending the turn, the turn included)");
            check("".equals(prefix(page, "03")), "chat-03 is no longer chat-02's worker, so it has no emoji: '" + prefix(page, "03") + "'");
            String five = prefix(page, "05");
            check("🏷️🤝🧪…".equals(five),
                    "chat-05 plays four roles: three emoji shown, in role order (archivist, helper, tester), then …: " + five);
            String tip5 = tooltip(page, "05");
            check(tip5.contains("archivist for chat-01") && tip5.contains("helper for chat-03") && tip5.contains("tester for chat-04")
                            && tip5.contains("worker for chat-02"),
                    "chat-05's tooltip keeps all four: " + tip5.replace('\n', '|'));
            check(legend(page).contains("🏷️ archivist") && legend(page).contains("🧪 tester"),
                    "a role with no emoji of its own takes the fallback, and the legend says so: " + legend(page));
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
            check("graph".equals(page.inputValue("#left-view-select")) && page.isVisible("#conv-graph"),
                    "after reload the dock is still on the graph");
            chooseView(page, "tree");
            check(page.isVisible("#actors-tree") && !page.isVisible("#conv-graph") && !page.isVisible("#graph-project")
                            && "Actors".equals(page.textContent("#left-dock .dock-title").strip()),
                    "Actor Tree brings the tree back");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
