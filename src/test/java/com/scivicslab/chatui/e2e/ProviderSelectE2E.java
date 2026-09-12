package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.List;

/**
 * E2E check of {@code CliHarnessProvider_260912_oo01}'s provider dropdown against a running
 * instance: the dropdown shows the provider the server reports, choosing another one switches the
 * conversation on the server, and the model list is reloaded from the new provider.
 *
 * <p>A {@code main()} program outside the build (TestingStandard_260404_oo01): it drives a live
 * instance it does not create. {@code CHAT_UI_URL} names the instance. Switching to Claude Code
 * starts no process — a harness provider starts one on its first prompt — so this needs no
 * Claude authentication, only the binary being irrelevant to the switch itself.</p>
 */
public class ProviderSelectE2E {

    private static final String BASE_URL =
            System.getenv().getOrDefault("CHAT_UI_URL", "http://localhost:28039/");

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        new ProviderSelectE2E().run();
        System.out.printf("%nResults: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    void run() {
        System.out.println("--- ProviderSelectE2E against " + BASE_URL + " ---");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.onPageError(e -> check(false, "page error: " + e));
                page.navigate(BASE_URL, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForSelector("#provider-select");
                page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0");

                String initial = page.evaluate("() => document.getElementById('provider-select').value").toString();
                String statusProvider = statusField(page, "provider");
                String statusTools = statusField(page, "tools");
                String expected = statusTools.equals(statusProvider.equals("openai-compat") ? "full" : "collaboration")
                        ? statusProvider : statusProvider + ":" + statusTools;
                check(initial.equals(expected), "dropdown shows what the server reports: " + initial + " vs " + expected);

                switchTo(page, "openai-compat");
                check("openai-compat".equals(statusField(page, "provider")), "server switched to openai-compat");
                check("full".equals(statusField(page, "tools")), "openai-compat conversation has the full tool set");
                List<String> localModels = modelOptions(page);
                check(!localModels.contains("sonnet"), "model list is the local provider's: " + localModels);

                switchTo(page, "claude");
                check("claude".equals(statusField(page, "provider")), "server switched to claude");
                check("collaboration".equals(statusField(page, "tools")), "claude conversation has the collaboration tool set");
                List<String> claudeModels = modelOptions(page);
                check(claudeModels.contains("sonnet"), "model list reloaded from Claude Code: " + claudeModels);
                check("claude".equals(page.evaluate("() => document.getElementById('provider-select').value")),
                        "dropdown stays on claude");

                switchTo(page, "claude:full");
                check("full".equals(statusField(page, "tools")), "claude:full gives the bare model the full tool set");
                check("claude:full".equals(page.evaluate("() => document.getElementById('provider-select').value")),
                        "dropdown shows claude:full");
            } finally {
                browser.close();
            }
        }
        if (failed > 0) throw new AssertionError("ProviderSelectE2E: " + failed + " check(s) failed");
    }

    /** The conversation a fresh browser opens: project1/01 (app.js's default when nothing is stored). */
    private static final String STATUS_URL = "api/projects/project1/chats/01/status";

    private static void switchTo(Page page, String value) {
        page.selectOption("#provider-select", value);
        // The change handler posts, then reloads the models; wait until the server agrees.
        String kind = value.split(":")[0];
        page.waitForFunction("async () => (await (await fetch('" + STATUS_URL + "')).json()).provider === '" + kind + "'",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
        page.waitForFunction("() => document.querySelectorAll('#model-select option').length > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
        page.waitForTimeout(500);
    }

    private static String statusField(Page page, String field) {
        return page.evaluate("async () => (await (await fetch('" + STATUS_URL + "')).json())." + field).toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> modelOptions(Page page) {
        return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll('#model-select option')).map(o => o.value)");
    }

    private static void check(boolean condition, String what) {
        if (condition) { passed++; System.out.println("ok:   " + what); }
        else { failed++; System.out.println("FAIL: " + what); }
    }
}
