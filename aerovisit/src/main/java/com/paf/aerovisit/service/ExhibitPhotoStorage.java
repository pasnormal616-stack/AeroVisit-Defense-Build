package com.paf.aerovisit.service;

import com.paf.aerovisit.model.ExhibitPhoto;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Saves and removes the Exhibits slideshow photos.
// 🔒 Only real JPEG, PNG and WebP images are accepted. A file must pass every check:
//    extension, declared content type, file signature (magic bytes), and a full decode by ImageIO.
//    It is stored under a random server-generated name, so the uploaded file name never reaches the disk.
@Service
public class ExhibitPhotoStorage {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 10_000;

    private static final Map<String, String> EXTENSION_TO_FORMAT = Map.of(
            "jpg", "jpeg", "jpeg", "jpeg", "png", "png", "webp", "webp");
    private static final Map<String, Set<String>> FORMAT_CONTENT_TYPES = Map.of(
            "jpeg", Set.of("image/jpeg", "image/jpg", "image/pjpeg"),
            "png", Set.of("image/png", "image/x-png"),
            "webp", Set.of("image/webp"));
    private static final Map<String, String> FORMAT_TO_EXTENSION = Map.of(
            "jpeg", "jpg", "png", "png", "webp", "webp");

    private final ExhibitPhotoRepository photoRepo;
    private final Path directory;

    // ☁️ Copies each photo to S3 and removes it there on delete (off locally). Optional: tests build this class directly.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    UploadMirror uploadMirror;

    public ExhibitPhotoStorage(ExhibitPhotoRepository photoRepo,
                               @Value("${aerovisit.exhibits.dir:uploads/exhibits}") String directory) {
        this.photoRepo = photoRepo;
        this.directory = Paths.get(directory).toAbsolutePath().normalize();
    }

    public ExhibitPhoto store(MultipartFile file) throws IOException {
        return photoRepo.save(new ExhibitPhoto(storeFile(file), LocalDateTime.now()));
    }

    // Checks one photo and saves it (with its S3 copy) under a random server-generated name; returns that name.
    // Shared by the Exhibits slideshow and the hero slideshow.
    public String storeFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("Please choose a photo to upload.");
        }
        String label = displayName(file);
        if (file.getSize() > MAX_BYTES) {
            throw new InvalidImageException(label + " is larger than 5 MB.");
        }
        String format = formatFromExtension(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (format == null || !FORMAT_CONTENT_TYPES.get(format).contains(contentType)) {
            throw new InvalidImageException(label + " is not a JPG, PNG or WebP image.");
        }
        byte[] bytes = file.getBytes();
        if (!format.equals(formatFromSignature(bytes)) || !decodes(bytes, format)) {
            throw new InvalidImageException(label + " is not a valid image file.");
        }

        Files.createDirectories(directory);
        String filename = UUID.randomUUID() + "." + FORMAT_TO_EXTENSION.get(format);
        Files.write(directory.resolve(filename), bytes, StandardOpenOption.CREATE_NEW);
        if (uploadMirror != null) uploadMirror.pushExhibit(directory.resolve(filename));
        return filename;
    }

    // Removes the database record and its file; returns false if the photo no longer exists
    public boolean delete(Long id) throws IOException {
        ExhibitPhoto photo = photoRepo.findById(id).orElse(null);
        if (photo == null) return false;
        deleteFile(photo.getFilename());
        photoRepo.delete(photo);
        return true;
    }

    // Removes one stored photo file and its S3 copy; a name that points outside the photo folder is never touched
    public void deleteFile(String filename) throws IOException {
        Path file = directory.resolve(filename).normalize();
        if (file.startsWith(directory)) {
            Files.deleteIfExists(file);
        }
        if (uploadMirror != null) uploadMirror.deleteExhibit(filename);
    }

    public Path getDirectory() {
        return directory;
    }

    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "The file";
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        return "\"" + (name.length() > 60 ? name.substring(0, 60) + "..." : name) + "\"";
    }

    private static String formatFromExtension(String originalFilename) {
        if (originalFilename == null) return null;
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0) return null;
        return EXTENSION_TO_FORMAT.get(originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String formatFromSignature(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }

    // Decodes the whole image (downsampled, to keep memory low) so truncated or disguised files are rejected
    private static boolean decodes(byte[] bytes, String format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) return false;
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(width, height) / 800);
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // The message is safe to show to the Director as-is
    public static class InvalidImageException extends IOException {
        public InvalidImageException(String message) {
            super(message);
        }
    }
}
