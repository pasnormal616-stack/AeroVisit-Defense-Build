package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 🛂 Passport copies of Foreign visitors (PDF only), stored like the ID photos and receipts: under uploads/ with a random
 * name (passport-<uuid>.pdf), copied to S3 (UploadMirror), never public (/uploads/passport-* is denied; staff open them
 * through PassportAdminController), and removed by the 30-day retention purge.
 *
 * The real file type is read from the bytes, never from the name or the browser's declared type: the file must start
 * with the PDF signature "%PDF-" AND parse as a whole PDF document with at least one page (OpenPDF's PdfReader), so a
 * renamed image, a document with only a forged header, a broken file or a password-protected PDF is refused.
 */
@Service
public class PassportStorage {

    // The same cap as the booking's ID photo (VisitorPortalController.ID_PHOTO_MAX_BYTES)
    public static final long MAX_PASSPORT_BYTES = 15L * 1024 * 1024;

    public static final String REQUIRED = "Please upload a PDF copy of your passport.";
    public static final String TOO_LARGE = "Your passport PDF is larger than 15 MB. Please upload a smaller PDF (a scan at a lower resolution works).";
    public static final String NOT_A_PDF = "Please upload your passport as a PDF file. Photos, other documents and password-protected PDFs are not accepted.";

    private static final byte[] PDF_SIGNATURE = {'%', 'P', 'D', 'F', '-'};

    private final Path uploadDir;

    // ☁️ S3 copy (off locally). Optional, so test slices without it still start.
    @Autowired(required = false)
    UploadMirror uploadMirror;

    public PassportStorage() {
        this(Paths.get("uploads"));
    }

    PassportStorage(Path uploadDir) {
        this.uploadDir = uploadDir;
    }

    // Why this upload is refused, or null when it is a real PDF within the size cap. Safe to show the visitor.
    public static String error(MultipartFile file) {
        if (file == null || file.isEmpty()) return REQUIRED;
        if (file.getSize() > MAX_PASSPORT_BYTES) return TOO_LARGE;
        try {
            return isRealPdf(file.getBytes()) ? null : NOT_A_PDF;
        } catch (IOException e) {
            return NOT_A_PDF;
        }
    }

    // True when the bytes start with "%PDF-" and OpenPDF reads them as an unencrypted PDF with at least one page
    public static boolean isRealPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 64) return false;
        for (int i = 0; i < PDF_SIGNATURE.length; i++) {
            if (bytes[i] != PDF_SIGNATURE[i]) return false;
        }
        com.lowagie.text.pdf.PdfReader reader = null;
        try {
            reader = new com.lowagie.text.pdf.PdfReader(bytes);
            return !reader.isEncrypted() && reader.getNumberOfPages() > 0;
        } catch (Exception | StackOverflowError e) { // broken, truncated or hostile structure
            return false;
        } finally {
            if (reader != null) reader.close();
        }
    }

    // Checks the upload and saves it; returns the new file name. Throws with a message that is safe to show the visitor.
    public String save(MultipartFile file) throws IOException {
        String error = error(file);
        if (error != null) throw new InvalidPassportException(error);
        String name = "passport-" + UUID.randomUUID() + ".pdf";
        Files.createDirectories(uploadDir);
        Files.write(uploadDir.resolve(name), file.getBytes());
        if (uploadMirror != null) uploadMirror.pushUpload(name);
        return name;
    }

    // The stored passport file, or null when the name is missing, not a passport name, or the file is gone
    public Path find(String fileName) {
        if (!isPassportName(fileName)) return null;
        Path file = uploadDir.resolve(fileName).normalize();
        if (!file.startsWith(uploadDir.normalize())) return null;
        if (!Files.exists(file) && uploadMirror != null) uploadMirror.restoreUpload(fileName);
        return Files.exists(file) ? file : null;
    }

    // Removes a passport file and its S3 copy (true when nothing is left); other names are never touched
    public boolean delete(String fileName) {
        if (!isPassportName(fileName)) return true;
        try {
            Path file = uploadDir.resolve(fileName).normalize();
            if (file.startsWith(uploadDir.normalize())) Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("⚠️ Passport file could not be deleted: " + e.getMessage());
            return false;
        }
        return uploadMirror == null || uploadMirror.deleteUpload(fileName);
    }

    public static boolean isPassportName(String fileName) {
        return fileName != null && fileName.matches("passport-[0-9a-f-]{36}\\.pdf");
    }

    // The message is safe to show to the visitor as-is
    public static class InvalidPassportException extends IOException {
        public InvalidPassportException(String message) {
            super(message);
        }
    }
}
