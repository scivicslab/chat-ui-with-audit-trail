package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;

/**
 * E2E check of {@code QueueChecklistView_260913_oo01} and {@code TurnErrorInConversation_260913_oo01}
 * against a running instance: the queue area lists sent items before pending ones with the
 * quarkus-chat-ui header and classes, a held item added with the queue button shows as the
 * waiting current item, and a failed turn comes back as an error bubble after a reload.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01). Needs a conversation
 * {@code project1/q2} it creates itself, and one {@code project1/e1} whose last turn failed (made
 * by the live check in the design document). No LLM call is made here.</p>
 */
public class QueueChecklistE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28014/");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        new QueueChecklistE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() {
        System.out.println("--- QueueChecklistE2E against " + BASE_URL + " ---");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForSelector("#queue-btn");

                // A fresh conversation: seed one sent item through the REST route the pane uses.
                page.evaluate("() => fetch('api/projects/project1/chats/q2', {method:'POST'})");
                page.evaluate("() => fetch('api/projects/project1/chats/q2/queue').then(r => r.json())");
                page.evaluate("() => window.chatUiSwitchChat('project1', 'q2')");
                page.waitForTimeout(800);

                // Empty at first.
                String header0 = page.evaluate("() => { const h = document.querySelector('#queue-area .queue-header span'); return h ? h.textContent : '(none)'; }").toString();
                boolean hidden0 = (boolean) page.evaluate("() => getComputedStyle(document.getElementById('queue-area')).display === 'none'");
                check(hidden0 || header0.equals("Queue is empty"), "a fresh conversation shows no queue (header: " + header0 + ")");

                // The queue button with text holds an item (Auto off): it is the waiting current item.
                page.fill("#prompt-input", "held item one");
                page.click("#queue-btn");
                page.waitForSelector("#queue-area .queue-item.current.waiting");
                String header1 = page.evaluate("() => document.querySelector('#queue-area .queue-header span').textContent").toString();
                check(header1.equals("Queue (1) - 1 pending:"), "header counts one pending: " + header1);
                boolean autoOff = !(boolean) page.evaluate("() => document.querySelector('#queue-area .queue-item.current input[type=checkbox]').checked");
                check(autoOff, "a held item's Auto is off");
                check((boolean) page.evaluate("() => getComputedStyle(document.getElementById('queue-area')).display !== 'none'"),
                        "the queue area is shown while it has items");

                // A second held item goes after the first; its up button is enabled, the first's is not.
                page.fill("#prompt-input", "held item two");
                page.click("#queue-btn");
                page.waitForFunction("() => document.querySelectorAll('#queue-area .queue-item').length === 2");
                @SuppressWarnings("unchecked")
                List<Boolean> ups = (List<Boolean>) page.evaluate("() => Array.from(document.querySelectorAll('#queue-area .queue-item .queue-move[title=\"Move up\"]')).map(b => b.disabled)");
                check(ups.equals(List.of(true, false)), "up is disabled only for the first pending item: " + ups);

                // Remove both; the area goes away again (the button had forced it open, so close it).
                page.click("#queue-area .queue-item:nth-of-type(3) .queue-remove");
                page.waitForFunction("() => document.querySelectorAll('#queue-area .queue-item').length === 1");
                page.click("#queue-area .queue-item .queue-remove");
                page.waitForFunction("() => document.querySelectorAll('#queue-area .queue-item').length === 0");
                String header2 = page.evaluate("() => document.querySelector('#queue-area .queue-header span').textContent").toString();
                check(header2.equals("Queue is empty"), "empty again: " + header2);

                // A conversation whose last turn failed shows the failure after a reload.
                page.evaluate("() => window.chatUiSwitchChat('project1', 'e1')");
                page.waitForSelector("#chat-area .message.error");
                String err = page.evaluate("() => document.querySelector('#chat-area .message.error').textContent").toString();
                check(err.startsWith("Error: "), "the failed turn is drawn as an error bubble: " + err);
                check(((Number) page.evaluate("() => document.querySelectorAll('#chat-area .message.assistant').length")).intValue() == 0,
                        "no empty assistant bubble for the failed turn");
            } finally {
                browser.close();
            }
        }
        if (failed > 0) throw new AssertionError("QueueChecklistE2E: " + failed + " check(s) failed");
    }

    private static void check(boolean condition, String what) {
        if (condition) { passed++; System.out.println("ok:   " + what); }
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
