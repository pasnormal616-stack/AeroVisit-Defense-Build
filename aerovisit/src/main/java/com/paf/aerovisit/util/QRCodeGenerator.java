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
}