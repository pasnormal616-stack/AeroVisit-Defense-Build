package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.paf.aerovisit.TestImages;

/** 💳 Receipt screenshots: only genuine JPEG / PNG / WebP images up to 5 MB, under a random name; an earlier one is never deleted. */
class ReceiptStorageTest {

    // A real 1x1 lossy WebP image (the same one ExhibitPhotoStorageTest uses)
    private static final byte[] WEBP_1X1 = Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=");

    @TempDir
    Path uploads;

    private ReceiptStorage storage() {
        return new ReceiptStorage(uploads);
    }

    @Test
    void jpegPngAndWebpScreenshotsAreSavedUnderARandomReceiptName() throws Exception {
        String jpg = storage().saveReceipt(new MockMultipartFile("receipt", "IMG_0042.JPG", "image/jpeg", TestImages.jpeg(9)));
        String webp = storage().saveReceipt(new MockMultipartFile("receipt", "shot.webp", "image/webp", WEBP_1X1));
        // the bytes decide the extension, not the name the phone gave it
        String png = storage().saveReceipt(new MockMultipartFile("receipt", "shot.jpg", "image/jpeg", TestImages.png(30)));

        assertThat(jpg).matches("receipt-[0-9a-f-]{36}\\.jpg");
        assertThat(webp).matches("receipt-[0-9a-f-]{36}\\.webp");
        assertThat(png).matches("receipt-[0-9a-f-]{36}\\.png");
        assertThat(uploads.resolve(webp)).exists();
        assertThat(ReceiptStorage.contentType(webp)).isEqualTo("image/webp");
    }

    @Test
    void aNewReceiptKeepsTheEarlierOneBecauseAReceiptIsProofOfPayment() throws Exception {
        String first = storage().saveReceipt(new MockMultipartFile("receipt", "a.jpg", "image/jpeg", TestImages.jpeg(9)));
        String second = storage().saveReceipt(new MockMultipartFile("receipt", "b.webp", "image/webp", WEBP_1X1));
        assertThat(second).isNotEqualTo(first);
        assertThat(uploads.resolve(first)).exists();
        assertThat(uploads.resolve(second)).exists();
    }

    @Test
    void nonImagesGifsTruncatedFilesAndOversizedFilesAreRefused() {
        byte[] exe = "MZ\u0090\u0000 not a picture".getBytes(StandardCharsets.ISO_8859_1);
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
        byte[] truncatedWebp = java.util.Arrays.copyOf(WEBP_1X1, 16);
        for (byte[] bad : new byte[][] {exe, gif, truncatedWebp, new byte[0]}) {
            assertThatThrownBy(() -> storage().saveReceipt(new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", bad)))
                .isInstanceOf(ReceiptStorage.InvalidReceiptException.class)
                .hasMessage("Please upload a JPG, PNG or WEBP screenshot of your receipt.");
        }
        byte[] sixMb = new byte[6 * 1024 * 1024];
        assertThatThrownBy(() -> storage().saveReceipt(new MockMultipartFile("receipt", "big.jpg", "image/jpeg", sixMb)))
            .hasMessage("The receipt is too large. Please upload a screenshot of 5 MB or less.");
        try (var left = Files.list(uploads)) {
            assertThat(left).isEmpty();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void idPhotosAcceptJpegPngAndWebpAndHeicIsRecognised() {
        assertThat(ReceiptStorage.realImageFormat(TestImages.jpeg(9), ReceiptStorage.ID_PHOTO_FORMATS)).isEqualTo("jpeg");
        assertThat(ReceiptStorage.realImageFormat(TestImages.png(30), ReceiptStorage.ID_PHOTO_FORMATS)).isEqualTo("png");
        assertThat(ReceiptStorage.realImageFormat(WEBP_1X1, ReceiptStorage.ID_PHOTO_FORMATS)).isEqualTo("webp");
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        byte[] mif1 = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'i', 'f', '1', 0, 0, 0, 0};
        assertThat(ReceiptStorage.isHeic(heic)).isTrue();
        assertThat(ReceiptStorage.isHeic(mif1)).isTrue();
        assertThat(ReceiptStorage.isHeic(TestImages.jpeg(9))).isFalse();
        assertThat(ReceiptStorage.realImageFormat(heic, ReceiptStorage.ID_PHOTO_FORMATS)).isNull();
    }

    @Test
    void onlyReceiptNamesInsideUploadsAreEverServedOrDeleted() throws Exception {
        Files.writeString(uploads.resolve("photo-1.jpg"), "id photo");
        assertThat(storage().find("photo-1.jpg")).isNull();
        assertThat(storage().find("../secret.txt")).isNull();
        assertThat(storage().delete("photo-1.jpg")).isTrue();
        assertThat(uploads.resolve("photo-1.jpg")).exists();
    }
}
