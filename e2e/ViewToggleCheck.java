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
 * that has only chat-01 (it makes chat-02 and chat-03, runs a job that asks both, and sends turns, one of which has
 * chat-02 ask chat-03 so that the reply starts a turn of chat-02). The model is e2e/ScriptedModelServer.java, started
 * first. Java Playwright,
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

    /** What the filter hides: the inputs and steps of the turns the person did not start. */
    static final String HIDDEN = "#chat-area .message.turn-other:not(.turn-answer)";
    /** What it keeps of those turns: each one's final answer. */
    static final String OTHER_ANSWERS = "#chat-area .message.turn-other.turn-answer";

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
            check(page.querySelectorAll(OTHER_ANSWERS).size() == page.querySelectorAll("#chat-area .message.assistant.turn-other").size(),
                    "and each of those answers is marked as its turn's final answer (turn-answer)");
            String btn = page.textContent("#view-mode-btn");
            check("Show all messages".equals(btn.strip()), "the button starts as 'Show all messages': " + btn);
            check(visible(page, "#chat-area .message") == allBubbles, "in 'Show all messages' every bubble is visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/1-all.png")));

            // Toggle to "Show person-facing messages only".
            page.click("#view-mode-btn");
            check("Show person-facing messages only".equals(page.textContent("#view-mode-btn").strip()), "the button now reads 'Show person-facing messages only'");
            check("true".equals(page.getAttribute("#view-mode-btn", "aria-pressed")), "aria-pressed is true");
            check(page.querySelectorAll(HIDDEN).size() >= 2 && visible(page, HIDDEN) == 0,
                    "no input or step of another actor's turn is visible");
            int otherAnswers = page.querySelectorAll(OTHER_ANSWERS).size();
            check(otherAnswers >= 2 && visible(page, OTHER_ANSWERS) == otherAnswers,
                    "every final answer of another actor's turn stays visible: " + otherAnswers);
            int personVisible = visible(page, "#chat-area .message.turn-person");
            check(personVisible >= 2 && personVisible == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "the person's turn (input and answer) is visible: " + personVisible);
            check(page.querySelectorAll("#chat-area .message").size() == allBubbles, "nothing was deleted: still " + allBubbles + " bubbles");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/2-person-only.png")));

            // A live turn that arrives while in "Show person-facing messages only" follows the same rule.
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"Second API turn while hidden: reply OK.\"}");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-other').length >= " + (otherUsers + 1));
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-other').length >= " + (otherBefore + 1));
            check(visible(page, HIDDEN) == 0, "a live turn from the API: its input arrives hidden");
            check(visible(page, OTHER_ANSWERS) == page.querySelectorAll(OTHER_ANSWERS).size()
                            && page.querySelectorAll(OTHER_ANSWERS).size() == otherAnswers + 1,
                    "and its final answer arrives visible");
            page.fill("#prompt-input", "Second person turn: name one prime number.");
            page.click("#send-btn");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.user.turn-person').length >= 2");
            waitIdle("02");
            page.waitForFunction("document.querySelectorAll('#chat-area .message.assistant.turn-person').length >= 2");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "a live turn the person starts is visible");
            check(visible(page, HIDDEN) == 0, "and the others' inputs and steps are still hidden");

            // A turn that a reply starts, as the liaison's are: chat-02 asks chat-03 (an API turn), and chat-03's
            // reply starts a turn of chat-02 whose final answer is written to the person. Its input (the reply)
            // and its steps (the accept_reply call) are hidden; its final answer is shown. Every bubble of those
            // turns that is not a final answer is recorded as it arrives, to see that none was shown even while
            // the turns ran.
            page.evaluate("() => { window.__stepsSeen = 0; window.__stepsShown = 0;"
                    + " new MutationObserver(ms => ms.forEach(m => m.addedNodes.forEach(n => {"
                    + "   if (!n.classList || !n.classList.contains('turn-other') || n.classList.contains('turn-answer')) return;"
                    + "   window.__stepsSeen++; if (getComputedStyle(n).display !== 'none') window.__stepsShown++; })))"
                    + " .observe(document.getElementById('chat-area'), { childList: true }); }");
            rest("POST", "/api/projects/project1/chats/02/chat", "{\"text\":\"SCENARIO-R ask chat-03 for the result.\"}");
            page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-area .message.assistant'))"
                    + ".some(e => e.textContent.includes('FINAL-FOR-PERSON'))", null, new Page.WaitForFunctionOptions().setTimeout(120_000));
            waitIdle("02");
            Thread.sleep(500);
            ElementHandle replyInput = null, finalAnswer = null;
            for (ElementHandle e : page.querySelectorAll("#chat-area .message.user")) {
                if (e.textContent().startsWith("[reply to request ")) replyInput = e;
            }
            for (ElementHandle e : page.querySelectorAll("#chat-area .message.assistant")) {
                if (e.textContent().contains("FINAL-FOR-PERSON")) finalAnswer = e;
            }
            check(replyInput != null && "true".equals(String.valueOf(replyInput.evaluate("e => e.classList.contains('turn-other')")))
                            && !replyInput.isVisible(),
                    "the reply that started chat-02's turn is hidden");
            check(finalAnswer != null && "true".equals(String.valueOf(finalAnswer.evaluate("e => e.classList.contains('turn-answer') && e.classList.contains('turn-other')")))
                            && finalAnswer.isVisible(),
                    "that turn's final answer, addressed to the person, is visible");
            Number seen = (Number) page.evaluate("() => window.__stepsSeen");
            Number shown = (Number) page.evaluate("() => window.__stepsShown");
            check(seen.intValue() >= 2 && shown.intValue() == 0,
                    "no input or step of those turns was shown while they ran: " + seen + " arrived, " + shown + " shown");
            check(visible(page, HIDDEN) == 0, "after those turns, no input or step of another actor's turn is visible");
            if (finalAnswer != null) finalAnswer.scrollIntoViewIfNeeded();
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/2b-reply-answer.png")));
            int otherUsersBeforeReload = page.querySelectorAll("#chat-area .message.user.turn-other").size();

            // Reload: the choice and the marks come back from storage and from the restored history.
            int total = page.querySelectorAll("#chat-area .message").size();
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("Show person-facing messages only".equals(page.textContent("#view-mode-btn").strip()), "after reload the button still reads 'Show person-facing messages only'");
            check(page.querySelectorAll("#chat-area .message.user.turn-person").size() == 2
                            && page.querySelectorAll("#chat-area .message.user.turn-other").size() == otherUsersBeforeReload,
                    "restored history marks the turns the same way");
            check(visible(page, HIDDEN) == 0, "after reload the other turns' inputs are hidden");
            check(page.querySelectorAll(OTHER_ANSWERS).size() >= 4 && visible(page, OTHER_ANSWERS) == page.querySelectorAll(OTHER_ANSWERS).size(),
                    "after reload the other turns' final answers are visible, the reply turn's among them: "
                            + page.querySelectorAll(OTHER_ANSWERS).size());
            check(page.querySelectorAll(OTHER_ANSWERS).stream().anyMatch(e -> e.textContent().contains("FINAL-FOR-PERSON") && e.isVisible()),
                    "after reload the reply turn's final answer is still visible");
            check(visible(page, "#chat-area .message.turn-person") == page.querySelectorAll("#chat-area .message.turn-person").size(),
                    "after reload the person's turns are visible");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/3-reloaded.png")));

            // Another chat has its own choice.
            page.evaluate("window.chatUiSwitchChat('project1','03')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("Show all messages".equals(page.textContent("#view-mode-btn").strip()), "chat-03 has its own choice: 'Show all messages'");
            check(visible(page, "#chat-area .message.turn-other") >= 2, "chat-03 shows its job turn");
            page.evaluate("window.chatUiSwitchChat('project1','02')");
            page.waitForSelector("#chat-area .message.user", ATTACHED);
            check("Show person-facing messages only".equals(page.textContent("#view-mode-btn").strip()), "back on chat-02 the choice is 'Show person-facing messages only'");
            check(visible(page, HIDDEN) == 0, "and its other turns' inputs are hidden");

            // Back to All.
            page.click("#view-mode-btn");
            check("Show all messages".equals(page.textContent("#view-mode-btn").strip()), "toggled back to 'Show all messages'");
            check(visible(page, "#chat-area .message") == page.querySelectorAll("#chat-area .message").size(), "everything is visible again");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("/tmp/uitest/4-all-again.png")));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
