package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

// ☁️ Keeps a copy of every file in uploads/ (selfies, ID close-ups, QR codes, ban mugshots, exhibit photos) in a
// private Amazon S3 bucket. Elastic Beanstalk deletes the app folder, uploads/ included, on every deploy and whenever
// it replaces the server, so on AWS the local folder is only a cache: each saved file is copied to S3, each deleted
// file is removed from S3 too, and a file missing locally is downloaded back on first use (restore).
// Off when aerovisit.s3.bucket is blank (local dev, tests): every method is then a no-op and uploads/ works as before.
// 🔒 Only plain file names (no "/", no "..") ever become S3 keys, so a bad database value can't reach other objects.
@Service
public class UploadMirror {

    static final String UPLOADS = "uploads/";
    static final String EXHIBITS = "uploads/exhibits/";
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}");

    private final S3Client s3; // null when off
    private final String bucket;
    private final Path uploadDir;

    @Autowired
    public UploadMirror(@Value("${aerovisit.s3.bucket:}") String bucket,
                        @Value("${aerovisit.s3.region:ap-southeast-2}") String region) {
        this(bucket == null || bucket.isBlank() ? null : S3Client.builder().region(Region.of(region)).build(),
             bucket, Paths.get("uploads"));
    }

    // Tests pass a fake client and a temp folder
    UploadMirror(S3Client s3, String bucket, Path uploadDir) {
        this.s3 = s3;
        this.bucket = bucket;
        this.uploadDir = uploadDir.toAbsolutePath().normalize();
        if (s3 != null) System.out.println("☁️ [S3] Upload mirror ON, bucket " + bucket);
    }

    public boolean enabled() {
        return s3 != null;
    }

    // --- uploads/<fileName>: selfies, ID close-ups, QR codes, ban mugshots ---

    public void pushUpload(String fileName) {
        if (plain(fileName)) push(UPLOADS + fileName, uploadDir.resolve(fileName));
    }

    // True when no S3 copy is left (or S3 is off), so a failed delete can be retried
    public boolean deleteUpload(String fileName) {
        return !plain(fileName) || delete(UPLOADS + fileName);
    }

    // True when the file is on disk afterwards
    public boolean restoreUpload(String fileName) {
        return plain(fileName) && restore(UPLOADS + fileName, uploadDir.resolve(fileName));
    }

    // --- Exhibits slideshow photos (aerovisit.exhibits.dir, uploads/exhibits by default) ---

    public void pushExhibit(Path file) {
        String fileName = String.valueOf(file.getFileName());
        if (plain(fileName)) push(EXHIBITS + fileName, file);
    }

    public boolean deleteExhibit(String fileName) {
        return !plain(fileName) || delete(EXHIBITS + fileName);
    }

    public boolean restoreExhibit(Path exhibitsDir, String fileName) {
        return plain(fileName) && restore(EXHIBITS + fileName, exhibitsDir.toAbsolutePath().normalize().resolve(fileName));
    }

    // A request for /uploads/<name> or /exhibit-photos/<name>: brings the file back from S3 first if it is missing
    public void restoreForRequest(String path, Path exhibitsDir) {
        if (!enabled() || path == null) return;
        if (path.startsWith("/uploads/")) restoreUpload(path.substring("/uploads/".length()));
        else if (path.startsWith("/exhibit-photos/")) restoreExhibit(exhibitsDir, path.substring("/exhibit-photos/".length()));
    }

    static boolean plain(String fileName) {
        return fileName != null && PLAIN_NAME.matcher(fileName).matches() && !fileName.contains("..");
    }

    // A failed copy is logged, never thrown: the booking or upload still succeeds with its local file
    private void push(String key, Path file) {
        if (s3 == null) return;
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
        } catch (RuntimeException e) {
            System.err.println("❌ [S3] Could not copy " + key + " to S3 (it only exists on this server): " + e.getMessage());
        }
    }

    private boolean delete(String key) {
        if (s3 == null) return true;
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (RuntimeException e) {
            System.err.println("❌ [S3] Could not delete " + key + ": " + e.getMessage());
            return false;
        }
    }

    private boolean restore(String key, Path file) {
        if (Files.exists(file)) return true;
        if (s3 == null) return false;
        try {
            byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, bytes);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            System.err.println("❌ [S3] Could not restore " + key + ": " + e.getMessage());
            return false;
        }
    }
}
