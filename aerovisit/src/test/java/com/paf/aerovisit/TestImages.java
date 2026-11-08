package com.paf.aerovisit;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real, decodable image bytes for upload tests. The booking's ID photos and payment receipts are checked by their
 * signature AND a full decode (ReceiptStorage.realImageFormat), so a few fake header bytes are no longer enough.
 * A different shade gives different bytes, for tests that tell the selfie and the ID close-up apart.
 */
public final class TestImages {

    private TestImages() {}

    public static byte[] jpeg(int shade) {
        return encode(shade, "jpg");
    }

    public static byte[] png(int shade) {
        return encode(shade, "png");
    }

    private static byte[] encode(int shade, String format) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(shade % 256, (shade * 37) % 256, (shade * 91) % 256));
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) throw new IllegalStateException("No ImageIO writer for " + format);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
