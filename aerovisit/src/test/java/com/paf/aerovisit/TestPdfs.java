package com.paf.aerovisit;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 🛂 Real PDF bytes for passport upload tests (PassportStorage reads the signature AND parses the whole document, so
 * a few "%PDF-" header bytes are not enough), plus the fakes the check must refuse.
 */
public final class TestPdfs {

    private TestPdfs() {}

    // A one-page PDF; different text gives different bytes
    public static byte[] pdf(String text) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();
            document.add(new Paragraph("PASSPORT " + text));
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Starts like a PDF but is not one (a renamed text file with a forged header)
    public static byte[] fakeHeaderOnly() {
        return ("%PDF-1.7\n" + "this is not really a pdf document, only its first five bytes are ".repeat(4))
                .getBytes(StandardCharsets.US_ASCII);
    }
}
