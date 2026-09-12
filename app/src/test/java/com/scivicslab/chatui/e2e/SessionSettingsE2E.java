package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * E2E check of {@code ConversationSettingsRecord_260913_oo01} in the Sessions tab: a session whose
 * conversation changed its provider or model shows a settings history above its turns.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01). Needs a conversation
 * {@code project1/s1} that was switched to Claude Code and to model {@code opus} (the design
 * document's live check does that). No LLM call is made here.</p>
 */
public class SessionSettingsE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28014/");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        new SessionSettingsE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() {
        System.out.println("--- SessionSettingsE2E against " + BASE_URL + " ---");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                // The Sessions tab lists the active conversation's sessions: make s1 the active one.
                page.evaluate("() => window.chatUiSwitchChat('project1', 's1')");
                page.locator("#io-refresh").click();
                Locator sess = page.locator("details.sess").filter(new Locator.FilterOptions().setHasText("chat-s1"));
                sess.first().waitFor();
                sess.first().locator("summary.sess-head").click();
                sess.first().locator(".sess-settings-line").first().waitFor();
                int lines = sess.first().locator(".sess-settings-line").count();
                check(lines >= 2, "the settings history lists every change (" + lines + " lines)");
                String last = sess.first().locator(".sess-settings-line").nth(lines - 1).textContent();
                check(last.contains("provider claude") && last.contains("tools harness") && last.contains("model opus"),
                        "the last line holds the current provider, tool set and model: " + last);
                check(sess.first().locator(".sess-body > *").first().getAttribute("class").contains("sess-settings"),
                        "the settings block sits above the turns");
            } finally {
                browser.close();
            }
        }
        if (failed > 0) throw new AssertionError("SessionSettingsE2E: " + failed + " check(s) failed");
    }

    private static void check(boolean condition, String what) {
        if (condition) { passed++; System.out.println("ok:   " + what); }
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
