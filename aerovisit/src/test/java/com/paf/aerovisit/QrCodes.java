package com.paf.aerovisit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

/** 🔍 Reads a QR back to its text, the way the gate scanner would (tests check the walk-in pass holds the secure token). */
public final class QrCodes {

    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    private QrCodes() {}

    public static String decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) throw new IllegalArgumentException("not an image");
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        } catch (Exception e) {
            throw new AssertionError("The QR code could not be read: " + e, e);
        }
    }

    public static String decodeDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith(DATA_URI_PREFIX)) throw new AssertionError("Not a PNG data URI: " + dataUri);
        return decode(Base64.getDecoder().decode(dataUri.substring(DATA_URI_PREFIX.length())));
    }

    /** The first <img src="data:image/png;base64,..."> on a page, decoded */
    public static String decodeFirstOnPage(String html) {
        int at = html.indexOf(DATA_URI_PREFIX);
        if (at < 0) throw new AssertionError("No QR image on the page");
        int end = html.indexOf('"', at);
        return decodeDataUri(html.substring(at, end));
    }
}
