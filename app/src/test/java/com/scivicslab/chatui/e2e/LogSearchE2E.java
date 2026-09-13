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
 * E2E: finding something said in a conversation without knowing which conversation
 * ({@code CrossConversationLogSearch_260913_oo01}).
 *
 * <p>The path this walks is the one the feature exists for: click {@code outputMultiplexer} in the
 * actor tree, type a phrase, and read the turn it was part of — in a conversation that may have no
 * tab on the screen at all, because {@code loader.removeChild} took its actor away and left the
 * log.</p>
 *
 * <p>Per the project's testing standard, this is a {@code main()} program against an
 * already-running instance — it neither starts nor stops one. Point it at an instance whose I/O log
 * holds conversations, and give it a word those conversations contain:</p>
 * <pre>
 *   CHAT_UI_URL=http://localhost:28061/ CHAT_UI_QUERY=actor \
 *     mvn -o test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.scivicslab.chatui.e2e.LogSearchE2E
 * </pre>
 */
public class LogSearchE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28011/");

    /** A word the instance's conversations contain; the search is meant to find it. */
    private static final String QUERY = System.getenv().getOrDefault("CHAT_UI_QUERY", "actor");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        new LogSearchE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    void run() {
        System.out.println("--- LogSearchE2E against " + BASE_URL + " (query: " + QUERY + ") ---");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                theMultiplexerOpensTheSearchAndAHitOpensItsTurn(browser);
            } finally {
                browser.close();
            }
        }
        if (failed > 0) {
            throw new AssertionError("LogSearchE2E: " + failed + " check(s) failed");
        }
        System.out.println("LogSearchE2E: PASSED");
    }

    private void theMultiplexerOpensTheSearchAndAHitOpensItsTurn(Browser browser) {
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

            // The multiplexer is the way in: it is what every conversation's lines pass through.
            Locator multiplexer = page.locator("#actors-tree .actor-name")
                    .filter(new Locator.FilterOptions().setHasText("outputMultiplexer")).first();
            multiplexer.waitFor(new Locator.WaitForOptions().setTimeout(20_000));
            multiplexer.click();

            check(page.locator("#tab-logsearch.active").count() == 1,
                    "clicking outputMultiplexer opens the Log Search tab");

            page.locator("#lsearch-q").fill(QUERY);
            page.locator("#lsearch-go").click();
            page.locator("details.lshit").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(20_000));
            int hits = page.locator("details.lshit").count();
            check(hits > 0, "a phrase is found across the conversations (" + hits + " hit(s))");

            String where = page.locator("details.lshit .lshit-conv").first().textContent().trim();
            check(!where.isBlank(), "each hit says which conversation it was said in (" + where + ")");
            String snippet = page.locator("details.lshit .lshit-snippet").first().textContent();
            check(snippet.toLowerCase().contains(QUERY.toLowerCase()),
                    "and shows the text around the match");

            // A hit is read in its turn, not as a fragment.
            page.locator("details.lshit summary.lshit-head").first().click();
            page.locator("details.lshit .trm").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(20_000));
            int messages = page.locator("details.lshit .trm").count();
            check(messages > 0, "opening a hit shows the whole turn it was part of ("
                    + messages + " message(s))");

            page.locator("details.lshit .trm .trm-sum").first().click();
            page.waitForTimeout(1_000);
            String head = page.locator("#lsearch-reading-head").textContent().trim();
            check(!head.isBlank() && !head.startsWith("Pick a message"),
                    "picking a message says what is being read (" + head + ")");
            check(page.locator("#lsearch-reading-body").textContent().length() > 0,
                    "and reads it in this tab's own pane");
            check(page.locator("#io-reading-body").textContent().isBlank(),
                    "the Sessions tab's reading pane is left alone");

            check(errors.isEmpty(), "no JavaScript errors: " + errors);
        } finally {
            page.close();
        }
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
