package com.paf.aerovisit.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.paf.aerovisit.repository.BlacklistedFaceRepository;

// 🪪 TWO-STEP KYC: the browser sends a close-up of the ID alone and a liveness selfie holding it. The Python service
// (POST /kyc/verify, OpenCV) cuts the card out of the close-up, compares the ID portrait 1:1 with the live face (SFace)
// and returns the live face's vector. Java then refuses a banned face, and runs the existing Tesseract OCR
// (OcrService) + fuzzy first/last name match on the card crop.
// HUMAN-IN-THE-LOOP: nothing is ever approved here, and nobody is rejected on a face comparison (a soft ID portrait
// can make a genuine visitor score low). Every booking waits for staff: PENDING when the face matched and the name was
// read (the "Identity Verified (face + name)" badge via ocrNameMatch()), and PENDING_MANUAL_REVIEW ("Identity
// Unverified") for anything else: a face mismatch, a face not found or found only weakly, name not read, AI offline,
// models missing, unreadable answer. Never throws. Only a banned face is REFUSED automatically.
@Service
public class KycService {

    public static final String PENDING = "PENDING";
    public static final String MANUAL_REVIEW = "PENDING_MANUAL_REVIEW";
    // Not a Visitor status: /save shows the message and saves nothing (a banned face)
    public static final String REFUSED = "REFUSED";
    // No longer returned by screen() (a face mismatch goes to manual review); /save still handles it if ever returned
    public static final String REJECTED = "REJECTED";

    public static final String FACE_MISMATCH = "Booking failed: your selfie did not match the photo on your ID. If this is a mistake, please contact the reception desk.";
    // Deliberately generic (silent security): it must not tell a banned person they are banned, restricted or recognised
    public static final String FACE_BANNED = "Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance.";

    // faceEncoding: the live face's vector (JSON array) when the AI service returned one, else null
    public record KycResult(String status, String reason, String message, String faceEncoding) {
        public KycResult(String status, String reason) {
            this(status, reason, null, null);
        }

        public boolean refused() {
            return REFUSED.equals(status);
        }

        public boolean rejected() {
            return REJECTED.equals(status);
        }

        // Tesseract read both names on the ID close-up (as taken, or after flipping a mirrored photo back)
        public boolean ocrNameMatch() {
            return "OK".equals(reason) || "OK_MIRRORED".equals(reason);
        }
    }

    private static final String API_BASE_URL = "http://localhost:8000";
    // AI service address (aerovisit.ai.base-url / AI_SERVICE_URL); tests point this at a local stub server
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ai.base-url:" + API_BASE_URL + "}")
    String apiBaseUrl = API_BASE_URL;

    // Shared secret for the AI service (AI_INTERNAL_KEY on both services); blank (local dev, tests) = not sent
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ai.internal-key:}")
    String internalKey = "";

    // Two photos, card search + two face searches: a few seconds at most; past this the booking goes to manual review
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    Duration timeout = TIMEOUT; // tests shorten this

    // Every call to the AI service starts here: address, time limit and the shared-secret header
    private HttpRequest.Builder aiRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
        return internalKey == null || internalKey.isBlank() ? builder : builder.header("X-Internal-Key", internalKey);
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final OcrService ocrService;

    // Optional, so web-layer test slices without the repository still start; without it no face is ever banned
    @Autowired(required = false)
    BlacklistedFaceRepository faceBans;

    // 🤖 AI features switch (Settings): off = no call to the AI service, the booking goes straight to manual review
    @Autowired(required = false)
    AiSwitch aiSwitch;

    public KycService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public KycResult screen(byte[] idCardPhoto, byte[] livenessSelfie, String firstName, String lastName) {
        if (aiSwitch != null && !aiSwitch.enabled()) return review("AI_OFF", null);
        try {
            JSONObject answer = verify(idCardPhoto, livenessSelfie);
            if (answer == null) return review("AI_SERVICE_UNAVAILABLE", null);
            String reason = answer.optString("reason", "UNKNOWN");
            // 🔎 Logged on every booking so the blur threshold (KYC_MIN_FACE_SHARPNESS) can be tuned on real phone photos
            System.out.println("KYC readings: status=" + answer.optString("status", "-") + " reason=" + reason
                    + " live_sharpness=" + answer.opt("live_sharpness") + " id_sharpness=" + answer.opt("id_sharpness")
                    + " similarity=" + answer.opt("face_similarity"));

            // 🧬 Sybil check first: Python returns the live face's vector even when the ID side was unclear, so a banned
            // face is refused whatever name or email it books under, and can't dodge the check by hiding the ID portrait
            double[] face = FaceEncodings.fromArray(answer.optJSONArray("live_face_encoding"));
            String encoding = face == null ? null : FaceEncodings.format(face);
            if (face != null && faceBans != null && FaceEncodings.isBanned(face, faceBans.findAll())) {
                return refuse("FACE_BANNED", FACE_BANNED, null);
            }

            // 🧭 The AI service's verdict. POOR_QUALITY (no/weak face, blur, a score just under the match line) and ERROR
            // are innocent problems: staff review the photos. MISMATCH (the faces scored as different people) also goes to
            // staff, never an automatic rejection: a soft printed ID portrait can make a genuine visitor score low. The
            // vector is kept, so staff can still ban the face. An older AI service without "status" falls through to
            // the field checks below, which give the same routing.
            switch (answer.optString("status", "")) {
                case "POOR_QUALITY", "ERROR" -> { return review(reason, encoding); }
                case "MISMATCH" -> { return review("FACE_MISMATCH", encoding); }
                default -> { } // SUCCESS (or no status): continue with the OCR name check
            }

            // GRACEFUL DEGRADATION: a face that couldn't be found (ID portrait under glare, no card, no live face) or was
            // found too weakly to trust a mismatch goes to staff with the photos, never back to the visitor as an error
            if (!answer.isNull("face_match_error") && !answer.optString("face_match_error").isBlank()) {
                return review(reason, encoding);
            }
            if (!answer.has("is_face_match") || answer.isNull("is_face_match")) return review(reason, encoding);
            // Both faces found but scored as different people (older AI service): to staff the same way
            if (!answer.optBoolean("is_face_match", false)) return review("FACE_MISMATCH", encoding);
            if (face == null) return review("NO_FACE_ENCODING", null);

            String cropB64 = answer.optString("id_crop_b64", "");
            if (!answer.optBoolean("id_detected", false) || cropB64.isEmpty()) return review("NO_ID_CARD", encoding);
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(cropB64)));
            if (crop == null) return review("BAD_CROP", encoding);
            // Biometrics passed: the booking joins the normal approval queue; OCR only decides the badge
            if (namesMatch(ocrService.extractText(crop), firstName, lastName)) return new KycResult(PENDING, "OK", null, encoding);
            // Some front cameras save photos mirrored, which makes the ID text unreadable: one retry, flipped back
            if (namesMatch(ocrService.extractText(mirrored(crop)), firstName, lastName)) {
                return new KycResult(PENDING, "OK_MIRRORED", null, encoding);
            }
            return review("NAME_MISMATCH", encoding);
        } catch (Exception | LinkageError e) { // LinkageError: Tesseract's native library missing on this machine
            System.out.println("KYC check failed, sending to manual review: " + e);
            return review("ERROR", null);
        }
    }

    // Both names must appear on the ID, each within a small OCR-error tolerance (Levenshtein, see FuzzyNameMatcher).
    // Blank or missing names never match.
    static boolean namesMatch(String rawIdText, String firstName, String lastName) {
        return FuzzyNameMatcher.matches(rawIdText, firstName, lastName);
    }

    static BufferedImage mirrored(BufferedImage image) {
        BufferedImage flipped = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flipped.createGraphics();
        g.drawImage(image, image.getWidth(), 0, -image.getWidth(), image.getHeight(), null);
        g.dispose();
        return flipped;
    }

    // The Python answer, or null when the service is down, slow or not answering 200
    private JSONObject verify(byte[] idCardPhoto, byte[] livenessSelfie) {
        try {
            String body = new JSONObject()
                    .put("high_res_id_image", Base64.getEncoder().encodeToString(idCardPhoto))
                    .put("liveness_image", Base64.getEncoder().encodeToString(livenessSelfie))
                    .toString();
            HttpRequest request = aiRequest(apiBaseUrl + "/kyc/verify")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("KYC service answered HTTP " + response.statusCode());
                return null;
            }
            return new JSONObject(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.out.println("KYC service unreachable: " + e.getMessage());
            return null;
        }
    }

    private static KycResult review(String reason, String faceEncoding) {
        System.out.println("KYC -> manual review (" + reason + ")");
        return new KycResult(MANUAL_REVIEW, reason, null, faceEncoding);
    }

    private static KycResult refuse(String reason, String message, String faceEncoding) {
        System.out.println("KYC -> refused (" + reason + ")");
        return new KycResult(REFUSED, reason, message, faceEncoding);
    }
}
