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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return search(query, maxResults, "all");
    }

    /**
     * Searches the internal documentation and returns a ranked list of candidate documents.
     *
     * <p>html-saurus answers the same question three unrelated ways — a Lucene inverted index over
     * the words, Lucene MoreLikeThis over term frequencies, and cosine similarity over embeddings —
     * and they disagree. The inverted index is the one that finds a class name at rank 1; the
     * embedding one is the one that finds a document whose wording differs from the question. Each
     * is queried on its own and their top hits are merged, rather than one standing in for another
     * ({@code DocRetrievalAgentLoop_260830_oo01}).</p>
     *
     * <p>Which routes to run is not the caller's choice. Offering it as a tool argument was tried
     * and measured: given the choice, the conversation asked for the embedding route alone in six
     * of ten searches, and every question it got wrong that way had a candidate list one route
     * deep ({@code DocRetrievalAgentLoop_260830_oo01}). The {@code route} parameter stays for
     * tests and for callers inside this system, not for the model.</p>
     *
     * @param query      what to search for
     * @param maxResults hits taken from each route; at or below zero the default is used
     * @param route      {@code "all"}, or one route on its own: {@code "fulltext"},
     *                   {@code "tfidf"}, {@code "semantic"}
     * @return the merged list as the Observation text, or a message saying nothing was found
     */
    static String search(String query, int maxResults, String route) {
        if (query == null || query.isBlank()) return "error: query required";
        int limit = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String which = (route == null || route.isBlank()) ? "all" : route.strip().toLowerCase();

        Map<String, List<JsonNode>> byRoute = new LinkedHashMap<>();
        if (which.equals("all") || which.equals("fulltext")) {
            byRoute.put("fulltext",
                    fetchHits(BASE_URL + "/api/search?q=" + q + "&lang=ja", "fulltext", query));
        }
        if (which.equals("all") || which.equals("tfidf")) {
            byRoute.put("tfidf", postHits(BASE_URL + "/api/find-related", query));
        }
        if (which.equals("all") || which.equals("semantic")) {
            byRoute.put("semantic",
                    fetchHits(BASE_URL + "/api/search-semantic?q=" + q, "semantic", query));
        }
        if (byRoute.isEmpty()) {
            return "error: unknown route '" + route + "' (use all, fulltext, tfidf or semantic)";
        }

        List<JsonNode> merged = interleave(byRoute, limit);
        if (merged.isEmpty()) {
            // Last resort for an old html-saurus without the JSON APIs: scrape the /search HTML page.
            String scraped = scrapeSearchPage(BASE_URL + "/search?q=" + q, limit, query);
            if (scraped != null) return scraped;
            return "No documents found for '" + query + "' (the internal doc-search server returned nothing "
                 + "or is unavailable at " + BASE_URL + ").";
        }
        return formatHits(merged);
    }

    /**
     * Merges the routes' hits by taking each route's rank-1, then each route's rank-2, and so on,
     * so no route's best hit is pushed down by another route's long tail. A document found by more
     * than one route keeps its earliest position and records every route that found it, which is
     * the strongest signal in the list: the three routes agree on almost nothing by accident.
     *
     * @param byRoute  each route's hits, in that route's own rank order
     * @param perRoute how many hits to take from each route
     * @return the merged hits, each annotated with the routes that found it
     */
    private static List<JsonNode> interleave(Map<String, List<JsonNode>> byRoute, int perRoute) {
        List<JsonNode> merged = new ArrayList<>();
        Map<String, ObjectNode> seen = new LinkedHashMap<>();
        for (int rank = 0; rank < perRoute; rank++) {
            for (Map.Entry<String, List<JsonNode>> e : byRoute.entrySet()) {
                List<JsonNode> hits = e.getValue();
                if (rank >= hits.size()) continue;
                JsonNode hit = hits.get(rank);
                String key = dedupeKey(hit);
                String found = e.getKey() + " #" + (rank + 1);
                ObjectNode already = seen.get(key);
                if (already != null) {
                    already.put("foundBy", already.path("foundBy").asText("") + ", " + found);
                    continue;
                }
                if (hit instanceof ObjectNode on) {
                    on.put("foundBy", found);
                    seen.put(key, on);
                }
                merged.add(hit);
            }
        }
        return merged;
    }

    /** POSTs {@code body} as plain text and reads the hit array (the MoreLikeThis route's shape). */
    private static List<JsonNode> postHits(String url, String body) {
        List<JsonNode> hits = new ArrayList<>();
        try {
            LOG.info("search_docs (tfidf): " + body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new RuntimeException("HTTP " + response.statusCode());
            JsonNode arr = MAPPER.readTree(response.body());
            if (arr.isArray()) arr.forEach(hits::add);
        } catch (Exception e) {
            LOG.warning("search_docs tfidf failed for '" + body + "': " + e.getMessage());
        }
        return hits;
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

    /** The html-saurus base URL both this tool and {@link ReferenceLinkTool} call. */
    static String baseUrl() {
        return BASE_URL;
    }

    /** Fetches a html-saurus JSON-array endpoint; empty list on no-hits or any failure. */
    static List<JsonNode> hitsFrom(String url, String mode, String subject) {
        return fetchHits(url, mode, subject);
    }

    static String get(String url) throws Exception {
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

    /**
     * Renders hit objects as the numbered block both this tool and {@link ReferenceLinkTool} show.
     * The caller puts its own sentence in front, because what the list means differs: search returns
     * documents ranked by similarity, following a reference returns documents an author declared.
     *
     * @param hits the hit objects, in the order they should appear
     * @return the numbered block, or {@code ""} when no hit had anything to show
     */
    static String renderHits(List<JsonNode> hits) {
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
            String foundBy = hit.path("foundBy").asText("");
            sb.append(count + 1).append(". ").append(title);
            if (!foundBy.isEmpty()) sb.append("  [").append(foundBy).append("]");
            sb.append("\n");
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
        return count == 0 ? "" : sb.toString().stripTrailing();
    }

    /** Renders a list of hit objects as the Observation text the agent reads. */
    private static String formatHits(List<JsonNode> hits) {
        String block = renderHits(hits);
        if (block.isEmpty()) return "No documents found.";
        int count = block.split("\n\n").length;
        // Restated next to the list itself, not only in the system prompt: by the time these hits
        // are read the tool description is many messages back, and a bare ranked list reads as an
        // answer (SkillAndAgentsFile/DocRetrievalAgentLoop 実測 — the agent answered from summaries
        // without opening anything in 7 of 10 questions).
        return count + " candidate documents, best match first. These are candidates, not an answer"
                + " — call read on a candidate's path to see what it actually says.\n\n" + block;
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
