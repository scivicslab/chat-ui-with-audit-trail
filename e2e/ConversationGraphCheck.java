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
    /** The second project the check makes; "project2" on a fresh instance. */
    static String project2 = "project2";
    static final HttpClient http = HttpClient.newHttpClient();
    static int failures = 0;

    static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** A project workflow that puts chat-04 on a model and an agent loop, as build-team does with the chats actor. */
    static final String SET_MODEL_AND_LOOP = """
            name: e2e-set-model-and-loop
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: chats
                    method: setModel
                    arguments: {chat: project1/chat-04, model: model-from-workflow}
              - states: ["1", "2"]
                actions:
                  - actor: chats
                    method: setAgentLoop
                    arguments: {chat: project1/chat-04, loop: plain-text-loop.yaml}
              - states: ["2", "end"]
                actions:
                  - {actor: this, method: finish, arguments: ["done"], execution: direct}
            """;

    /** The working directory project1 is given, where its workflows are saved. */
    static final String WORKING_DIR = "/tmp/cg-e2e-wd";

    static String restText(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "text/plain");
        b = b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Runs the project workflow above and waits for it to end; returns the job's last state. */
    static String runSetModelAndLoop() throws Exception {
        java.nio.file.Files.createDirectories(Path.of(WORKING_DIR));
        restText("POST", "/api/projects/project1/working-dir", WORKING_DIR);
        restText("PUT", "/api/projects/project1/workflows/e2e-set-model-and-loop", SET_MODEL_AND_LOOP);
        String job = rest("POST", "/api/projects/project1/jobs", "{\"workflow\":\"e2e-set-model-and-loop\"}");
        String jobId = job.replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String state = job;
        for (int i = 0; i < 120; i++) {
            state = rest("GET", "/api/projects/project1/jobs/" + jobId, null);
            if (state.contains("FINISHED") || state.contains("FAILED") || state.contains("STOPPED")) break;
            Thread.sleep(500);
        }
        return state;
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
        Object v = page.evaluate("(c) => { const e = document.querySelector(\"#conv-graph .cg-node[data-chat='\" + c + \"']\");"
                + " return e ? (e.getAttribute('data-tip') || '') : ''; }", chat);
        return v == null ? "" : v.toString();
    }

    /** The legend's role entries, as "emoji names". */
    @SuppressWarnings("unchecked")
    static List<String> legend(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#conv-graph .cg-legend-item'))"
                + ".map(e => { const s = e.querySelectorAll('tspan'); return s[0].textContent + ' ' + s[1].textContent; })");
    }

    static final String ROW_ONE = "['#left-view-select', '#new-project-btn', '#actors-refresh', '.tab-auto']"
            + ".map(s => document.querySelector('#left-dock .dock-head ' + s).getBoundingClientRect())";

    /** The menu, +, refresh and Auto share one line of the dock's head, and the head does not overflow. */
    static boolean rowOneOnOneLine(Page page) {
        return (Boolean) page.evaluate("() => { const b = " + ROW_ONE + "; const h = document.querySelector('#left-dock .dock-head');"
                + " const top = Math.min(...b.map(r => r.top)), bottom = Math.max(...b.map(r => r.bottom));"
                + " return b.every(r => r.width > 0) && bottom - top < 2 * Math.min(...b.map(r => r.height))"
                + " && h.scrollWidth <= h.clientWidth && b.every(r => r.right <= h.getBoundingClientRect().right); }");
    }

    static String rowOneBoxes(Page page) {
        return String.valueOf(page.evaluate("() => JSON.stringify({dock: document.getElementById('left-dock').clientWidth, boxes: "
                + ROW_ONE + ".map(r => [Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom)])})"));
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
        if (args.length > 1 && "after-restart".equals(args[1])) {
            afterRestart();
            return;
        }
        for (int i = 0; i < 4; i++) rest("POST", "/api/projects/project1/chats", null);
        String made = rest("POST", "/api/projects", null);
        project2 = made.replaceAll(".*\"projectId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        restText("POST", "/api/projects/project1/name", "Graph check");
        restText("POST", "/api/projects/" + project2 + "/name", "Second project");
        // chat-02..05 never run a turn: after a restart (after-restart mode) they come back because creating them
        // recorded them (ConversationRestoreOnRestart_260904_oo01).
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
            check(page.isVisible("#conv-graph") && !page.isVisible("#actors-tree")
                            && page.querySelectorAll("#graph-project").isEmpty(),
                    "Graph replaces the tree; there is no project selector");
            check(page.querySelectorAll("#left-dock .dock-title").isEmpty(), "no separate title: the menu names the view");
            page.waitForFunction("() => /conversation/.test(document.getElementById('actors-status').textContent)");
            check("2 projects, 6 conversations".equals(page.textContent("#actors-status").strip()),
                    "row 2 counts the projects and their conversations: " + page.textContent("#actors-status"));
            check(rowOneOnOneLine(page), "the menu, +, refresh and Auto sit on one line: " + rowOneBoxes(page));
            check(page.querySelectorAll("#conv-graph .cg-project").size() == 2
                            && page.textContent("#conv-graph .cg-project[data-project='project1']").contains("Graph check")
                            && page.textContent("#conv-graph .cg-project[data-project='project1']").contains("project1")
                            && page.textContent("#conv-graph .cg-project[data-project='" + project2 + "']").contains("Second project"),
                    "every project is a top-level row with its name and its id: "
                            + page.textContent("#conv-graph .cg-project[data-project='project1']") + " / "
                            + page.textContent("#conv-graph .cg-project[data-project='" + project2 + "']"));
            int nodes = page.querySelectorAll("#conv-graph .cg-node").size();
            check(nodes == 7, "five conversations of project1, one of project2, one role target not on this instance: " + nodes);
            check(page.querySelectorAll("#conv-graph .cg-node.cg-external").size() == 1
                            && page.textContent("#conv-graph .cg-node.cg-external").contains("project2/chat-05")
                            && tooltip(page, "project2/05").contains("not on this instance"),
                    "a role target that is not a conversation here is the only placeholder");
            check(page.querySelectorAll("#conv-graph .cg-node.cg-liaison").size() == 2
                            && !page.querySelectorAll("#conv-graph .cg-node.cg-liaison[data-chat='project1/01']").isEmpty(),
                    "each project's chat-01 is marked as its liaison");
            int sup = page.querySelectorAll("#conv-graph path.cg-sup[data-to]").size();
            check(sup == superiorEdges, "one elbow per superior edge: " + sup + " of " + superiorEdges);
            check(page.querySelectorAll("#conv-graph path.cg-role, #conv-graph .cg-role-label").isEmpty(),
                    "no role arcs are drawn");
            check("🔧".equals(prefix(page, "project1/03")) && "🔍".equals(prefix(page, "project1/04")) && "🤝".equals(prefix(page, "project1/05"))
                            && "📋".equals(prefix(page, "project2/05")) && "".equals(prefix(page, "project1/02")) && "".equals(prefix(page, "project1/01")),
                    "each collaborator carries its role's emoji: 03=" + prefix(page, "project1/03") + " 04=" + prefix(page, "project1/04")
                            + " 05=" + prefix(page, "project1/05") + " project2/05=" + prefix(page, "project2/05"));
            check(tooltip(page, "project1/03").contains("worker for chat-02") && tooltip(page, "project2/05").contains("auditor for project1/chat-05"),
                    "the tooltip states the relation: " + tooltip(page, "project1/03").replace('\n', '|'));
            Set<String> leg = new TreeSet<>(legend(page));
            check(leg.equals(new TreeSet<>(List.of("📋 auditor", "🤝 helper", "🔍 reviewer", "🔧 worker"))),
                    "the legend maps each emoji in use to its role: " + legend(page));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-1.png")));

            // A change in the organization reaches the drawing on the next refresh (every 3 s with Auto on).
            long t0 = System.currentTimeMillis();
            turn("01", "SCENARIO-G2 move chat-05");
            page.waitForFunction("() => { const n = document.querySelector(\"#conv-graph .cg-node[data-chat='project1/05']\");"
                    + " const s = document.querySelector(\"#conv-graph path.cg-sup[data-to='05']\");"
                    + " return n && (n.getAttribute('data-roles') || '').includes('worker:chat-02') && s && s.getAttribute('data-from') === '01'; }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            long waited = System.currentTimeMillis() - t0;
            check(true, "after set_superior and set_collaborator the drawing changed by itself (" + waited
                    + " ms from sending the turn, the turn included)");
            check("".equals(prefix(page, "project1/03")), "chat-03 is no longer chat-02's worker, so it has no emoji: '" + prefix(page, "project1/03") + "'");
            String five = prefix(page, "project1/05");
            check("🏷️🤝🧪…".equals(five),
                    "chat-05 plays four roles: three emoji shown, in role order (archivist, helper, tester), then …: " + five);
            String tip5 = tooltip(page, "project1/05");
            check(tip5.contains("archivist for chat-01") && tip5.contains("helper for chat-03") && tip5.contains("tester for chat-04")
                            && tip5.contains("worker for chat-02"),
                    "chat-05's tooltip keeps all four: " + tip5.replace('\n', '|'));
            check(legend(page).contains("🏷️ archivist") && legend(page).contains("🧪 tester"),
                    "a role with no emoji of its own takes the fallback, and the legend says so: " + legend(page));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-2-changed.png")));

            // A project workflow puts chat-04 on a model and an agent loop; the tooltip follows.
            String jobEnd = runSetModelAndLoop();
            check(jobEnd.contains("FINISHED"), "the project workflow that sets chat-04's model and loop finished: "
                    + jobEnd.substring(0, Math.min(160, jobEnd.length())));
            page.waitForFunction("() => { const n = document.querySelector(\"#conv-graph .cg-node[data-chat='project1/04']\");"
                    + " return n && (n.getAttribute('data-tip') || '').includes(': model-from-workflow'); }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            check(true, "chat-04's tooltip names the model the workflow set: " + tooltip(page, "project1/04").replace('\n', '|'));

            // What each conversation runs on, in its tooltip, as the REST graph read it from the conversation.
            String g2 = rest("GET", "/api/projects/project1/graph", null);
            String provider = g2.replaceAll("(?s).*\"chatId\":\"03\",\"name\":null,\"liaison\":false,\"provider\":\"([^\"]*)\".*", "$1");
            check(!provider.equals(g2) && !provider.isBlank() && g2.contains("\"model\":\"fake\""),
                    "the REST graph gives each local conversation its provider and model: " + provider + " / fake");
            check(tooltip(page, "project1/03").startsWith("project1/chat-03\n" + provider + ": fake"),
                    "chat-03's tooltip names what it runs on: " + tooltip(page, "project1/03").replace('\n', '|'));
            check(tooltip(page, "project1/05").contains(provider + ": fake\n🏷️ archivist for chat-01"),
                    "the model line comes before the role lines: " + tooltip(page, "project1/05").replace('\n', '|'));
            check(tooltip(page, "project2/05").contains("model unknown"),
                    "a conversation in another project says model unknown: " + tooltip(page, "project2/05").replace('\n', '|'));

            // A model change shows on the next refresh, read again from the conversation.
            rest("POST", "/api/projects/project1/chats/03/model", "{\"model\":\"fake-2\"}");
            page.waitForFunction("() => { const n = document.querySelector(\"#conv-graph .cg-node[data-chat='project1/03']\");"
                    + " return n && (n.getAttribute('data-tip') || '').includes(': fake-2'); }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            check(tooltip(page, "project1/03").contains(provider + ": fake-2"), "after set model, chat-03's tooltip says fake-2");

            // The tooltip itself, as the person sees it on hovering a node.
            page.hover("#conv-graph .cg-node[data-chat='project1/05'] .cg-id");
            page.waitForSelector("#cg-tip", new Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
            String shown = page.textContent("#cg-tip");
            check(shown.contains(provider + ": fake") && shown.contains("worker for chat-02"),
                    "hovering chat-05 shows its tooltip: " + shown.replace('\n', '|'));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-4-tooltip.png")));
            page.mouse().move(900, 700);

            // A role played by a conversation of another project lands on that conversation, in the same tree.
            turn("01", "SCENARIO-G3 a role in another project");
            page.waitForFunction("() => { const n = document.querySelector(\"#conv-graph .cg-node[data-chat='project2/01']\");"
                    + " return n && (n.getAttribute('data-roles') || '').includes('translator'); }",
                    null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            check("🌐".equals(prefix(page, project2 + "/01")) && tooltip(page, project2 + "/01").contains("translator for project1/chat-02"),
                    "project2's chat-01 carries the translator emoji, for project1/chat-02: " + tooltip(page, project2 + "/01").replace('\n', '|'));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-5-all-projects.png")));

            // Folding a project hides its conversations, and stays folded after a reload.
            page.click("#conv-graph .cg-project[data-project='" + project2 + "']");
            page.waitForTimeout(400);
            check(page.querySelectorAll("#conv-graph .cg-node[data-chat='" + project2 + "/01']").isEmpty()
                            && "false".equals(page.getAttribute("#conv-graph .cg-project[data-project='" + project2 + "']", "aria-expanded")),
                    "folding project2 hides its conversations");
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#conv-graph svg .cg-project");
            check("false".equals(page.getAttribute("#conv-graph .cg-project[data-project='" + project2 + "']", "aria-expanded"))
                            && page.querySelectorAll("#conv-graph .cg-node[data-chat='" + project2 + "/01']").isEmpty(),
                    "after a reload project2 is still folded");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-6-folded.png")));
            page.click("#conv-graph .cg-project[data-project='" + project2 + "']");
            page.waitForTimeout(400);
            check(!page.querySelectorAll("#conv-graph .cg-node[data-chat='" + project2 + "/01']").isEmpty(),
                    "unfolding shows them again");

            // Thinking and effort, set with the pane's own controls: chat-03's Think box, and chat-05's Effort field
            // (chat-05 is moved to the Claude Code provider, the one that keeps an effort; no prompt is sent).
            rest("POST", "/api/projects/project1/chats/05/provider", "{\"provider\":\"claude\"}");
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForTimeout(1500);
            page.uncheck("#think-check");
            page.waitForTimeout(800);
            check(rest("GET", "/api/projects/project1/chats/03/status", null).contains("\"noThink\":true"),
                    "unticking Think on chat-03 set it to answer without thinking");
            page.evaluate("window.chatUiSwitchChat('project1','05')");
            page.waitForTimeout(1500);
            page.fill("#effort-input", "high");
            page.dispatchEvent("#effort-input", "change");
            page.waitForTimeout(800);
            String st5 = rest("GET", "/api/projects/project1/chats/05/status", null);
            check(st5.contains("\"provider\":\"claude\"") && st5.contains("\"effort\":\"high\""),
                    "typing high into chat-05's Effort field set it: " + st5);
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/think-effort-1.png")));

            // A node opens its conversation, as the tree does.
            page.click("#conv-graph .cg-node[data-chat='project1/03'] text");
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
                            && "tree".equals(page.inputValue("#left-view-select")),
                    "Actor Tree brings the tree back");
            page.waitForFunction("() => /actor\\(s\\)/.test(document.getElementById('actors-status').textContent)");
            check(page.textContent("#actors-status").strip().matches("\\d+ actor\\(s\\)"),
                    "row 2 counts the actors: " + page.textContent("#actors-status"));
            check(rowOneOnOneLine(page), "row 1 is still one line in the tree: " + rowOneBoxes(page));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Run against the same instance after a restart on the same data directory: the roles the normal run left
     * (after SCENARIO-G2) are still on the nodes, since role edges are saved with the project's settings
     * (CollaborationGraph_260828_oo01). The edge to project2/chat-05, which never existed, is dropped at start-up.
     * Nothing is set up here.
     */
    static void afterRestart() throws Exception {
        String g = rest("GET", "/api/projects/project1/graph", null);
        System.out.println("graph after restart: " + g);
        check(g.contains("\"role\":\"worker\",\"to\":\"05\"") && g.contains("\"role\":\"archivist\",\"to\":\"05\"")
                        && g.contains("\"role\":\"tester\",\"to\":\"05\"") && g.contains("\"role\":\"reviewer\",\"to\":\"04\""),
                "the REST graph still has the role edges after the restart");
        check(!g.contains("\"to\":\"05\",\"toProject\":\"project2\""),
                "the edge to project2/chat-05, a conversation that never existed, was dropped at start-up");
        check(g.contains("\"role\":\"translator\",\"to\":\"01\",\"toProject\":\"project2\""),
                "the edge into project2/chat-01, which exists, came back");
        String chats = rest("GET", "/api/projects/project1/chats", null);
        check(chats.contains("\"02\"") && chats.contains("\"03\"") && chats.contains("\"04\"") && chats.contains("\"05\""),
                "chat-02..05, which never ran a turn, came back: " + chats);
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                    + "localStorage.setItem('chat-ui-last-chat','01');");
            Page page = ctx.newPage();
            page.onPageError(e -> check(false, "page error: " + e));
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#actors-tree .actor-name");
            chooseView(page, "graph");
            page.waitForSelector("#conv-graph svg .cg-node");
            check(page.textContent("#conv-graph .cg-project[data-project='project1']").contains("Graph check")
                            && page.textContent("#conv-graph .cg-project[data-project='project2']").contains("Second project"),
                    "after the restart both projects carry their names");
            check("🌐".equals(prefix(page, "project2/01")), "and project2's chat-01 still carries the translator emoji");
            check("🔍".equals(prefix(page, "project1/04")) && "🏷️🤝🧪…".equals(prefix(page, "project1/05"))
                            && page.querySelectorAll("#conv-graph .cg-node.cg-external").isEmpty() && "".equals(prefix(page, "project1/03")),
                    "after the restart the nodes carry the same roles: 04=" + prefix(page, "project1/04") + " 05=" + prefix(page, "project1/05")
                            + " project2/05=" + prefix(page, "project2/05"));
            check(tooltip(page, "project1/05").contains("worker for chat-02"), "and chat-05 is still chat-02's worker: "
                    + tooltip(page, "project1/05").replace('\n', '|'));
            check("01".equals(attr(page, "#conv-graph path.cg-sup[data-to='05']", "data-from")),
                    "the org chart came back too: chat-05 under chat-01");
            check(tooltip(page, "project1/04").contains(": model-from-workflow"),
                    "the model a project workflow set on chat-04 came back: " + tooltip(page, "project1/04").replace('\n', '|'));
            String loops = rest("GET", "/api/projects/project1/chats/04/agent-loops", null);
            check(loops.matches("(?s).*\\{\"file\":\"plain-text-loop\\.yaml\"[^}]*\"current\":true.*"),
                    "the agent loop a project workflow set on chat-04 came back as its current loop");
            String project = rest("GET", "/api/projects/project1", null);
            check(project.contains("\"workingDir\":\"" + WORKING_DIR + "\""),
                    "project1's working directory came back through its settings record: " + project.substring(0, Math.min(200, project.length())));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/graph-3-after-restart.png")));

            // Thinking and effort came back, as the pane's controls show them.
            String st3 = rest("GET", "/api/projects/project1/chats/03/status", null);
            String st5 = rest("GET", "/api/projects/project1/chats/05/status", null);
            check(st3.contains("\"noThink\":true"), "chat-03 still answers without thinking: " + st3);
            check(st5.contains("\"provider\":\"claude\"") && st5.contains("\"effort\":\"high\""),
                    "chat-05 is still on Claude Code at effort high: " + st5);
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForTimeout(1500);
            check(!page.isChecked("#think-check"), "chat-03's Think box comes back unticked");
            page.evaluate("window.chatUiSwitchChat('project1','05')");
            page.waitForTimeout(1500);
            check("high".equals(page.inputValue("#effort-input")), "chat-05's Effort field comes back with high: '"
                    + page.inputValue("#effort-input") + "'");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/think-effort-after-restart.png")));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
