package com.paf.aerovisit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// ☁️ UploadMirror against an in-memory S3 (no AWS account, no network)
class UploadMirrorTest {

    @TempDir
    Path tmp;

    Path uploads;
    FakeS3 s3;
    UploadMirror mirror;

    @BeforeEach
    void setUp() throws IOException {
        uploads = Files.createDirectories(tmp.resolve("uploads"));
        s3 = new FakeS3();
        mirror = new UploadMirror(s3, "test-bucket", uploads);
    }

    @Test
    void offWhenNoBucketIsSet_everythingIsANoOp() throws IOException {
        UploadMirror off = new UploadMirror(null, "", uploads);
        Files.writeString(uploads.resolve("a.jpg"), "x");

        off.pushUpload("a.jpg");

        assertThat(off.enabled()).isFalse();
        assertThat(off.deleteUpload("a.jpg")).isTrue();
        assertThat(off.restoreUpload("a.jpg")).isTrue();   // still on disk
        assertThat(off.restoreUpload("gone.jpg")).isFalse();
        assertThat(s3.objects).isEmpty();
    }

    @Test
    void pushCopiesTheFileUnderUploadsPrefix() throws IOException {
        Files.writeString(uploads.resolve("QR_5.png"), "qr-bytes");

        mirror.pushUpload("QR_5.png");

        assertThat(s3.text("uploads/QR_5.png")).isEqualTo("qr-bytes");
    }

    @Test
    void exhibitPhotosGoUnderTheirOwnPrefix() throws IOException {
        Path exhibits = Files.createDirectories(tmp.resolve("exhibits"));
        Files.writeString(exhibits.resolve("abc.jpg"), "photo");

        mirror.pushExhibit(exhibits.resolve("abc.jpg"));
        Files.delete(exhibits.resolve("abc.jpg"));

        assertThat(s3.text("uploads/exhibits/abc.jpg")).isEqualTo("photo");
        assertThat(mirror.restoreExhibit(exhibits, "abc.jpg")).isTrue();
        assertThat(Files.readString(exhibits.resolve("abc.jpg"))).isEqualTo("photo");
        assertThat(mirror.deleteExhibit("abc.jpg")).isTrue();
        assertThat(s3.objects).doesNotContainKey("uploads/exhibits/abc.jpg");
    }

    @Test
    void restoreBringsBackAFileWipedByARedeploy() throws IOException {
        Files.writeString(uploads.resolve("selfie.jpg"), "face");
        mirror.pushUpload("selfie.jpg");
        Files.delete(uploads.resolve("selfie.jpg")); // what a redeploy does

        assertThat(mirror.restoreUpload("selfie.jpg")).isTrue();
        assertThat(Files.readString(uploads.resolve("selfie.jpg"))).isEqualTo("face");
        assertThat(uploads.resolve("selfie.jpg.part")).doesNotExist();
    }

    @Test
    void restoreLeavesAFileAlreadyOnDiskAlone() throws IOException {
        Files.writeString(uploads.resolve("local.jpg"), "local");
        s3.objects.put("uploads/local.jpg", "older".getBytes(StandardCharsets.UTF_8));

        assertThat(mirror.restoreUpload("local.jpg")).isTrue();

        assertThat(Files.readString(uploads.resolve("local.jpg"))).isEqualTo("local");
        assertThat(s3.gets).isZero();
    }

    @Test
    void restoreOfAFileThatIsNowhereReturnsFalseAndCreatesNothing() {
        assertThat(mirror.restoreUpload("missing.jpg")).isFalse();
        assertThat(uploads.resolve("missing.jpg")).doesNotExist();
    }

    @Test
    void deleteRemovesTheS3Copy_andReportsAFailedDelete() throws IOException {
        Files.writeString(uploads.resolve("ban-1.jpg"), "mug");
        mirror.pushUpload("ban-1.jpg");

        assertThat(mirror.deleteUpload("ban-1.jpg")).isTrue();
        assertThat(s3.objects).isEmpty();

        s3.failDeletes = true;
        assertThat(mirror.deleteUpload("ban-2.jpg")).isFalse(); // the purge keeps the field and retries next night
    }

    @Test
    void aFailedUploadIsLoggedNotThrown() throws IOException {
        Files.writeString(uploads.resolve("a.jpg"), "x");
        s3.failPuts = true;

        mirror.pushUpload("a.jpg"); // the booking must still go through with its local file

        assertThat(s3.objects).isEmpty();
    }

    @Test
    void unsafeNamesNeverReachS3() {
        for (String bad : new String[] {null, "", "../secret.txt", "a/b.jpg", "a\\b.jpg", ".hidden", "x..y.jpg", "exhibits/y.jpg"}) {
            mirror.pushUpload(bad);
            assertThat(mirror.deleteUpload(bad)).as("delete " + bad).isTrue();
            assertThat(mirror.restoreUpload(bad)).as("restore " + bad).isFalse();
        }
        assertThat(s3.puts + s3.gets + s3.deletes).isZero();
    }

    @Test
    void requestPathsMapToTheRightFolder() throws IOException {
        Path exhibits = Files.createDirectories(tmp.resolve("exhibits"));
        s3.objects.put("uploads/a.jpg", "A".getBytes(StandardCharsets.UTF_8));
        s3.objects.put("uploads/exhibits/b.jpg", "B".getBytes(StandardCharsets.UTF_8));

        mirror.restoreForRequest("/uploads/a.jpg", exhibits);
        mirror.restoreForRequest("/exhibit-photos/b.jpg", exhibits);
        mirror.restoreForRequest("/admin/anything", exhibits);
        mirror.restoreForRequest("/uploads/../../etc/passwd", exhibits);

        assertThat(Files.readString(uploads.resolve("a.jpg"))).isEqualTo("A");
        assertThat(Files.readString(exhibits.resolve("b.jpg"))).isEqualTo("B");
        assertThat(s3.gets).isEqualTo(2);
    }

    // In-memory S3: only the four calls UploadMirror makes
    static class FakeS3 implements S3Client {
        final Map<String, byte[]> objects = new HashMap<>();
        int puts, gets, deletes;
        boolean failPuts, failDeletes;

        String text(String key) {
            byte[] b = objects.get(key);
            return b == null ? null : new String(b, StandardCharsets.UTF_8);
        }

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            puts++;
            if (failPuts) throw new IllegalStateException("S3 down");
            try (var in = body.contentStreamProvider().newStream()) {
                objects.put(request.key(), in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return PutObjectResponse.builder().build();
        }

        @Override
        public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
            gets++;
            byte[] b = objects.get(request.key());
            if (b == null) throw NoSuchKeyException.builder().message("no such key").build();
            return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), b);
        }

        @Override
        public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
            deletes++;
            if (failDeletes) throw new IllegalStateException("S3 down");
            objects.remove(request.key());
            return DeleteObjectResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }
    }
}
