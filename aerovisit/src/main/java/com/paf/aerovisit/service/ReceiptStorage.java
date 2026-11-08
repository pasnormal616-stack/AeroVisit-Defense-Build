package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * 💳 Proof-of-payment screenshots, stored like the ID photos: under uploads/ with a random name, copied to S3
 * (UploadMirror), never public (served only through PaymentAdminController), and removed by the retention purge.
 *
 * The real file type is read from the bytes, never from the name or the browser's declared type: the file signature
 * (magic bytes) must be JPEG, PNG or WebP AND the whole image must decode (ExhibitPhotoStorage's checks), so a renamed
 * document, a truncated file or a disguised script is refused. The same check guards the booking's two ID photos.
 */
@Service
public class ReceiptStorage {

    public static final long MAX_RECEIPT_BYTES = 5L * 1024 * 1024;
    public static final Set<String> RECEIPT_FORMATS = Set.of("jpeg", "png", "webp");
    // The booking's ID photo: the booking page uploads a JPEG; PNG and WEBP are accepted from browsers that can't convert
    public static final Set<String> ID_PHOTO_FORMATS = Set.of("jpeg", "png", "webp");

    // HEIC / HEIF (an iPhone camera setting): an ISO-BMFF "ftyp" box with a HEIF brand. Refused with its own message,
    // as the server can't read it.
    public static boolean isHeic(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        if (bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p') return false;
        String brand = new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1").contains(brand);
    }

    static final String TOO_LARGE = "The receipt is too large. Please upload a screenshot of 5 MB or less.";
    static final String NOT_AN_IMAGE = "Please upload a JPG, PNG or WEBP screenshot of your receipt.";

    private final Path uploadDir;

    // ☁️ S3 copy (off locally). Optional, so test slices without it still start.
    @Autowired(required = false)
    UploadMirror uploadMirror;

    public ReceiptStorage() {
        this(Paths.get("uploads"));
    }

    ReceiptStorage(Path uploadDir) {
        this.uploadDir = uploadDir;
    }

    // "jpeg", "png" or "webp" when the bytes really are one of the allowed images (signature + full decode), else null
    public static String realImageFormat(byte[] bytes, Set<String> allowed) {
        if (bytes == null) return null;
        String format = ExhibitPhotoStorage.formatFromSignature(bytes);
        if (format == null || !allowed.contains(format)) return null;
        return ExhibitPhotoStorage.decodes(bytes, format) ? format : null;
    }

    // Saves a receipt and returns its new file name. The previous receipt is NEVER deleted (a receipt is proof of
    // payment): the caller keeps its name (Visitor.keepEarlierReceipt) for the retention purge. Throws with a message
    // that is safe to show the visitor.
    public String saveReceipt(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new InvalidReceiptException(NOT_AN_IMAGE);
        if (file.getSize() > MAX_RECEIPT_BYTES) throw new InvalidReceiptException(TOO_LARGE);
        byte[] bytes = file.getBytes();
        String format = realImageFormat(bytes, RECEIPT_FORMATS);
        if (format == null) throw new InvalidReceiptException(NOT_AN_IMAGE);

        String name = "receipt-" + UUID.randomUUID() + "." + ("jpeg".equals(format) ? "jpg" : format);
        Files.createDirectories(uploadDir);
        Files.write(uploadDir.resolve(name), bytes);
        if (uploadMirror != null) uploadMirror.pushUpload(name);
        return name;
    }

    // The stored receipt file, or null when the name is missing, not a receipt name, or the file is gone
    public Path find(String fileName) {
        if (!isReceiptName(fileName)) return null;
        Path file = uploadDir.resolve(fileName).normalize();
        if (!file.startsWith(uploadDir.normalize())) return null;
        if (!Files.exists(file) && uploadMirror != null) uploadMirror.restoreUpload(fileName);
        return Files.exists(file) ? file : null;
    }

    // Removes a receipt file and its S3 copy (true when nothing is left); other names are never touched
    public boolean delete(String fileName) {
        if (!isReceiptName(fileName)) return true;
        try {
            Path file = uploadDir.resolve(fileName).normalize();
            if (file.startsWith(uploadDir.normalize())) Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("⚠️ Old receipt could not be deleted: " + e.getMessage());
            return false;
        }
        return uploadMirror == null || uploadMirror.deleteUpload(fileName);
    }

    static boolean isReceiptName(String fileName) {
        return fileName != null && fileName.matches("receipt-[0-9a-f-]{36}\\.(jpg|png|webp)");
    }

    public static String contentType(String fileName) {
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    // The message is safe to show to the visitor as-is
    public static class InvalidReceiptException extends IOException {
        public InvalidReceiptException(String message) {
            super(message);
        }
    }
}
