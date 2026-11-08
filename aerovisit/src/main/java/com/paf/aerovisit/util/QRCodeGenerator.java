package com.paf.aerovisit.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.nio.file.FileSystems;
import java.nio.file.Path;

public class QRCodeGenerator {

    // This method generates a QR code image and saves it to the specified file path
    public static void generateQRCode(String text, String filePath) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();

        // 1. Create the QR Matrix (250 pixels wide, 250 pixels high)
        BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 250, 250);

        // 2. Save the matrix as a PNG image to the file system
        Path path = FileSystems.getDefault().getPath(filePath);
        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", path);
    }

    // 🚶 A QR as PNG bytes, in memory (the walk-in gate pass: shown on the visitor's pages and embedded in the email,
    // never saved under uploads/)
    public static byte[] pngBytes(String text, int size) {
        try {
            BitMatrix bitMatrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", png);
            return png.toByteArray();
        } catch (com.google.zxing.WriterException | java.io.IOException e) {
            throw new IllegalStateException("QR code could not be made: " + e.getMessage(), e);
        }
    }

    // The same QR as a data: URI for an <img src> on our own pages (emails use cid:, since mail apps block data: images)
    public static String dataUri(String text, int size) {
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pngBytes(text, size));
    }
}