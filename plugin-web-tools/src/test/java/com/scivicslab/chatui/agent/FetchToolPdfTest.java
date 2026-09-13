package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure tests (no HTTP): a PDF is recognised and its text extracted instead of its bytes. */
@DisplayName("FetchTool — PDFs reach the model as text, not as %PDF- bytes")
class FetchToolPdfTest {

    /** A PDF built in memory with one line of text per page. */
    private static byte[] pdfWithPages(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Gamified vocabulary retention page " + i);
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void isPdf_byContentType_orBySignature() throws Exception {
        byte[] pdf = pdfWithPages(1);
        assertTrue(FetchTool.isPdf("application/pdf", new byte[0]));
        assertTrue(FetchTool.isPdf("application/octet-stream", pdf), "signature wins when the type is wrong");
        assertTrue(FetchTool.isPdf("", pdf));
        assertFalse(FetchTool.isPdf("text/html; charset=utf-8", "<html></html>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pdfText_extractsTheWords() throws Exception {
        String text = FetchTool.pdfText(pdfWithPages(2));

        assertTrue(text.contains("Gamified vocabulary retention page 1"), text);
        assertTrue(text.contains("Gamified vocabulary retention page 2"), text);
        assertFalse(text.startsWith("%PDF-"), "the bytes must not leak through");
        assertFalse(text.contains("pages]"), "nothing left out for a short document");
    }

    @Test
    void pdfText_stopsAtTheFirstSixtyPages_andSaysSo() throws Exception {
        String text = FetchTool.pdfText(pdfWithPages(FetchTool.PDF_MAX_PAGES + 5));

        assertTrue(text.contains("page " + FetchTool.PDF_MAX_PAGES), text);
        assertFalse(text.contains("page " + (FetchTool.PDF_MAX_PAGES + 1) + "\n"), "page beyond the cap was read");
        assertTrue(text.endsWith("[text of the first 60 of 65 pages]"), text);
    }

    @Test
    void pdfText_notAPdf_isAnErrorText() {
        String text = FetchTool.pdfText("this is not a pdf".getBytes(StandardCharsets.UTF_8));
        assertTrue(text.startsWith("error: could not read the PDF"), text);
    }

    @Test
    void charsetOf_readsTheHeader_andFallsBackToUtf8() {
        assertEquals("ISO-8859-1", FetchTool.charsetOf("text/html; charset=ISO-8859-1").name());
        assertEquals("UTF-8", FetchTool.charsetOf("text/html; charset=\"utf-8\"").name());
        assertEquals("UTF-8", FetchTool.charsetOf("application/json").name());
        assertEquals("UTF-8", FetchTool.charsetOf("text/plain; charset=no-such-charset").name());
        assertEquals("UTF-8", FetchTool.charsetOf(null).name());
    }
}
