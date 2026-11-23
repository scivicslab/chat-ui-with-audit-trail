package com.scivicslab.chatui.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.chatui.core.provider.LlmProvider.ModelEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads the model picker catalog from Codex's app-server {@code model/list} method. */
final class CodexModelCatalog {

    private static final Logger logger = Logger.getLogger(CodexModelCatalog.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    record Snapshot(List<ModelEntry> models, String defaultModel) {
        Snapshot {
            models = List.copyOf(models);
        }

        static Snapshot empty() { return new Snapshot(List.of(), null); }
    }

    private final List<String> command;
    private final Duration timeout;
    private final Duration ttl;
    private volatile Snapshot cached;
    private volatile long cachedAtNanos;

    CodexModelCatalog() {
        this(List.of("codex", "app-server", "--listen", "stdio://"), DEFAULT_TIMEOUT, DEFAULT_TTL);
    }

    CodexModelCatalog(List<String> command, Duration timeout, Duration ttl) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.ttl = ttl;
    }

    synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (cached != null && now - cachedAtNanos < ttl.toNanos()) return cached;
        try {
            Snapshot fresh = query();
            cached = fresh;
            cachedAtNanos = now;
            return fresh;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not read Codex's model catalog; Codex will choose its own default model", e);
            return cached == null ? Snapshot.empty() : cached;
        }
    }

    private Snapshot query() throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        FutureTask<Snapshot> exchange = new FutureTask<>(() -> exchange(process));
        Thread.ofVirtual().name("codex-model-list").start(exchange);
        try {
            return exchange.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            exchange.cancel(true);
            process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private Snapshot exchange(Process process) throws IOException {
        try (BufferedWriter in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            write(in, request(1, "initialize", MAPPER.createObjectNode()
                    .set("clientInfo", MAPPER.createObjectNode()
                            .put("name", "chat-ui-with-audit-trail")
                            .put("version", "3.0.0"))));
            readResult(out, 1);

            List<ModelEntry> models = new ArrayList<>();
            String defaultModel = null;
            String cursor = null;
            int requestId = 2;
            do {
                ObjectNode params = MAPPER.createObjectNode().put("includeHidden", false);
                if (cursor != null) params.put("cursor", cursor);
                write(in, request(requestId, "model/list", params));
                JsonNode result = readResult(out, requestId++);
                Snapshot page = parseModelList(result);
                models.addAll(page.models());
                if (defaultModel == null) defaultModel = page.defaultModel();
                JsonNode next = result.get("nextCursor");
                cursor = next == null || next.isNull() ? null : next.asText();
            } while (cursor != null && !cursor.isBlank());
            return new Snapshot(models, defaultModel);
        }
    }

    private static ObjectNode request(int id, String method, JsonNode params) {
        return MAPPER.createObjectNode().put("id", id).put("method", method).set("params", params);
    }

    private static void write(BufferedWriter writer, JsonNode request) throws IOException {
        writer.write(request.toString());
        writer.newLine();
        writer.flush();
    }

    private static JsonNode readResult(BufferedReader reader, int requestId) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode response;
            try {
                response = MAPPER.readTree(line);
            } catch (Exception ignored) {
                continue;
            }
            if (!response.has("id") || response.path("id").asInt(-1) != requestId) continue;
            if (response.has("error")) {
                throw new IOException("Codex app-server returned: " + response.get("error"));
            }
            JsonNode result = response.get("result");
            if (result == null || !result.isObject()) {
                throw new IOException("Codex app-server returned no result for request " + requestId);
            }
            return result;
        }
        throw new IOException("Codex app-server ended before request " + requestId + " completed");
    }

    static Snapshot parseModelList(JsonNode result) {
        List<ModelEntry> models = new ArrayList<>();
        String defaultModel = null;
        JsonNode data = result.get("data");
        if (data == null || !data.isArray()) return Snapshot.empty();
        for (JsonNode item : data) {
            if (item.path("hidden").asBoolean(false)) continue;
            String name = item.path("model").asText(item.path("id").asText(""));
            if (name.isBlank()) continue;
            models.add(new ModelEntry(name, CodexProvider.ID, null));
            if (item.path("isDefault").asBoolean(false)) defaultModel = name;
        }
        return new Snapshot(models, defaultModel);
    }
}
