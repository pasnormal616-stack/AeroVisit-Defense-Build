package com.paf.aerovisit.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

@Service
public class OcrService {

    // Tesseract language data folder (aerovisit.ocr.tessdata-path / TESSDATA_PATH); the Windows installer's default
    // when not set, so another machine or a server no longer needs a code change
    static final String DEFAULT_TESSDATA = "C:/Program Files/Tesseract-OCR/tessdata";
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ocr.tessdata-path:" + DEFAULT_TESSDATA + "}")
    String tessdataPath = DEFAULT_TESSDATA;

    public String extractTextFromId(MultipartFile file) throws IOException, TesseractException {
        // 1. Read the uploaded file into Java memory
        BufferedImage originalImage = ImageIO.read(file.getInputStream());
        if (originalImage == null) {
            throw new IOException("Java could not decode this image format.");
        }
        return extractText(originalImage);
    }

    // Steps 2-6 on an image already in memory (KycService passes the ID card cropped out of the selfie)
    public String extractText(BufferedImage originalImage) throws IOException, TesseractException {
        // 2. THE MAGIC TRICK: Create a brand new, pure RGB canvas
        BufferedImage pristineImage = new BufferedImage(
                originalImage.getWidth(), 
                originalImage.getHeight(), 
                BufferedImage.TYPE_INT_RGB);
        
        // 3. Paint the uploaded image onto the new canvas (with a white background just in case of transparency)
        Graphics2D g2d = pristineImage.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, pristineImage.getWidth(), pristineImage.getHeight());
        g2d.drawImage(originalImage, 0, 0, null);
        g2d.dispose();

        // 4. Save this pristine image as a guaranteed, universally supported .png file
        File safeTempFile = File.createTempFile("safe_ocr_image_", ".png");
        
        try {
            ImageIO.write(pristineImage, "png", safeTempFile);

            // 5. Initialize Tesseract and hand it the perfect .png
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage("eng");

            return tesseract.doOCR(safeTempFile);
            
        } finally {
            // 6. Always clean up the temp file
            if (safeTempFile.exists()) {
                safeTempFile.delete();
            }
        }
    }
}