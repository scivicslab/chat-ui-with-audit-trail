package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E2E check of the right pane's Tokens tab: for a conversation on the openai-compat provider (answered by
 * {@code e2e/ScriptedModelServer.java}) and for one on Claude Code (answered by {@code e2e/fake-claude/claude}),
 * the tab shows the parts of the head block with who may cut each, the estimate beside the measured first
 * request, and the first request of each turn with its growth. It has no button that cuts anything.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01), against a throwaway instance
 * started for it, never a live one. The instance needs the scripted model, the fake CLI first on its PATH, and the
 * harness plugin; its conversations are made here, so it should have only {@code project1/chat-01}:</p>
 *
 * <pre>
 * java e2e/ScriptedModelServer.java 28978 &amp;
 * PATH=$HOME/works/chat-ui-with-audit-trail/e2e/fake-claude:$PATH java \
 *     -Dquarkus.http.port=28979 -Dchat-ui.servers=http://127.0.0.1:28978 -Dchat-ui.default-model=fake \
 *     -Dchat-ui.plugins=chat-ui-with-audit-trail-plugin-harness.jar -jar app/target/quarkus-app/quarkus-run.jar &amp;
 * CHAT_UI_URL=http://localhost:28979/ java -cp ... com.scivicslab.chatui.e2e.TokensTabE2E
 * </pre>
 *
 * Exits 1 when a check fails; writes screenshots to {@code /tmp/uitest/}.
 */
public class TokensTabE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28979/");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        new TokensTabE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() throws Exception {
        System.out.println("--- TokensTabE2E against " + BASE_URL + " ---");
        Files.createDirectories(Path.of("/tmp/uitest"));
        // chat-01 stays on openai-compat; chat-02 is switched to Claude Code. Each gets two turns, so the per-turn table has a growth.
        rest("POST", "/api/projects/project1/chats", null);
        String switched = rest("POST", "/api/projects/project1/chats/02/provider", "{\"provider\":\"claude\"}");
        check(switched.contains("\"accepted\""), "chat-02 was switched to Claude Code: " + switched);
        for (String chat : List.of("01", "02")) {
            for (String text : List.of("HELLO-ONLY first turn", "HELLO-ONLY second turn")) {
                rest("POST", "/api/projects/project1/chats/" + chat + "/chat", "{\"text\":\"" + text + "\"}");
                waitIdle(chat);
            }
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                checkTab(page, "01", "chat-ui's own MCP server", "openai-compat", 1000);
                checkTab(page, "02", "CLI built-in prompt and tools (not measurable here)", "claude", 41000);
            } finally {
                browser.close();
            }
        }
    }

    private void checkTab(Page page, String chat, String expectedPart, String label, int minFirstRequest) {
        page.evaluate("() => window.chatUiSwitchChat('project1', '" + chat + "')");
        page.selectOption("#right-tab-select", "tokens");
        // chatUiSwitchChat does not go through the tree's click that reloads the open tab, so the person's Refresh is pressed.
        page.locator("#tokens-refresh").click();
        check(page.locator("#tab-tokens").getAttribute("class").contains("active"), label + ": the Tokens section is shown");
        page.locator("#tokens-parts table").first().waitFor();
        page.locator("#tokens-turns table").first().waitFor();
        String parts = page.locator("#tokens-parts").textContent();
        check(parts.contains(expectedPart), label + ": the parts table lists '" + expectedPart + "'");
        check(parts.contains("Estimated total of the parts above"), label + ": the estimate is shown");
        check(parts.contains("Measured first request") && !parts.contains("not recorded"),
                label + ": the measured first request is shown beside it");
        check(parts.contains("Measured minus estimated"), label + ": the difference is shown");
        check(page.locator("#tab-tokens button").count() == 1,
                label + ": the tab has only its Refresh button, nothing that cuts");
        int rows = page.locator("#tokens-turns tr").count() - 1;
        check(rows >= 2, label + ": the per-turn table has a row for each of two turns (" + rows + ")");
        if (rows >= 2) {
            int first = Integer.parseInt(page.locator("#tokens-turns tr").nth(1).locator("td").nth(1).textContent().strip());
            check(first >= minFirstRequest, label + ": turn 1's first request is at least " + minFirstRequest + ": " + first);
            String growth = page.locator("#tokens-turns tr").nth(2).locator("td").nth(2).textContent().strip();
            check(growth.startsWith("+"), label + ": turn 2 shows its growth: " + growth);
        }
        page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/tokens-" + label + ".png")));
    }

    private static String rest(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL.replaceAll("/$", "") + path))
                .header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void waitIdle(String chat) throws Exception {
        Thread.sleep(500);
        for (int i = 0; i < 240; i++) {
            if (rest("GET", "/api/projects/project1/chats/" + chat + "/status", null).contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
        check(false, "chat-" + chat + " did not become idle");
    }

    private static void check(boolean condition, String what) {
        if (condition) { passed++; System.out.println("ok:   " + what); }
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
