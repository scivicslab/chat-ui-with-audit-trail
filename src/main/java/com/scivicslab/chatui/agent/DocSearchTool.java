package com.scivicslab.chatui.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
import java.util.logging.Logger;

/**
 * The {@code search_docs} tool: retrieval over the internal documentation corpus (the "RAG" tool).
 * Calls the html-saurus server, which indexes the Docusaurus docs under {@code ~/works} and exposes
 * both semantic (embedding) and full-text search.
 *
 * <p>It prefers semantic search ({@code /api/search-semantic}, backed by the multilingual-e5 embedding
 * server), which is the meaning-based retrieval we want; if that returns nothing — e.g. the embedding
 * server is unreachable or no semantic index exists — it falls back to full-text search
 * ({@code /api/search}). Both return a JSON array of {@code {title, path/pagePath, summary}} which is
 * formatted into text the agent feeds back as an Observation, then cites in its answer.</p>
 *
 * <p>The html-saurus base URL defaults to {@code http://localhost:28001} (its reserved port per
 * {@code PortConvention_260719_oo01}) and can be overridden with the {@code chatui.docsearch.url}
 * system property or {@code DOCSEARCH_URL} env.</p>
 */
public final class DocSearchTool {

    private DocSearchTool() {}

    private static final Logger LOG = Logger.getLogger(DocSearchTool.class.getName());
    static final int DEFAULT_MAX_RESULTS = 20;
    /** Characters kept from each hit's summary, so 20 hits stay under ContextBudget's observation
     *  threshold and the model sees the whole ranking rather than its head and tail. */
    static final int SUMMARY_CHARS = 150;

    private static final String BASE_URL = resolveBaseUrl();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String resolveBaseUrl() {
        String p = System.getProperty("chatui.docsearch.url");
        if (p != null && !p.isBlank()) return p.trim();
        String e = System.getenv("DOCSEARCH_URL");
        if (e != null && !e.isBlank()) return e.trim();
        // html-saurus's reserved port per PortConvention_260719_oo01 (28005/28004 are retired).
        return "http://localhost:28001";
    }

    /** Searches the internal docs; returns formatted hits, or a clear "unavailable/none" message.
     *  Semantic search is tried first (the RAG path), falling back to full-text, de-duplicated
     *  by document. (quarkus-chat-ui3's curated "/api/keyword-map" step is dropped here — that
     *  endpoint does not exist in html-saurus's published API, HtmlSaurusApi_260802_oo01.) */
    public static String search(String query, int maxResults) {
        if (query == null || query.isBlank()) return "error: query required";
        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);

        // Recall: semantic (the RAG path); fall back to JSON full-text.
        List<JsonNode> recall = fetchHits(BASE_URL + "/api/search-semantic?q=" + q, "semantic", query);
        if (recall.isEmpty())
            recall = fetchHits(BASE_URL + "/api/search?q=" + q + "&lang=ja", "fulltext", query);

        if (recall.isEmpty()) {
            // Last resort for an old html-saurus without the JSON APIs: scrape the /search HTML page.
            String scraped = scrapeSearchPage(BASE_URL + "/search?q=" + q, limit, query);
            if (scraped != null) return scraped;
            return "No documents found for '" + query + "' (the internal doc-search server returned nothing "
                 + "or is unavailable at " + BASE_URL + ").";
        }

        // Recall up to 'limit', de-duplicated by document.
        List<JsonNode> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int recallAdded = 0;
        for (JsonNode h : recall) {
            if (recallAdded >= limit) break;
            if (seen.add(dedupeKey(h))) { merged.add(h); recallAdded++; }
        }

        return formatHits(merged);
    }

    /** Fallback: scrape the html-saurus {@code /search} HTML results page (jsoup) — used when the JSON
     *  search API is absent (older html-saurus). Each hit is {@code a.result[href]} with child divs. */
    private static String scrapeSearchPage(String url, int maxResults, String query) {
        try {
            LOG.info("search_docs (html scrape): " + query);
            Document doc = Jsoup.parse(get(url));
            Elements results = doc.select("a.result");
            if (results.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Element r : results) {
                if (count >= maxResults) break;
                String title = textOf(r, ".result-title");
                String path = r.attr("href");
                String summary = textOf(r, ".result-summary");
                if (title.isBlank() && path.isBlank()) continue;
                sb.append(count + 1).append(". ").append(title).append("\n");
                if (!path.isBlank()) sb.append("   url: ").append(toUrl(path)).append("\n");
                if (!summary.isBlank()) sb.append("   ").append(summary).append("\n");
                sb.append("\n");
                count++;
            }
            return count == 0 ? null : sb.toString().stripTrailing();
        } catch (Exception e) {
            LOG.warning("search_docs html scrape failed for '" + query + "': " + e.getMessage());
            return null;
        }
    }

    private static String textOf(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().strip() : "";
    }

    /** Fetches a html-saurus JSON-array endpoint and returns its hits; empty list on no-hits or any failure. */
    private static List<JsonNode> fetchHits(String url, String mode, String query) {
        List<JsonNode> hits = new ArrayList<>();
        try {
            LOG.info("search_docs (" + mode + "): " + query);
            JsonNode arr = MAPPER.readTree(get(url));
            if (arr.isArray()) arr.forEach(hits::add);
        } catch (Exception e) {
            LOG.warning("search_docs " + mode + " failed for '" + query + "': " + e.getMessage());
        }
        return hits;
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // Short per-attempt timeout: this backs an interactive gate, and there are up to three
                // fallback attempts, so a slow/unresponsive html-saurus must not stall the turn.
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Renders a list of hit objects as the Observation text the agent reads. */
    private static String formatHits(List<JsonNode> hits) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (JsonNode hit : hits) {
            String id = hit.path("id").asText("");
            String title = hit.path("title").asText("");
            // Served path (for a fetchable URL): semantic results use "path", full-text uses "pagePath".
            String served = hit.hasNonNull("path") ? hit.path("path").asText("")
                                                   : hit.path("pagePath").asText("");
            String srcPath = hit.path("srcPath").asText("");   // absolute source .md path
            String summary = hit.path("summary").asText("");
            if (title.isBlank() && served.isBlank() && srcPath.isBlank()) continue;
            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!id.isBlank()) sb.append("   id: ").append(id).append("\n");
            // Full source path (what the doc-site "Path" button shows).
            if (!srcPath.isBlank()) sb.append("   path: ").append(srcPath).append("\n");
            // Directly-fetchable URL, so the agent can read the full document with the 'fetch' tool.
            if (!summary.isBlank()) {
                sb.append("   ")
                  .append(summary.length() > SUMMARY_CHARS ? summary.substring(0, SUMMARY_CHARS) + "…" : summary)
                  .append("\n");
            }
            sb.append("\n");
            count++;
        }
        if (count == 0) return "No documents found.";
        // Restated next to the list itself, not only in the system prompt: by the time these hits
        // are read the tool description is many messages back, and a bare ranked list reads as an
        // answer (SkillAndAgentsFile/DocRetrievalAgentLoop 実測 — the agent answered from summaries
        // without opening anything in 7 of 10 questions).
        return count + " candidate documents, best match first. These are candidates, not an answer"
                + " — call read on a candidate's path to see what it actually says.\n\n"
                + sb.toString().stripTrailing();
    }

    /** De-duplication key for a hit: source path, else served path, else id, else title. */
    private static String dedupeKey(JsonNode hit) {
        String sp = hit.path("srcPath").asText("");
        if (!sp.isBlank()) return "s:" + sp;
        String p = hit.hasNonNull("path") ? hit.path("path").asText("") : hit.path("pagePath").asText("");
        if (!p.isBlank()) return "p:" + p;
        String id = hit.path("id").asText("");
        if (!id.isBlank()) return "i:" + id;
        return "t:" + hit.path("title").asText("");
    }

    /** Turns a html-saurus served path into a fetchable absolute URL. */
    private static String toUrl(String path) {
        if (path == null || path.isBlank()) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return BASE_URL + (path.startsWith("/") ? path : "/" + path);
    }
}
