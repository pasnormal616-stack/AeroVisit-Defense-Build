package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.paf.aerovisit.model.ExhibitPhoto;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;

/** Upload validation for the Exhibits slideshow: only genuine JPEG / PNG / WebP images reach the disk. */
class ExhibitPhotoStorageTest {

    // A real 1x1 lossy WebP image
    private static final byte[] WEBP_1X1 = Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=");

    @TempDir
    Path dir;

    private ExhibitPhotoRepository repo;
    private ExhibitPhotoStorage storage;

    @BeforeEach
    void setUp() {
        repo = mock(ExhibitPhotoRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        storage = new ExhibitPhotoStorage(repo, dir.toString());
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    private void assertRejected(MockMultipartFile file, String message) {
        assertThatThrownBy(() -> storage.store(file))
            .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class)
            .hasMessage(message);
        verify(repo, never()).save(any());
    }

    private long filesOnDisk() throws Exception {
        try (var files = Files.list(dir)) {
            return files.count();
        }
    }

    // --- Accepted ---

    @Test
    void acceptsJpegPngAndWebpUnderARandomName() throws Exception {
        ExhibitPhoto jpg = storage.store(new MockMultipartFile("photos", "F-5 Freedom Fighter.JPEG", "image/jpeg", image("jpg")));
        ExhibitPhoto png = storage.store(new MockMultipartFile("photos", "huey.png", "image/png", image("png")));
        ExhibitPhoto webp = storage.store(new MockMultipartFile("photos", "fokker.webp", "image/webp", WEBP_1X1));

        assertThat(jpg.getFilename()).matches("[0-9a-f-]{36}\\.jpg");
        assertThat(png.getFilename()).matches("[0-9a-f-]{36}\\.png");
        assertThat(webp.getFilename()).matches("[0-9a-f-]{36}\\.webp");
        assertThat(jpg.getUploadDate()).isNotNull();
        assertThat(Files.readAllBytes(dir.resolve(png.getFilename()))).isEqualTo(image("png"));
        assertThat(filesOnDisk()).isEqualTo(3);
    }

    @Test
    void uploadedFileNameNeverReachesTheDisk() throws Exception {
        ExhibitPhoto saved = storage.store(new MockMultipartFile("photos", "../../../static/index.png", "image/png", image("png")));
        assertThat(saved.getFilename()).doesNotContain("..", "/", "\\", "index");
        assertThat(Files.exists(dir.resolve(saved.getFilename()))).isTrue();
        assertThat(filesOnDisk()).isEqualTo(1);
    }

    // --- Rejected ---

    @Test
    void rejectsDisallowedExtensions() throws Exception {
        assertRejected(new MockMultipartFile("photos", "logo.svg", "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8)),
            "\"logo.svg\" is not a JPG, PNG or WebP image.");
        assertRejected(new MockMultipartFile("photos", "page.html", "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)),
            "\"page.html\" is not a JPG, PNG or WebP image.");
        assertRejected(new MockMultipartFile("photos", "anim.gif", "image/gif", image("gif")),
            "\"anim.gif\" is not a JPG, PNG or WebP image.");
        assertRejected(new MockMultipartFile("photos", "noextension", "image/png", image("png")),
            "\"noextension\" is not a JPG, PNG or WebP image.");
        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void rejectsAContentTypeThatDoesNotMatchTheExtension() throws Exception {
        assertRejected(new MockMultipartFile("photos", "photo.jpg", "text/html", image("jpg")),
            "\"photo.jpg\" is not a JPG, PNG or WebP image.");
    }

    @Test
    void rejectsAScriptRenamedToJpg() throws Exception {
        assertRejected(new MockMultipartFile("photos", "shell.php.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8)),
            "\"shell.php.jpg\" is not a valid image file.");
        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void rejectsAPngDisguisedAsAJpg() throws Exception {
        assertRejected(new MockMultipartFile("photos", "photo.jpg", "image/jpeg", image("png")),
            "\"photo.jpg\" is not a valid image file.");
    }

    @Test
    void rejectsAFakeHeaderFollowedByHtml() throws Exception {
        byte[] fake = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, '<', 'h', 't', 'm', 'l', '>'};
        assertRejected(new MockMultipartFile("photos", "polyglot.jpg", "image/jpeg", fake),
            "\"polyglot.jpg\" is not a valid image file.");
    }

    @Test
    void rejectsATruncatedImage() throws Exception {
        byte[] png = image("png");
        assertRejected(new MockMultipartFile("photos", "cut.png", "image/png", Arrays.copyOf(png, png.length / 2)),
            "\"cut.png\" is not a valid image file.");
    }

    @Test
    void rejectsFilesOverFiveMegabytes() throws Exception {
        byte[] big = Arrays.copyOf(image("png"), (int) ExhibitPhotoStorage.MAX_BYTES + 1);
        assertRejected(new MockMultipartFile("photos", "huge.png", "image/png", big),
            "\"huge.png\" is larger than 5 MB.");
    }

    @Test
    void rejectsAnEmptyUpload() throws Exception {
        assertRejected(new MockMultipartFile("photos", "empty.png", "image/png", new byte[0]),
            "Please choose a photo to upload.");
    }

    // --- Delete ---

    @Test
    void deleteRemovesTheFileAndTheRecord() throws Exception {
        ExhibitPhoto saved = storage.store(new MockMultipartFile("photos", "huey.png", "image/png", image("png")));
        saved.setId(4L);
        when(repo.findById(4L)).thenReturn(Optional.of(saved));

        assertThat(storage.delete(4L)).isTrue();
        assertThat(filesOnDisk()).isZero();
        verify(repo).delete(saved);
    }

    @Test
    void deleteNeverTouchesFilesOutsideTheExhibitsFolder() throws Exception {
        Path outside = Files.writeString(dir.getParent().resolve("keep-" + System.nanoTime() + ".txt"), "keep");
        ExhibitPhoto tampered = new ExhibitPhoto("../" + outside.getFileName(), null);
        when(repo.findById(6L)).thenReturn(Optional.of(tampered));
        try {
            assertThat(storage.delete(6L)).isTrue();
            assertThat(Files.exists(outside)).isTrue();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void deletingAMissingPhotoReportsFalse() throws Exception {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThat(storage.delete(99L)).isFalse();
    }
}
