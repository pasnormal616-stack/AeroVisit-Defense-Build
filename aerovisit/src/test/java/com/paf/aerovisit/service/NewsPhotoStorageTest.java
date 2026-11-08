package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.paf.aerovisit.repository.ExhibitPhotoRepository;

/** News photos: the exhibit photo checks with a 10 MB limit, then upright, max 1600 px, re-encoded, random name, S3 copy. Temp folder. */
class NewsPhotoStorageTest {

    @TempDir
    Path dir;

    private ExhibitPhotoStorage exhibits;
    private UploadMirror mirror;
    private NewsPhotoStorage storage;

    @BeforeEach
    void setUp() {
        exhibits = new ExhibitPhotoStorage(mock(ExhibitPhotoRepository.class), dir.toString());
        mirror = mock(UploadMirror.class);
        exhibits.uploadMirror = mirror;
        storage = new NewsPhotoStorage(exhibits);
    }

    static byte[] image(String format, int w, int h, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(alpha ? new Color(10, 40, 128, 120) : new Color(10, 40, 128));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.YELLOW);
        g.fillRect(0, 0, Math.max(1, w / 4), Math.max(1, h / 4)); // a marked top-left corner
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static MockMultipartFile upload(String name, String type, byte[] bytes) {
        return new MockMultipartFile("photo", name, type, bytes);
    }

    private BufferedImage stored(NewsPhotoStorage.StoredPhoto photo) throws Exception {
        return ImageIO.read(dir.resolve(photo.filename()).toFile());
    }

    @Test
    void aLargePhotoIsDownsizedTo1600OnItsLongestSide() throws Exception {
        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("hangar.jpg", "image/jpeg", image("jpg", 3000, 2000, false)));

        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.height()).isEqualTo(1067);
        BufferedImage saved = stored(photo);
        assertThat(saved.getWidth()).isEqualTo(1600);
        assertThat(saved.getHeight()).isEqualTo(1067);
        assertThat(photo.filename()).endsWith(".jpg");
    }

    @Test
    void aPortraitPhotoIsLimitedByItsHeight() throws Exception {
        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("tall.png", "image/png", image("png", 1200, 4000, false)));
        assertThat(photo.height()).isEqualTo(1600);
        assertThat(photo.width()).isEqualTo(480);
    }

    @Test
    void aSmallPhotoIsNeverUpscaledButStillReEncoded() throws Exception {
        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("small.png", "image/png", image("png", 800, 600, false)));
        assertThat(photo.width()).isEqualTo(800);
        assertThat(photo.height()).isEqualTo(600);
        assertThat(photo.filename()).endsWith(".jpg"); // no transparency: JPEG
        assertThat(stored(photo).getWidth()).isEqualTo(800);
    }

    @Test
    void aPngWithTransparencyStaysPng() throws Exception {
        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("logo.png", "image/png", image("png", 400, 300, true)));
        assertThat(photo.filename()).endsWith(".png");
        assertThat(stored(photo).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void aPhoneJpegTaggedOrientation6IsTurnedUpright() throws Exception {
        byte[] sideways = withExifOrientation(image("jpg", 400, 200, false), 6);
        assertThat(NewsPhotoStorage.exifOrientation(sideways)).isEqualTo(6);

        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("phone.jpg", "image/jpeg", sideways));

        assertThat(photo.width()).isEqualTo(200);
        assertThat(photo.height()).isEqualTo(400);
        // Rotated 90° clockwise: the yellow top-left corner is now at the top right
        BufferedImage saved = stored(photo);
        Color topRight = new Color(saved.getRGB(195, 5));
        assertThat(topRight.getRed()).isGreaterThan(200);
        assertThat(topRight.getGreen()).isGreaterThan(200);
    }

    @Test
    void noExifMeansAsStored() throws Exception {
        assertThat(NewsPhotoStorage.exifOrientation(image("jpg", 10, 10, false))).isEqualTo(1);
        assertThat(NewsPhotoStorage.exifOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 2})).isEqualTo(1);
    }

    @Test
    void theStoredNameIsRandomAndEveryFileIsCopiedToS3() throws Exception {
        byte[] bytes = image("jpg", 100, 100, false);
        NewsPhotoStorage.StoredPhoto a = storage.store(upload("../../evil name.jpg", "image/jpeg", bytes));
        NewsPhotoStorage.StoredPhoto b = storage.store(upload("../../evil name.jpg", "image/jpeg", bytes));

        assertThat(a.filename()).isNotEqualTo(b.filename()).doesNotContain("evil").matches("[0-9a-f-]{36}\\.jpg");
        assertThat(Files.exists(dir.resolve(a.filename()))).isTrue();
        verify(mirror, times(2)).pushExhibit(any());
    }

    @Test
    void aTextFileNamedJpgIsRefused() {
        assertThatThrownBy(() -> storage.store(upload("photo.jpg", "image/jpeg", "<?php echo 1; ?>".getBytes())))
                .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class)
                .hasMessageContaining("not a valid image file");
    }

    @Test
    void aTruncatedPngIsRefused() throws Exception {
        byte[] png = image("png", 300, 300, false);
        byte[] cut = Arrays.copyOf(png, png.length / 3);
        assertThatThrownBy(() -> storage.store(upload("cut.png", "image/png", cut)))
                .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class);
    }

    @Test
    void aGifOrAWrongContentTypeIsRefused() throws Exception {
        assertThatThrownBy(() -> storage.store(upload("anim.gif", "image/gif", image("gif", 10, 10, false))))
                .hasMessageContaining("not a JPG, PNG or WebP image");
        assertThatThrownBy(() -> storage.store(upload("photo.jpg", "text/html", image("jpg", 10, 10, false))))
                .hasMessageContaining("not a JPG, PNG or WebP image");
    }

    @Test
    void aFileOver10MbIsRefusedBeforeItIsRead() {
        byte[] big = new byte[(int) NewsPhotoStorage.MAX_BYTES + 1];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> storage.store(upload("big.jpg", "image/jpeg", big)))
                .isInstanceOf(ExhibitPhotoStorage.InvalidImageException.class)
                .hasMessageContaining("larger than 10 MB");
    }

    @Test
    void theExhibitLimitIsStill5Mb() {
        byte[] big = new byte[(int) ExhibitPhotoStorage.MAX_BYTES + 1];
        assertThatThrownBy(() -> exhibits.storeFile(upload("big.jpg", "image/jpeg", big)))
                .hasMessageContaining("larger than 5 MB");
    }

    @Test
    void deleteRemovesTheFileAndItsS3Copy() throws Exception {
        NewsPhotoStorage.StoredPhoto photo = storage.store(upload("a.jpg", "image/jpeg", image("jpg", 50, 50, false)));
        storage.delete(photo.filename());
        assertThat(Files.exists(dir.resolve(photo.filename()))).isFalse();
        verify(mirror).deleteExhibit(photo.filename());
    }

    // Inserts an APP1 "Exif" segment (big-endian TIFF, one IFD0 entry: Orientation) right after the SOI marker
    static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
            'M', 'M', 0, 42, 0, 0, 0, 8,           // header, IFD0 at offset 8
            0, 1,                                  // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, count 1, value
            0, 0, 0, 0                             // no next IFD
        };
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiff.length;
        byte[] out = new byte[jpeg.length + 2 + length];
        out[0] = (byte) 0xFF; out[1] = (byte) 0xD8;
        out[2] = (byte) 0xFF; out[3] = (byte) 0xE1;
        out[4] = (byte) (length >> 8); out[5] = (byte) length;
        System.arraycopy(exifHeader, 0, out, 6, exifHeader.length);
        System.arraycopy(tiff, 0, out, 6 + exifHeader.length, tiff.length);
        System.arraycopy(jpeg, 2, out, 4 + length, jpeg.length - 2);
        return out;
    }
}
