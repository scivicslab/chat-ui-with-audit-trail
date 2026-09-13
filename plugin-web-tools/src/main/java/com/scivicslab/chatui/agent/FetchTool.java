package com.scivicslab.chatui.agent;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * The {@code fetch} tool: retrieves a URL and returns its readable content as text. The complement to
 * {@code web_search} — search finds URLs, fetch reads the page behind one — so the agent can answer from
 * page content, not just search snippets. For HTML it extracts the main content and renders it as
 * Markdown-ish text; a PDF is extracted to text with PDFBox; anything else (e.g. JSON from an API)
 * is returned as-is.
 *
 * <p>Logic reused from the existing {@code plugin-web} {@code FetchActor} / mcp-gateway {@code FetchTool};
 * only jsoup is needed (already a dependency). Note: pages whose content is rendered by JavaScript return
 * only their static HTML skeleton here — for those, fetch a data/JSON endpoint instead (e.g. the JMA
 * forecast API for weather).</p>
 *
 * <p>PDFs used to reach the model as their raw bytes ({@code %PDF-1.7 ... endobj ...}), which is what
 * most scholarly search hits are; the model then answered from the search snippets alone
 * ({@code ScholarSearchAndPdfFetch_260913_oo01}).</p>
 */
public final class FetchTool {

    private FetchTool() {}

    private static final Logger LOG = Logger.getLogger(FetchTool.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;
    /** Pages of a PDF whose text is extracted; a 300-page book is not what a fetch is for. */
    static final int PDF_MAX_PAGES = 60;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Fetches {@code url} and returns extracted text, or an {@code error: ...} string. */
    public static String fetch(String url) {
        if (url == null || url.isBlank()) return "error: url required";
        String u = url.trim();
        try {
            LOG.info("fetch: " + u);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(u))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (compatible; chat-ui3/1.0; fetch)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            if (response.statusCode() >= 400) {
                return "error: HTTP " + response.statusCode() + ": "
                        + truncate(new String(body, charsetOf(contentType)), 500);
            }
            String text;
            if (isPdf(contentType, body)) {
                text = pdfText(body);
            } else {
                String s = new String(body, charsetOf(contentType));
                text = contentType.contains("html") ? extractText(s, u) : s;
            }
            return truncate(text, DEFAULT_MAX_LENGTH);
        } catch (Exception e) {
            LOG.warning("fetch failed for " + u + ": " + e.getMessage());
            return "error: fetching " + u + ": " + e.getMessage();
        }
    }

    /** A PDF by declared type, or by the {@code %PDF-} signature when the server lied about the type. */
    static boolean isPdf(String contentType, byte[] body) {
        if (contentType != null && contentType.contains("application/pdf")) return true;
        return body != null && body.length >= 5
                && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F' && body[4] == '-';
    }

    /**
     * The text of a PDF's first {@link #PDF_MAX_PAGES} pages, runs of whitespace collapsed. Says
     * when pages were left out. An unreadable PDF (encrypted, scanned images only) yields an
     * {@code error: } text rather than an exception.
     */
    static String pdfText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pages, PDF_MAX_PAGES));
            String text = stripper.getText(doc).replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
            if (text.isBlank()) {
                return "error: the PDF has no extractable text (" + pages + " pages; scanned images or protected)";
            }
            if (pages > PDF_MAX_PAGES) {
                text += "\n[text of the first " + PDF_MAX_PAGES + " of " + pages + " pages]";
            }
            return text;
        } catch (Exception e) {
            return "error: could not read the PDF: " + e.getMessage();
        }
    }

    /** The charset named in a Content-Type header, or UTF-8. */
    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int i = contentType.indexOf("charset=");
            if (i >= 0) {
                String name = contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header, aside, [role=navigation]").remove();

        Element main = doc.selectFirst("main, article, [role=main], #content, .content, #main");
        Element root = main != null ? main : doc.body();
        if (root == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String tag = block.tagName();
            String t = block.text().trim();
            if (t.isEmpty()) continue;
            if (tag.startsWith("h")) {
                sb.append("#".repeat(tag.charAt(1) - '0')).append(" ").append(t).append("\n\n");
            } else if ("pre".equals(tag)) {
                sb.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("li".equals(tag)) {
                sb.append("- ").append(t).append("\n");
            } else {
                sb.append(t).append("\n\n");
            }
        }
        return sb.isEmpty() ? root.text() : sb.toString().stripTrailing();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n[truncated " + text.length() + " chars total]";
    }
}
