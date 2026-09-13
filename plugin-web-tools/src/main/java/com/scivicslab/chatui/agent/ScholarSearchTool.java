package com.scivicslab.chatui.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * The {@code scholar_search} tool: searches the scholarly literature through the OpenAlex API and
 * returns, per work, the title, authors, year, venue, citation count, DOI, an open-access URL when
 * one exists, and the abstract.
 *
 * <p>{@code web_search} is the wrong tool for a research question: it returns web pages, most
 * scholarly hits are PDFs it cannot read or publisher pages that refuse the fetch, and nothing in
 * a search-engine result says how much a paper has been cited. OpenAlex indexes the works
 * themselves, with structured metadata, and needs no API key
 * ({@code ScholarSearchAndPdfFetch_260913_oo01}).</p>
 *
 * <p>Calls are spaced at least {@link #MIN_INTERVAL_MS} apart, the polite floor for an external
 * API. The optional {@code chat-ui.openalex.mailto} system property is passed as OpenAlex's
 * {@code mailto} parameter, which puts requests in its faster "polite pool"; without it the tool
 * still works.</p>
 */
public final class ScholarSearchTool {

    private ScholarSearchTool() {}

    private static final Logger LOG = Logger.getLogger(ScholarSearchTool.class.getName());

    static final String BASE_URL = "https://api.openalex.org/works";
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 25;
    /** Characters of each abstract kept in the observation. */
    static final int ABSTRACT_CHARS = 900;
    /** Minimum spacing between two calls to OpenAlex. */
    static final long MIN_INTERVAL_MS = 3_000;
    static final String MAILTO_PROPERTY = "chat-ui.openalex.mailto";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Object PACE = new Object();
    private static long lastCallAt = 0;

    /**
     * Searches OpenAlex and formats the result for the model.
     *
     * @param query    words looked for in titles and abstracts
     * @param sort     {@code "relevance"} (default) or {@code "cited"} (most cited first)
     * @param yearFrom earliest publication year, or blank for no limit
     * @param limit    how many works to return, 1–{@value #MAX_LIMIT}; blank for {@value #DEFAULT_LIMIT}
     * @return the formatted works, or a text starting with {@code error: }
     */
    public static String search(String query, String sort, String yearFrom, String limit) {
        if (query == null || query.isBlank()) return "error: query required";
        Integer year = parseYear(yearFrom);
        int n = parseLimit(limit);
        String url = buildUrl(query, sort, year, n, System.getProperty(MAILTO_PROPERTY));
        try {
            pace();
            LOG.info("scholar_search: " + url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "chat-ui-with-audit-trail/2.0 (scholar_search)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                return "error: OpenAlex HTTP " + response.statusCode() + ": " + trim(response.body(), 300);
            }
            return format(new JSONObject(response.body()), query, sortLabel(sort), n);
        } catch (Exception e) {
            LOG.warning("scholar_search failed for '" + query + "': " + e.getMessage());
            return "error: searching OpenAlex for '" + query + "': " + e.getMessage();
        }
    }

    /**
     * The request URL. The query goes into OpenAlex's {@code title_and_abstract.search} filter —
     * the whole-record {@code search} parameter also matches full text and citing works, which
     * put self-driving cars in a query about vocabulary. Commas separate OpenAlex filters, so a
     * comma in the query is replaced by a space.
     */
    static String buildUrl(String query, String sort, Integer yearFrom, int limit, String mailto) {
        StringBuilder filter = new StringBuilder("title_and_abstract.search:")
                .append(query.trim().replace(',', ' '));
        if (yearFrom != null) {
            filter.append(",from_publication_date:").append(yearFrom).append("-01-01");
        }
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("?filter=").append(encode(filter.toString()))
                .append("&sort=").append(encode("cited".equalsIgnoreCase(sortLabel(sort)) ? "cited_by_count:desc" : "relevance_score:desc"))
                .append("&per-page=").append(limit);
        if (mailto != null && !mailto.isBlank()) {
            url.append("&mailto=").append(encode(mailto.trim()));
        }
        return url.toString();
    }

    /** {@code "cited"} or {@code "relevance"}; anything else (or blank) is relevance. */
    static String sortLabel(String sort) {
        return sort != null && sort.trim().equalsIgnoreCase("cited") ? "cited" : "relevance";
    }

    static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) return DEFAULT_LIMIT;
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(limit.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    static Integer parseYear(String yearFrom) {
        if (yearFrom == null || yearFrom.isBlank()) return null;
        try {
            int y = Integer.parseInt(yearFrom.trim());
            return (y >= 1000 && y <= 9999) ? y : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * OpenAlex ships abstracts as an inverted index ({@code word -> [positions]}); this puts the
     * words back in order.
     */
    static String abstractOf(JSONObject inverted) {
        if (inverted == null || inverted.isEmpty()) return "";
        Map<Integer, String> byPosition = new TreeMap<>();
        for (String word : inverted.keySet()) {
            JSONArray positions = inverted.optJSONArray(word);
            if (positions == null) continue;
            for (int i = 0; i < positions.length(); i++) {
                byPosition.put(positions.getInt(i), word);
            }
        }
        return String.join(" ", byPosition.values());
    }

    /** One work per numbered entry, in the order OpenAlex returned them. */
    static String format(JSONObject response, String query, String sortLabel, int limit) {
        JSONArray results = response.optJSONArray("results");
        int total = response.optJSONObject("meta") != null ? response.getJSONObject("meta").optInt("count", -1) : -1;
        if (results == null || results.isEmpty()) {
            return "No works found in OpenAlex for \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("OpenAlex: ").append(total >= 0 ? total + " works match" : "works matching")
          .append(" \"").append(query).append("\"; showing ").append(Math.min(results.length(), limit))
          .append(" sorted by ").append("cited".equals(sortLabel) ? "citation count" : "relevance").append(".\n\n");
        for (int i = 0; i < results.length() && i < limit; i++) {
            JSONObject w = results.getJSONObject(i);
            sb.append(i + 1).append(". ").append(w.optString("display_name", "(untitled)"));
            int year = w.optInt("publication_year", 0);
            if (year > 0) sb.append(" (").append(year).append(")");
            sb.append("\n");
            List<String> authors = authorsOf(w);
            if (!authors.isEmpty()) sb.append("   Authors: ").append(String.join(", ", authors)).append("\n");
            String venue = venueOf(w);
            if (!venue.isBlank()) sb.append("   Venue: ").append(venue).append("\n");
            sb.append("   Cited by: ").append(w.optInt("cited_by_count", 0)).append("\n");
            String doi = w.optString("doi", "");
            if (!doi.isBlank() && !"null".equals(doi)) sb.append("   DOI: ").append(doi).append("\n");
            JSONObject oa = w.optJSONObject("open_access");
            String oaUrl = oa == null ? "" : oa.optString("oa_url", "");
            if (!oaUrl.isBlank() && !"null".equals(oaUrl)) sb.append("   Open access: ").append(oaUrl).append("\n");
            String abs = abstractOf(w.optJSONObject("abstract_inverted_index"));
            sb.append("   Abstract: ").append(abs.isBlank() ? "(none)" : trim(abs, ABSTRACT_CHARS)).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private static List<String> authorsOf(JSONObject w) {
        List<String> out = new ArrayList<>();
        JSONArray authorships = w.optJSONArray("authorships");
        if (authorships == null) return out;
        for (int i = 0; i < authorships.length() && out.size() < 4; i++) {
            JSONObject author = authorships.getJSONObject(i).optJSONObject("author");
            if (author != null) {
                String name = author.optString("display_name", "");
                if (!name.isBlank()) out.add(name);
            }
        }
        if (authorships.length() > 4) out.add("et al.");
        return out;
    }

    private static String venueOf(JSONObject w) {
        JSONObject loc = w.optJSONObject("primary_location");
        if (loc == null) return "";
        JSONObject source = loc.optJSONObject("source");
        return source == null ? "" : source.optString("display_name", "");
    }

    static String trim(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() > max ? t.substring(0, max) + " …[" + t.length() + " chars]" : t;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Waits so that consecutive calls to OpenAlex are at least {@link #MIN_INTERVAL_MS} apart. */
    private static void pace() throws InterruptedException {
        synchronized (PACE) {
            long wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastCallAt = System.currentTimeMillis();
        }
    }
}
