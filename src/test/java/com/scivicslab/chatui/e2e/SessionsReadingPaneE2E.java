package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.ArrayList;
import java.util.List;

/**
 * E2E: reading a conversation in the Sessions tab without losing sight of where you are.
 *
 * <p>Every message used to open where it sat, inside the one scroll that also held the sessions
 * and their turns. Measured on a running instance: opening a session made that list 1,684 pixels
 * tall in a 371-pixel window, and opening five message bodies made it 4,994 — the scroll ran to
 * 3,028 and both the session's heading and the turn's heading, and the delete button in the
 * session's heading, were above the top of the list. A message is now picked rather than opened,
 * and shown in a region of its own below, so the list's height does not depend on what is being
 * read.</p>
 *
 * <p>Per the project's testing standard, this is a {@code main()} program against an
 * already-running instance — it neither starts nor stops one. Point it at an instance whose I/O
 * log holds at least one conversation:</p>
 * <pre>
 *   mvn -o test-compile exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.scivicslab.chatui.e2e.SessionsReadingPaneE2E
 *
 *   CHAT_UI_URL=http://localhost:28039/ mvn -o test-compile exec:java ...
 * </pre>
 */
public class SessionsReadingPaneE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28011/");

    /** How many messages are read in a row. The old layout grew with every one of them. */
    private static final int MESSAGES_TO_READ = 5;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        new SessionsReadingPaneE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    void run() {
        System.out.println("--- SessionsReadingPaneE2E against " + BASE_URL + " ---");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                readingManyMessagesDoesNotMoveTheListUnderThem(browser);
            } finally {
                browser.close();
            }
        }
        if (failed > 0) {
            throw new AssertionError("SessionsReadingPaneE2E: " + failed + " check(s) failed");
        }
        System.out.println("SessionsReadingPaneE2E: PASSED");
    }

    private void readingManyMessagesDoesNotMoveTheListUnderThem(Browser browser) {
        Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(1600, 900));
        List<String> errors = new ArrayList<>();
        page.onPageError(errors::add);
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type())) {
                errors.add("console: " + m.text());
            }
        });
        try {
            page.navigate(BASE_URL,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.locator("details.sess").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(20_000));

            check(fitsItsPanel(page, "#io-sessions"), "the list is inside the right panel");
            check(fitsItsPanel(page, "#io-reading"), "the reading pane is inside the right panel");

            page.locator("details.sess").first().locator("summary.sess-head").click();
            page.locator(".trm").first().waitFor(new Locator.WaitForOptions().setTimeout(20_000));
            int messages = page.locator(".trm").count();
            check(messages > 0, "a session opens into its messages (" + messages + ")");

            int listHeight = scrollHeight(page, "#io-sessions");
            List<Integer> heights = new ArrayList<>();
            String lastRead = "";
            for (int i = 0; i < Math.min(messages, MESSAGES_TO_READ); i++) {
                page.locator(".trm").nth(i).locator(".trm-sum").click();
                page.waitForTimeout(700);
                heights.add(scrollHeight(page, "#io-sessions"));
                lastRead = page.locator("#io-reading-head").textContent().trim();
            }

            check(heights.stream().allMatch(h -> h == listHeight),
                    "reading " + heights.size() + " messages leaves the list the same height ("
                            + listHeight + "px, then " + heights + ")");
            check(page.locator(".trm.selected").count() == 1,
                    "exactly one message is marked as the one being read");
            check(!lastRead.isBlank(),
                    "the reading pane says which message it holds (" + lastRead + ")");
            check(page.locator("#io-reading-body").textContent().length() > 0,
                    "and holds it");

            check(insideTheList(page, "summary.sess-head"),
                    "the session's heading is still in view, with its delete button");
            check(insideTheList(page, ".tr-turn-head"),
                    "the turn's heading is still in view");
            check(fitsItsPanel(page, "#io-sessions") && fitsItsPanel(page, "#io-reading"),
                    "and both regions are still inside the right panel");

            check(errors.isEmpty(), "no JavaScript errors: " + errors);
        } finally {
            page.close();
        }
    }

    // --- measuring ------------------------------------------------------------------------------

    private int scrollHeight(Page page, String selector) {
        Object n = page.evaluate(
                "(s) => Math.round(document.querySelector(s).scrollHeight)", selector);
        return ((Number) n).intValue();
    }

    /** True when the element's box lies within the right panel's box. */
    private boolean fitsItsPanel(Page page, String selector) {
        Object ok = page.evaluate("""
                (s) => {
                  const el = document.querySelector(s);
                  const panel = document.querySelector('#right-panel');
                  if (!el || !panel) return false;
                  const a = el.getBoundingClientRect(), b = panel.getBoundingClientRect();
                  return a.top >= b.top - 1 && a.bottom <= b.bottom + 1;
                }
                """, selector);
        return Boolean.TRUE.equals(ok);
    }

    /** True when the element is present and visible within the list's own box. */
    private boolean insideTheList(Page page, String selector) {
        Object ok = page.evaluate("""
                (s) => {
                  const el = document.querySelector(s);
                  const list = document.querySelector('#io-sessions');
                  if (!el || !list) return false;
                  const a = el.getBoundingClientRect(), b = list.getBoundingClientRect();
                  return a.bottom > b.top && a.top < b.bottom;
                }
                """, selector);
        return Boolean.TRUE.equals(ok);
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            passed++;
            System.out.println("  PASS: " + message);
        } else {
            failed++;
            System.out.println("  FAIL: " + message);
        }
    }
}
