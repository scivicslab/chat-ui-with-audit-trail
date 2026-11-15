import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Browser check of the timekeeper view (TimekeeperViewInTheActorTree_261115_oo01): clicking a conversation's
 * {@code .timekeeper} actor in the tree shows what that timekeeper is watching in the centre pane, and nothing in
 * the right pane. Run against a throwaway instance that already has the state to show: chat-01 with two requests
 * out to chat-02, chat-02 running a turn with an urgent item waiting, chat-03 with a promised report. No real
 * model is involved (the instance talks to a scripted server). Java Playwright, outside the Maven build.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/TimekeeperViewCheck.java http://localhost:28977
 * </pre>
 *
 * Exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class TimekeeperViewCheck {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static void clickTree(Page page, String titleStart) {
        page.waitForFunction("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".some(e => (e.title || '').startsWith(t))", titleStart);
        page.evaluate("(t) => Array.from(document.querySelectorAll('#actors-tree .tab-switchable'))"
                + ".find(e => (e.title || '').startsWith(t)).click()", titleStart);
        page.waitForTimeout(800);
    }

    static boolean visible(Page page, String selector) {
        return (Boolean) page.evaluate("(s) => { const e = document.querySelector(s); return !!e && e.offsetParent !== null; }", selector);
    }

    static String text(Page page, String selector) {
        return page.textContent(selector).trim();
    }

    @SuppressWarnings("unchecked")
    static List<String> shownRight(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('.rtab-content'))"
                + ".filter(c => c.offsetParent !== null).map(c => c.id)");
    }

    @SuppressWarnings("unchecked")
    static List<String> offered(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#right-tab-select option'))"
                + ".filter(o => !o.hidden).map(o => o.textContent.trim())");
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:28977";
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch();
            Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1500, 900));
            page.onPageError(e -> check(false, "page error: " + e));
            page.navigate(base + "/");
            page.waitForSelector("#right-tab-select");
            page.waitForSelector("#actors-tree .actor-name");

            // chat-01: two requests out, to chat-02.
            clickTree(page, "Show what the timekeeper of project1 / 01");
            check(visible(page, "#timekeeper-panel") && !visible(page, "#main-panel") && !visible(page, "#project-panel"),
                    "chat-01's timekeeper: only the timekeeper pane is in the centre");
            check(shownRight(page).equals(List.of("tab-timekeeper")) && offered(page).equals(List.of("Timekeeper")),
                    "the right pane offers and shows only its one section: " + shownRight(page) + " " + offered(page));
            check(text(page, "#tab-timekeeper").contains("Nothing to show for a timekeeper"),
                    "the right pane says there is nothing to show");
            page.waitForFunction("() => document.querySelectorAll('#tk-sent tr').length >= 3");
            check(text(page, "#timekeeper-panel-title").equals("project1/chat-01.timekeeper"), "the title names the actor");
            check(text(page, "#tk-urgent-wait").equals("10 minutes") && text(page, "#tk-warn").equals("1 minute"),
                    "the limits: " + text(page, "#tk-urgent-wait") + ", " + text(page, "#tk-warn"));
            String sent = text(page, "#tk-sent");
            check(sent.contains("project1/chat-01:s1:t") && sent.contains("project1/chat-02") && sent.contains("not sent"),
                    "two requests out to chat-02, notice not sent yet: " + sent.replaceAll("\\s+", " "));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat01.png")));

            // The notice goes out after the limit (1 minute plus the next check, every 30 s); the view follows it
            // without a reload.
            page.waitForFunction("() => document.getElementById('tk-sent').textContent.includes('sent') "
                    + "&& !document.getElementById('tk-sent').textContent.includes('not sent')",
                    null, new Page.WaitForFunctionOptions().setTimeout(150_000));
            check(true, "once the notice went out, the view says so by itself: " + text(page, "#tk-sent").replaceAll("\\s+", " "));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat01-noticed.png")));

            // chat-02: running a turn, an urgent item waiting.
            clickTree(page, "Show what the timekeeper of project1 / 02");
            page.waitForFunction("() => document.getElementById('tk-turn').textContent.startsWith('running')");
            String urgent = text(page, "#tk-urgent");
            check(urgent.startsWith("Waiting") && urgent.contains("would be paused in"),
                    "chat-02: urgent item waiting, pause countdown: " + urgent);
            check(text(page, "#tk-sent").startsWith("None"), "chat-02 sent nothing: " + text(page, "#tk-sent"));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat02.png")));

            // chat-03: promised reports.
            clickTree(page, "Show what the timekeeper of project1 / 03");
            page.waitForFunction("() => document.querySelectorAll('#tk-promised tr').length >= 2");
            String promised = text(page, "#tk-promised");
            check(promised.contains("project1/chat-01:s9:t9:r9") && promised.contains("(to the person)") && promised.contains("#1"),
                    "chat-03: a promise for a request and one to the person: " + promised.replaceAll("\\s+", " "));
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/timekeeper-chat03.png")));

            // The other perspectives still work, and the timekeeper one is left cleanly.
            clickTree(page, "Show project project1");
            check(visible(page, "#project-panel") && !visible(page, "#timekeeper-panel"),
                    "the Project actor still shows the project pane");
            check(offered(page).contains("Project Property") && !offered(page).contains("Timekeeper"),
                    "the project's right-pane menu is back: " + offered(page));
            clickTree(page, "Switch to project1 / 01");
            check(visible(page, "#main-panel") && !visible(page, "#timekeeper-panel") && offered(page).contains("Sessions"),
                    "a conversation still shows the chat pane: " + offered(page));

            // A reload comes back to the same timekeeper.
            clickTree(page, "Show what the timekeeper of project1 / 02");
            page.reload();
            page.waitForSelector("#actors-tree .actor-name");
            page.waitForTimeout(1500);
            check(visible(page, "#timekeeper-panel") && text(page, "#timekeeper-panel-title").equals("project1/chat-02.timekeeper"),
                    "a reload comes back to chat-02's timekeeper");
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
