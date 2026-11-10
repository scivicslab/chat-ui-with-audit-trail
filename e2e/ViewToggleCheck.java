import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

/**
 * Browser check of the chat pane's display toggle (ChatDisplayToggle_261110_oo01), run against a throwaway instance
 * that has only chat-01 (it makes chat-02 and chat-03, runs a job that asks both, and sends turns). Java Playwright,
 * outside the Maven build: a main() against a running instance, as the testing standard has it.
 *
 * <pre>
 * java -cp ~/works/w206-e2e-test/target/w206-e2e-test-1.0.0-shaded.jar e2e/ViewToggleCheck.java http://localhost:28975
 * </pre>
 *
 * It exits 0 when every check passes, and writes screenshots to /tmp/uitest/ (create the directory first).
 */
public class ViewToggleCheck {

    static final Page.WaitForSelectorOptions ATTACHED = new Page.WaitForSelectorOptions()
            .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED);

    static String base = "http://localhost:28975";
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

    static void waitIdle(String chat) throws Exception {
        for (int i = 0; i < 240; i++) {
            String s = rest("GET", "/api/projects/project1/chats/" + chat + "/status", null);
            if (s.contains("\"busy\":false")) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("chat " + chat + " stayed busy");
    }

    static int visible(Page page, String selector) {
        int n = 0;
        for (ElementHandle e : page.querySelectorAll(selector)) if (e.isVisible()) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) base = args[0];
        // Two ConversationSquad actors; a job asks both (the ask_chat path: a turn another actor starts).
        String existing = rest("GET", "/api/projects/project1/chats", null);
        if (!existing.contains("03")) {
            rest("POST", "/api/projects/project1/chats", null);
            rest("POST", "/api/projects/project1/chats", null);
            String job = rest("POST", "/api/projects/project1/jobs", "{\"workflow\":\"parallel-workers-plan\"}");
            System.out.println("job: " + job);
            for (int i = 0; i < 240; i++) {
                String j = rest("GET", "/api/projects/project1/jobs/job-01", null);
                if (j.contains("FINISHED") || j.contains("STOPPED") || j.contains("FAILED")) { System.out.println("job end: " + j.substring(0, Math.min(200, j.length()))); break; }
                Thread.sleep(500);
            }
            waitIdle("02");
            // A turn from the REST API (not the screen), on chat-02.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"API turn: reply with the single word OK.\"}");
            Thread.sleep(500);
            waitIdle("02");
        }

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setViewportSize(1400, 900));
            ctx.addInitScript("localStorage.setItem('chat-ui-last-project','project1');"
                    + "if (!localStorage.getItem('chat-ui-last-chat')) localStorage.setItem('chat-ui-last-chat','02');");
            Page page = ctx.newPage();
            page.navigate(base + "/");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);

            // The person types a message in the pane of chat-02.
            check(page.evaluate("window.chatUiCurrentChatId()").equals("02"), "the pane shows chat-02");
            int personBefore = page.querySelectorAll("#chat-area .message.user.turn-person").size();
            int otherBefore = page.querySelectorAll("#chat-area .message.user.turn-other").size();
            System.out.println("before: personUsers=" + personBefore + " otherUsers=" + otherBefore);
            check(personBefore == 0 && otherBefore >= 2, "before the person types, every user bubble is another actor's (job, API)");
            page.fill("#prompt-input", "Person turn: what is 2+2? Answer in one short sentence.");
            page.click("#send-btn");
            page.waitForSelector("#chat-area .message.user.turn-person", ATTACHED);
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-person').length >= 1");
            Thread.sleep(500);

            int allBubbles = page.querySelectorAll("#chat-area .message").size();
            int personUsers = page.querySelectorAll("#chat-area .message.user.turn-person").size();
            int otherUsers = page.querySelectorAll("#chat-area .message.user.turn-other").size();
            System.out.println("bubbles=" + allBubbles + " personUsers=" + personUsers + " otherUsers=" + otherUsers);
            check(personUsers == 1, "one user bubble opened by the person (turn-person)");
            check(otherUsers == otherBefore, "the others' user bubbles are the same as before: " + otherUsers);
            check(page.querySelectorAll("#chat-area .message.assistant.turn-other").size() >= 2,
                    "their answers carry turn-other too");
            String btn = page.textContent("#view-mode-btn");
            check("All".equals(btn.strip()), "the button starts as 'All': " + btn);
            check(visible(page, "#chat-area .message") == allBubbles, "in All every bubble is visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/1-all.png")));

            // Toggle to Person only.
            page.click("#view-mode-btn");
            check("Person only".equals(page.textContent("#view-mode-btn").strip()), "the button now reads 'Person only'");
            check("true".equals(page.getAttribute("#view-mode-btn", "aria-pressed")), "aria-pressed is true");
            check(visible(page, "#chat-area .message.turn-other") == 0, "no turn-other bubble is visible");
            int personVisible = visible(page, "#chat-area .message.turn-person");
            check(personVisible >= 2 && personVisible == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "the person's turn (input and answer) is visible: " + personVisible);
            check(page.querySelectorAll("#chat-area .message").size() == allBubbles, "nothing was deleted: still " + allBubbles + " bubbles");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/2-person-only.png")));

            // A live bubble that arrives while in Person only follows the same rule.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"Second API turn while hidden: reply OK.\"}");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-other').length >= " + (otherUsers + 1));
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-other').length >= " + (otherBefore + 1));
            check(visible(page, "#chat-area .message.turn-other") == 0, "a live turn from the API arrives hidden");
            page.fill("#prompt-input", "Second person turn: name one prime number.");
            page.click("#send-btn");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-person').length >= 2");
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-person').length >= 2");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "a live turn the person starts is visible");
            check(visible(page, "#chat-area .message.turn-other") == 0, "and the others are still hidden");

            // Reload: the choice and the marks come back from storage and from the restored history.
            int total = page.querySelectorAll("#chat-area .message").size();
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("Person only".equals(page.textContent("#view-mode-btn").strip()), "after reload the button still reads 'Person only'");
            check(page.querySelectorAll("#chat-area .message.user.turn-person").size() == 2
                            && page.querySelectorAll("#chat-area .message.user.turn-other").size() == otherBefore + 1,
                    "restored history marks the turns the same way");
            check(visible(page, "#chat-area .message.turn-other") == 0, "after reload the other turns are hidden");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "after reload the person's turns are visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/3-reloaded.png")));

            // Another chat has its own choice.
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("All".equals(page.textContent("#view-mode-btn").strip()), "chat-03 has its own choice: 'All'");
            check(visible(page, "#chat-area .message.turn-other") >= 2, "chat-03 shows its job turn");
            page.evaluate("window.chatUiSwitchChat('project1','02')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("Person only".equals(page.textContent("#view-mode-btn").strip()), "back on chat-02 the choice is 'Person only'");
            check(visible(page, "#chat-area .message.turn-other") == 0, "and its other turns are hidden");

            // Back to All.
            page.click("#view-mode-btn");
            check("All".equals(page.textContent("#view-mode-btn").strip()), "toggled back to 'All'");
            check(visible(page, "#chat-area .message") == page.querySelectorAll("#chat-area .message").size(), "everything is visible again");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/4-all-again.png")));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
