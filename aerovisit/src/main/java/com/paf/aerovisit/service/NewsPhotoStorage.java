package com.paf.aerovisit.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

// 📰 Saves the News photos. Every file goes through the same checks as the exhibit photos (ExhibitPhotoStorage.check:
// extension, declared type, magic bytes, full decode) with a 10 MB limit, then it is turned upright (JPEG EXIF orientation),
// downsized so its longest side is at most 1600 px, and re-encoded (JPEG, or PNG when it has transparency). Re-encoding
// also drops the camera metadata (EXIF/GPS). The result is saved by ExhibitPhotoStorage.storeBytes: random file name,
// uploads/exhibits/, served at /exhibit-photos/{name}, copied to S3. The retention purge never touches these files.
@Service
public class NewsPhotoStorage {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final int MAX_EDGE = 1600;
    private static final float JPEG_QUALITY = 0.85f;

    private final ExhibitPhotoStorage storage;

    public NewsPhotoStorage(ExhibitPhotoStorage storage) {
        this.storage = storage;
    }

    // The stored file's name and its pixel size
    public record StoredPhoto(String filename, int width, int height) {}

    public StoredPhoto store(MultipartFile file) throws IOException {
        ExhibitPhotoStorage.CheckedImage checked = storage.check(file, MAX_BYTES);
        BufferedImage image = readDownsampled(checked.bytes(), checked.format());
        if (image == null) {
            throw new ExhibitPhotoStorage.InvalidImageException(checked.label() + " is not a valid image file.");
        }
        if ("jpeg".equals(checked.format())) {
            image = upright(image, exifOrientation(checked.bytes()));
        }
        image = fit(image, MAX_EDGE);
        boolean png = image.getColorModel().hasAlpha() && "png".equals(checked.format());
        byte[] encoded = png ? encodePng(image) : encodeJpeg(image);
        String filename = storage.storeBytes(encoded, png ? "png" : "jpg");
        return new StoredPhoto(filename, image.getWidth(), image.getHeight());
    }

    public void delete(String filename) throws IOException {
        storage.deleteFile(filename);
    }

    // Decodes with source subsampling so a huge photo never fills the memory: the result is still at least MAX_EDGE wide
    static BufferedImage readDownsampled(byte[] bytes, String format) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (in == null || !readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longest / MAX_EDGE);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    // Scales down (never up) so the longest side is at most maxEdge, halving first for a smooth result
    static BufferedImage fit(BufferedImage image, int maxEdge) {
        int w = image.getWidth(), h = image.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge) return normalized(image);
        int targetW = Math.max(1, (int) Math.round((double) w * maxEdge / longest));
        int targetH = Math.max(1, (int) Math.round((double) h * maxEdge / longest));
        BufferedImage current = normalized(image);
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = scale(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return scale(current, targetW, targetH);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // Plain RGB or ARGB pixels, so the encoders and Graphics2D always get a type they handle
    private static BufferedImage normalized(BufferedImage image) {
        int type = image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == type) return image;
        return scale(image, image.getWidth(), image.getHeight());
    }

    // Applies the EXIF orientation (1-8): phone cameras store the photo sideways and set this tag instead of rotating it
    static BufferedImage upright(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) return image;
        int w = image.getWidth(), h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage src = normalized(image);
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, src.getType());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, t, null);
        g.dispose();
        return out;
    }

    // Reads the orientation tag (0x0112) from a JPEG's EXIF block; 1 (as stored) when there is none or it can't be read
    static int exifOrientation(byte[] b) {
        try {
            int i = 2; // after the SOI marker FFD8
            while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
                int marker = b[i + 1] & 0xFF;
                int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (marker == 0xDA || marker == 0xD9) break; // image data starts: no EXIF before it
                if (marker == 0xE1 && length >= 8 && i + 10 <= b.length
                        && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f' && b[i + 8] == 0 && b[i + 9] == 0) {
                    return orientationFromTiff(b, i + 10, Math.min(b.length, i + 2 + length));
                }
                i += 2 + length;
            }
        } catch (RuntimeException e) {
            // a malformed block just means "as stored"
        }
        return 1;
    }

    private static int orientationFromTiff(byte[] b, int tiff, int end) {
        boolean little = b[tiff] == 'I' && b[tiff + 1] == 'I';
        if (!little && !(b[tiff] == 'M' && b[tiff + 1] == 'M')) return 1;
        int ifd = tiff + read32(b, tiff + 4, little);
        if (ifd + 2 > end) return 1;
        int entries = read16(b, ifd, little);
        for (int n = 0; n < entries; n++) {
            int entry = ifd + 2 + n * 12;
            if (entry + 12 > end) break;
            if (read16(b, entry, little) == 0x0112) {
                int value = read16(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int read16(byte[] b, int at, boolean little) {
        return little ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) : ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static int read32(byte[] b, int at, boolean little) {
        return little
                ? (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24)
                : ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getColorModel().hasAlpha()) { // JPEG has no transparency: flatten onto white
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG encoder available.");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG encoder available.");
        return out.toByteArray();
    }
}
