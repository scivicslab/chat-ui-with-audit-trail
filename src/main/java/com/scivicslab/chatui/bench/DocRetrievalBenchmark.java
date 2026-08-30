package com.scivicslab.chatui.bench;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Measures how well a conversation's agent loop finds the documents it needs from html-saurus
 * ({@code DocRetrievalBenchmark_260830_oo01}).
 *
 * <p>Not a JUnit test: it drives an already-running instance and does not set its environment up,
 * which is what the project's testing standard calls an E2E test ({@code
 * TestingStandard_260404_oo01}). Run it against a deployed jar:</p>
 *
 * <pre>{@code
 * javac -cp ~/works/chat-ui-with-audit-trail.jar \
 *     src/main/java/com/scivicslab/chatui/bench/DocRetrievalBenchmark.java -d /tmp/bench
 * java -cp /tmp/bench:~/works/chat-ui-with-audit-trail.jar \
 *     com.scivicslab.chatui.bench.DocRetrievalBenchmark [baseline|prompt|statemachine]
 * }</pre>
 *
 * <p>Scoring reads the I/O log rather than the answer text: {@code recordToolIo} records every tool
 * call's input and observation, so what the agent actually looked at is known whether or not it
 * chose to cite anything.</p>
 */
public final class DocRetrievalBenchmark {

    private static final String CHAT_UI = envOr("CHATUI_URL", "http://localhost:28014");
    private static final String SAURUS = envOr("DOCSEARCH_URL", "http://localhost:28001");
    private static final String PROJECT_ID = envOr("BENCH_PROJECT", "project1");
    /** Generous: one task is a whole multi-step turn with searches and reads in it. */
    private static final int TASK_TIMEOUT_SECONDS = 300;
    /** Where the answer-correctness judge runs — the same OpenAI-compatible server the conversations use. */
    private static final String JUDGE_URL =
            envOr("BENCH_JUDGE_URL", "http://localhost:28005/v1/chat/completions");
    private static final String JUDGE_MODEL = envOr("BENCH_JUDGE_MODEL", "google/gemma-4-26B-A4B-it");
    /**
     * Distinguishes this run's conversations from an earlier run's. Without it a re-run reuses the
     * conversation of the same name, whose history already holds the previous run's answer — the
     * agent could then repeat that answer without searching at all.
     */
    private static final String RUN_ID = String.valueOf(System.currentTimeMillis() % 1_000_000);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private DocRetrievalBenchmark() {}

    public static void main(String[] args) throws Exception {
        String variant = args.length > 0 ? args[0] : "baseline";
        if (!variant.equals("baseline") && !variant.equals("prompt")) {
            System.err.println("unknown variant '" + variant + "' (baseline | prompt)");
            System.exit(2);
        }
        // A dead embedding server makes html-saurus return an empty array rather than fail, which
        // would score every variant equally badly for a reason that has nothing to do with the
        // agent loop. Refuse to measure until search is known to answer.
        if (!searchIsAnswering()) {
            System.err.println("html-saurus at " + SAURUS + " returned no hits for a known term — "
                    + "not measuring. Check the embedding server and the index.");
            System.exit(1);
        }

        List<Task> tasks = loadTasks();
        System.out.println("variant=" + variant + "  tasks=" + tasks.size()
                + "  chat-ui=" + CHAT_UI + "  html-saurus=" + SAURUS);
        System.out.println();

        List<Result> results = new ArrayList<>();
        for (Task task : tasks) {
            System.out.print("running " + task.id + " ... ");
            System.out.flush();
            Result r = run(task, variant);
            results.add(r);
            System.out.println((r.answerHit ? "PASS" : "FAIL")
                    + "  " + (r.readHit ? "read-hit" : (r.searchHit ? "search-hit only" : "miss"))
                    + "  steps=" + r.steps + " searches=" + r.searches + " " + r.seconds + "s"
                    + (r.truncated ? " (step limit)" : ""));
        }
        report(variant, results);
    }

    // ── running one task ──────────────────────────────────────────────────────

    private static Result run(Task task, String variant) throws Exception {
        // A fresh conversation per task: reusing one would leave the previous answer in history,
        // and the agent could answer from that instead of searching.
        String chatId = "bench-" + variant + "-" + RUN_ID + "-" + task.id;
        String chatBase = CHAT_UI + "/api/projects/" + PROJECT_ID + "/chats/" + enc(chatId);
        post(chatBase, "");
        // 型2 (DocRetrievalAgentLoop_260830_oo01): same agent loop, different text at every step.
        if (variant.equals("prompt")) {
            post(chatBase + "/prompt-workflow", "prompt-construction-doc-retrieval.yaml");
        }

        long start = System.currentTimeMillis();
        postJson(chatBase + "/chat", new JSONObject().put("text", task.question).toString());
        boolean finished = waitUntilIdle(chatBase);
        long seconds = (System.currentTimeMillis() - start) / 1000;

        Trace trace = readTrace(PROJECT_ID + "/chat-" + chatId);
        Result r = new Result();
        r.task = task;
        r.seconds = seconds;
        r.steps = trace.steps;
        r.searches = trace.searches;
        r.truncated = !finished || trace.stepLimitReached;
        for (String want : task.answerDocs) {
            if (mentions(trace.readInputs, want)) r.readHit = true;
            if (mentions(trace.searchObservations, want)) r.searchHit = true;
        }
        r.answer = lastAssistantMessage(chatBase);
        if (!task.answerKey.isEmpty()) {
            String verdict = judge(task, r.answer);
            r.answerHit = verdict.startsWith("PASS");
            r.judgeReason = verdict;
        }
        return r;
    }

    /** @return {@code false} if the conversation was still busy when we gave up waiting */
    private static boolean waitUntilIdle(String chatBase) throws Exception {
        for (int i = 0; i < TASK_TIMEOUT_SECONDS / 5; i++) {
            Thread.sleep(5000);
            JSONObject status = new JSONObject(get(chatBase + "/status"));
            if (!status.optBoolean("busy", false)) return true;
        }
        return false;
    }

    /** @return the conversation's most recent assistant message, or {@code ""} */
    private static String lastAssistantMessage(String chatBase) {
        try {
            JSONArray msgs = new JSONArray(get(chatBase + "/conversation"));
            for (int i = msgs.length() - 1; i >= 0; i--) {
                JSONObject m = msgs.getJSONObject(i);
                if ("assistant".equals(m.optString("role"))) return m.optString("content", "");
            }
        } catch (Exception e) {
            System.err.println("could not read the answer: " + e.getMessage());
        }
        return "";
    }

    /**
     * Asks an LLM whether the answer says what the task's answer key requires.
     *
     * <p>Reading the right document and answering correctly are different things, and once
     * retrieval works they come apart: a loop told to open three documents scores a read-hit
     * whether or not it understood any of them. This is the {@code LLMJudge} grader named in
     * {@code AIBenchmarkVisionStatement_260805_oo01}.</p>
     *
     * @param task   the question and its answer key
     * @param answer what the conversation replied
     * @return {@code "PASS"} or {@code "FAIL"} followed by one sentence of reason
     */
    private static String judge(Task task, String answer) {
        if (answer == null || answer.isBlank()) return "FAIL: no answer";
        String prompt = "次の解答が、採点基準を満たしているかを判定してください。\n\n"
                + "【設問】\n" + task.question + "\n\n"
                + "【採点基準】\n" + task.answerKey + "\n\n"
                + "【解答】\n" + answer + "\n\n"
                + "基準を満たしていれば PASS、満たしていなければ FAIL とだけ最初に書き、"
                + "その後に理由を1文で書いてください。言い回しの違いは問いません。"
                + "基準が求めている事実がすべて含まれているかどうかだけを見てください。";
        try {
            JSONObject body = new JSONObject()
                    .put("model", JUDGE_MODEL)
                    .put("temperature", 0)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "user").put("content", prompt)));
            String res = postJson(JUDGE_URL, body.toString());
            String text = new JSONObject(res).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").strip();
            return text.length() > 200 ? text.substring(0, 200) : text;
        } catch (Exception e) {
            return "FAIL: judge call failed: " + e.getMessage();
        }
    }

    // ── scoring from the I/O log ──────────────────────────────────────────────

    /** What one task's turn actually did, taken from the I/O log rather than from its answer. */
    private static final class Trace {
        final List<String> readInputs = new ArrayList<>();
        final List<String> searchObservations = new ArrayList<>();
        int steps;
        int searches;
        boolean stepLimitReached;
    }

    private static Trace readTrace(String qualifiedChatName) throws Exception {
        Trace t = new Trace();
        JSONArray sessions = new JSONArray(
                get(CHAT_UI + "/api/sessions?tabId=" + enc(qualifiedChatName)));
        if (sessions.isEmpty()) return t;
        // Most recent session for this conversation; it was created fresh for this task.
        long sessionId = sessions.getJSONObject(0).getLong("sessionId");

        JSONArray turns = new JSONArray(get(CHAT_UI + "/api/sessions/" + sessionId + "/trace"));
        Set<String> stepLabels = new LinkedHashSet<>();
        for (int i = 0; i < turns.length(); i++) {
            JSONArray steps = turns.getJSONObject(i).optJSONArray("steps");
            if (steps == null) continue;
            for (int j = 0; j < steps.length(); j++) {
                JSONObject step = steps.getJSONObject(j);
                stepLabels.add(step.optString("label", ""));
                String tool = step.optString("toolName", "");
                if (tool.equals("read")) {
                    t.readInputs.add(step.optString("toolInput", "") + "\n"
                            + fullObservation(sessionId, step));
                } else if (tool.equals("search_docs")) {
                    t.searches++;
                    t.searchObservations.add(fullObservation(sessionId, step));
                }
                if (step.optString("finalAnswer", "").contains("step limit")) t.stepLimitReached = true;
            }
        }
        // One agent-loop step writes one "turnN/stepM/llm" label; count those, not the tool records.
        t.steps = (int) stepLabels.stream().filter(l -> l.endsWith("/llm")).count();
        return t;
    }

    /**
     * The whole observation, fetched from the log entry the trace step points at.
     *
     * <p>The trace's own {@code observation} field is a 240-character digest for the Sessions view
     * ({@code IoLogView.OBS_DIGEST}); a search observation is around 7000 characters, so scoring
     * against the digest asks only whether the answer was the first hit. The full text is in the
     * log database and comes back from {@code /api/sessions/{id}/entry/{logId}}.</p>
     *
     * @param sessionId the I/O-log session the step belongs to
     * @param step      one trace step
     * @return the observation as the tool produced it, or the digest if the entry cannot be read
     */
    private static String fullObservation(long sessionId, JSONObject step) {
        long logId = step.optLong("id", -1);
        if (logId < 0) return step.optString("observation", "");
        try {
            JSONObject entry = new JSONObject(
                    get(CHAT_UI + "/api/sessions/" + sessionId + "/entry/" + logId));
            String message = entry.optString("message", "");
            return message.isEmpty() ? step.optString("observation", "") : message;
        } catch (Exception e) {
            System.err.println("could not read log entry " + logId + ": " + e.getMessage());
            return step.optString("observation", "");
        }
    }

    /**
     * A document counts as reached when its id appears in what the agent read or in what search
     * handed back — ids are unique strings, so a substring match is enough and does not depend on
     * the agent formatting a citation.
     */
    private static boolean mentions(List<String> texts, String docId) {
        for (String s : texts) {
            if (s != null && s.contains(docId)) return true;
        }
        return false;
    }

    // ── reporting ─────────────────────────────────────────────────────────────

    private static void report(String variant, List<Result> results) {
        int readHits = 0, searchHits = 0, answerHits = 0, truncated = 0, steps = 0, searches = 0;
        long seconds = 0;
        for (Result r : results) {
            if (r.readHit) readHits++;
            if (r.searchHit) searchHits++;
            if (r.answerHit) answerHits++;
            if (r.truncated) truncated++;
            steps += r.steps;
            searches += r.searches;
            seconds += r.seconds;
        }
        int n = results.size();
        System.out.println();
        System.out.printf("%-24s %-8s %-8s %-8s %6s %9s %6s%n",
                "task", "answer", "read", "search", "steps", "searches", "sec");
        for (Result r : results) {
            System.out.printf("%-24s %-8s %-8s %-8s %6d %9d %6d%s%n",
                    r.task.id, r.answerHit ? "PASS" : "FAIL",
                    r.readHit ? "hit" : "miss", r.searchHit ? "hit" : "miss",
                    r.steps, r.searches, r.seconds, r.truncated ? "  (step limit)" : "");
        }
        System.out.println();
        for (Result r : results) {
            if (!r.answerHit && !r.judgeReason.isEmpty()) {
                System.out.println("  " + r.task.id + ": " + r.judgeReason);
            }
        }
        System.out.println();
        System.out.printf("variant=%s  answer %d/%d  read-hit %d/%d  search-hit %d/%d  "
                        + "avg steps %.1f  avg searches %.1f  avg %.0fs  step-limited %d%n",
                variant, answerHits, n, readHits, n, searchHits, n,
                n == 0 ? 0.0 : (double) steps / n,
                n == 0 ? 0.0 : (double) searches / n,
                n == 0 ? 0.0 : (double) seconds / n,
                truncated);
        System.out.println("Compare variants only within one run against the same corpus — "
                + "documents are added and edited, so yesterday's numbers are not this run's baseline.");
    }

    // ── inputs ────────────────────────────────────────────────────────────────

    /** One question and the documents whose content answers it. */
    private static final class Task {
        String id;
        String question;
        String answerKey;
        List<String> answerDocs = new ArrayList<>();
    }

    private static final class Result {
        Task task;
        boolean readHit;
        boolean searchHit;
        boolean answerHit;
        String answer = "";
        String judgeReason = "";
        boolean truncated;
        int steps;
        int searches;
        long seconds;
    }

    private static List<Task> loadTasks() throws Exception {
        try (InputStream in = DocRetrievalBenchmark.class
                .getResourceAsStream("/benchmarks/doc-retrieval-tasks.json")) {
            if (in == null) throw new IllegalStateException("task set not found on the classpath");
            JSONArray arr = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getJSONArray("tasks");
            List<Task> tasks = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.getString("id");
                t.question = o.getString("question");
                t.answerKey = o.optString("answer_key", "");
                JSONArray docs = o.getJSONArray("answer_docs");
                for (int j = 0; j < docs.length(); j++) t.answerDocs.add(docs.getString(j));
                tasks.add(t);
            }
            return tasks;
        }
    }

    private static boolean searchIsAnswering() {
        try {
            String body = get(SAURUS + "/api/search-semantic?q=" + enc("POJO-actor アクター"));
            if (!new JSONArray(body).isEmpty()) return true;
            return !new JSONArray(get(SAURUS + "/api/search?q=" + enc("POJO-actor"))).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private static String get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private static String post(String url, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private static String postJson(String url, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private static String send(HttpRequest.Builder b) throws Exception {
        HttpResponse<String> res = HTTP.send(b.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return res.body();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
