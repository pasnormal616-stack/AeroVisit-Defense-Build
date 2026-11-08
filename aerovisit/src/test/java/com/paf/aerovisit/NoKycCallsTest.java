package com.paf.aerovisit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 🪪 KYC is gone from the Java side: the identity-check classes no longer exist, nothing in the main code calls the AI
 * service's /kyc endpoints, and no ID text recognition library is on the classpath. (The /save flows themselves are
 * checked for zero AI calls in PayFirstBookingTest; the chatbot and forecast keep their own AI calls.)
 */
class NoKycCallsTest {

    private static final Path MAIN = Paths.get("src", "main", "java");

    @Test
    void theIdentityCheckClassesAreGone() {
        for (String name : List.of("com.paf.aerovisit.service.KycService", "com.paf.aerovisit.service.FaceEncodings",
                "com.paf.aerovisit.service.OcrService", "com.paf.aerovisit.controller.IdVerificationController")) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
        }
        // tess4j (OCR) was removed from the build
        assertThatThrownBy(() -> Class.forName("net.sourceforge.tess4j.Tesseract")).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void noMainSourceFileCallsAKycEndpointOrUsesTheOldClasses() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    return src.contains("/kyc/") || src.contains("\"/kyc") || src.contains("KycService")
                            || src.contains("FaceEncodings") || src.contains("OcrService") || src.contains("/api/booking/verify-id");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(Path::toString).toList();
        }
        assertThat(offenders).isEmpty();
    }
}
