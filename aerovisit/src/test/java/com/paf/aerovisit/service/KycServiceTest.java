package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.sun.net.httpserver.HttpServer;

/**
 * Two-step KYC: Python (POST /kyc/verify) cuts the card from the ID close-up and matches the ID portrait 1:1 with the
 * live face; Java refuses a missing face, a wrong face or a banned face, then OCRs only the crop with the old name rule.
 * System failures end in PENDING_MANUAL_REVIEW, never an exception. Stub Python server, mocked Tesseract.
 */
class KycServiceTest {

    private static final byte[] ID_CARD = {(byte) 0xFF, (byte) 0xD8, 7, 7, 7};
    private static final byte[] SELFIE = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};

    private HttpServer python;
    private final AtomicReference<String> reply = new AtomicReference<>();
    private final AtomicReference<String> received = new AtomicReference<>();
    private volatile int replyStatus = 200;
    private volatile long replyDelayMs = 0;

    private OcrService ocr;
    private BlacklistedFaceRepository bans;
    private KycService kyc;

    @BeforeEach
    void start() throws IOException {
        python = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        python.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        python.createContext("/kyc/verify", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(replyDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(replyStatus, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientGone) {
                // the caller already gave up
            }
            exchange.close();
        });
        python.start();
        ocr = mock(OcrService.class);
        bans = mock(BlacklistedFaceRepository.class);
        when(bans.findAll()).thenReturn(List.of());
        kyc = new KycService(ocr);
        kyc.faceBans = bans;
        kyc.apiBaseUrl = "http://127.0.0.1:" + python.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        python.stop(0);
    }

    private static String pngBase64() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 25, BufferedImage.TYPE_INT_RGB), "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // A unit vector pointing along one axis: two different axes = cosine 0 (different people)
    static double[] axis(int i) {
        double[] v = new double[FaceEncodings.DIMENSIONS];
        v[i] = 1.0;
        return v;
    }

    private void pythonMatches(boolean match, double[] liveFace) throws Exception {
        reply.set(new JSONObject().put("reason", "OK").put("id_detected", true).put("id_crop_b64", pngBase64())
                .put("is_face_match", match).put("face_similarity", match ? 0.71 : 0.12)
                .put("face_match_error", JSONObject.NULL).put("live_face_encoding", new JSONArray(liveFace)).toString());
    }

    // kyc.py's graceful-degradation answer; the live face's vector comes along whenever the selfie had a face
    private void pythonSaysFaceUnclear(String reason, double[] liveFace) throws Exception {
        reply.set(new JSONObject().put("reason", reason).put("id_detected", true).put("id_crop_b64", JSONObject.NULL)
                .put("is_face_match", false).put("face_similarity", JSONObject.NULL)
                .put("face_match_error", "Face detection unclear - routing to manual review.")
                .put("live_face_encoding", liveFace == null ? JSONObject.NULL : new JSONArray(liveFace)).toString());
    }

    private void pythonHasASystemProblem(String reason) throws Exception {
        reply.set(new JSONObject().put("reason", reason).put("id_detected", false).put("id_crop_b64", JSONObject.NULL)
                .put("is_face_match", JSONObject.NULL).put("face_match_error", JSONObject.NULL)
                .put("live_face_encoding", JSONObject.NULL).toString());
    }

    private KycService.KycResult screen() {
        return kyc.screen(ID_CARD, SELFIE, "Juan", "Dela Cruz");
    }

    // --- 1:1 face match ---

    @Test
    void matchingFaceAndBothNamesOnTheCardWaitForStaffWithTheBadgeAndTheLiveVector() throws Exception {
        pythonMatches(true, axis(3));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("REPUBLIC OF THE PHILIPPINES\nDELA CRUZ, JUAN\n");

        KycService.KycResult result = screen();

        // Human-in-the-loop: never approved automatically, but staff see "ID Name Verified"
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.ocrNameMatch()).isTrue();
        assertThat(FaceEncodings.parse(result.faceEncoding())).containsExactly(axis(3));
        JSONObject sent = new JSONObject(received.get());
        assertThat(sent.getString("high_res_id_image")).isEqualTo(Base64.getEncoder().encodeToString(ID_CARD));
        assertThat(sent.getString("liveness_image")).isEqualTo(Base64.getEncoder().encodeToString(SELFIE));
    }

    @Test
    void aLiveFaceThatDoesNotMatchTheIdGoesToStaffNotRejectedAndKeepsTheVector() throws Exception {
        pythonMatches(false, axis(3)); // an older AI service: no "status" field, same routing

        KycService.KycResult result = screen();

        // Never an automatic rejection: a soft ID portrait can make a genuine visitor score low
        assertThat(result.rejected()).isFalse();
        assertThat(result.refused()).isFalse();
        assertThat(result.status()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(result.reason()).isEqualTo("FACE_MISMATCH");
        assertThat(result.message()).isNull(); // the visitor sees the normal pending page
        assertThat(result.ocrNameMatch()).isFalse(); // "Identity Unverified" for staff
        assertThat(result.faceEncoding()).isEqualTo(FaceEncodings.format(axis(3))); // kept so the Director can still ban the face
        verify(ocr, never()).extractText(any(BufferedImage.class));
    }

    // --- The AI service's "status" field (kyc.py quality routing) ---

    private void pythonSays(String status, String reason, Boolean match, double[] liveFace) throws Exception {
        reply.set(new JSONObject().put("status", status).put("reason", reason).put("id_detected", true)
                .put("id_crop_b64", pngBase64()).put("is_face_match", match == null ? JSONObject.NULL : match)
                .put("face_similarity", match == null ? JSONObject.NULL : (match ? 0.71 : 0.12))
                .put("face_match_error", "POOR_QUALITY".equals(status) ? "Face detection unclear - routing to manual review." : JSONObject.NULL)
                .put("live_sharpness", 210.5).put("id_sharpness", 95.0)
                .put("live_face_encoding", liveFace == null ? JSONObject.NULL : new JSONArray(liveFace)).toString());
    }

    @Test
    void poorQualityGoesToManualReviewWithTheVectorAndNoOcr() throws Exception {
        for (String reason : new String[] {"BLURRY_LIVE_FACE", "BLURRY_ID_FACE", "BORDERLINE_MATCH", "NO_ID_FACE"}) {
            pythonSays("POOR_QUALITY", reason, false, axis(4));
            KycService.KycResult result = screen();
            assertThat(result.status()).as(reason).isEqualTo(KycService.MANUAL_REVIEW);
            assertThat(result.reason()).as(reason).isEqualTo(reason);
            assertThat(result.faceEncoding()).as(reason).isEqualTo(FaceEncodings.format(axis(4)));
            assertThat(result.rejected() || result.refused()).as(reason).isFalse();
        }
        verify(ocr, never()).extractText(any(BufferedImage.class));
    }

    @Test
    void aSystemErrorGoesToManualReview() throws Exception {
        pythonSays("ERROR", "FACE_MODELS_UNAVAILABLE", null, null);
        assertThat(screen().status()).isEqualTo(KycService.MANUAL_REVIEW);
    }

    @Test
    void aMismatchStatusGoesToStaffEvenIfTheOldFieldsLookFine() throws Exception {
        pythonSays("MISMATCH", "OK", true, axis(5)); // the status wins
        KycService.KycResult result = screen();
        assertThat(result.rejected()).isFalse();
        assertThat(result.status()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(result.reason()).isEqualTo("FACE_MISMATCH");
        assertThat(result.faceEncoding()).isEqualTo(FaceEncodings.format(axis(5)));
        verify(ocr, never()).extractText(any(BufferedImage.class));
    }

    @Test
    void successStillGoesThroughTheOcrNameCheckAndWaitsForStaff() throws Exception {
        pythonSays("SUCCESS", "OK", true, axis(6));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("REPUBLIC OF THE PHILIPPINES\nDELA CRUZ, JUAN");
        KycService.KycResult result = screen();
        assertThat(result.status()).isEqualTo(KycService.PENDING);
        assertThat(result.ocrNameMatch()).isTrue();
    }

    @Test
    void aBannedFaceIsRefusedBeforeAnyStatusIsLookedAt() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(FaceEncodings.format(axis(7)));
        when(bans.findAll()).thenReturn(List.of(ban));
        for (String status : new String[] {"MISMATCH", "POOR_QUALITY", "SUCCESS"}) {
            pythonSays(status, "OK", "SUCCESS".equals(status), axis(7));
            KycService.KycResult result = screen();
            assertThat(result.refused()).as(status).isTrue();
            assertThat(result.message()).as(status).isEqualTo(KycService.FACE_BANNED);
        }
    }

    // --- Graceful degradation (human-in-the-loop): an unclear face goes to staff, never back to the visitor ---

    @Test
    void anUnclearFaceGoesToManualReviewAndKeepsTheLiveVector() throws Exception {
        for (String reason : List.of("NO_ID_FACE", "NO_ID_CARD", "UNSURE_ID_FACE")) {
            pythonSaysFaceUnclear(reason, axis(5));
            KycService.KycResult result = screen();
            assertThat(result.refused()).isFalse();
            assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
            assertThat(result.reason()).isEqualTo(reason);
            assertThat(result.message()).isNull();
            assertThat(FaceEncodings.parse(result.faceEncoding())).containsExactly(axis(5)); // so it can still be banned
            assertThat(result.ocrNameMatch()).isFalse();
        }
        verify(ocr, never()).extractText(any(BufferedImage.class));
    }

    @Test
    void noLiveFaceAtAllAlsoGoesToManualReview() throws Exception {
        pythonSaysFaceUnclear("NO_LIVE_FACE", null);
        KycService.KycResult result = screen();
        assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(result.faceEncoding()).isNull();
    }

    @Test
    void aBannedFaceCannotHideBehindAnUnclearIdPortrait() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(FaceEncodings.format(axis(5)));
        when(bans.findAll()).thenReturn(List.of(ban));
        pythonSaysFaceUnclear("NO_ID_FACE", axis(5)); // glare over the ID photo, but the live face is on the blacklist

        KycService.KycResult result = screen();

        assertThat(result.refused()).isTrue();
        assertThat(result.reason()).isEqualTo("FACE_BANNED");
        assertThat(result.message()).isEqualTo("Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance.");
    }

    // --- Biometric blacklist (Sybil check) ---

    @Test
    void aBannedFaceIsRefusedWhateverNameItBooksUnder() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(FaceEncodings.format(axis(3)));
        when(bans.findAll()).thenReturn(List.of(ban));
        double[] sameFaceSlightlyOff = axis(3);
        sameFaceSlightlyOff[4] = 0.8; // cosine 0.78 with the banned face
        pythonMatches(true, sameFaceSlightlyOff);

        KycService.KycResult result = screen();

        assertThat(result.refused()).isTrue();
        assertThat(result.reason()).isEqualTo("FACE_BANNED");
        assertThat(result.message()).isEqualTo("Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance.");
        verify(ocr, never()).extractText(any(BufferedImage.class));
    }

    @Test
    void aDifferentFaceIsNotCaughtBySomeoneElsesBan() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(FaceEncodings.format(axis(9)));
        when(bans.findAll()).thenReturn(List.of(ban));
        pythonMatches(true, axis(3));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("DELA CRUZ, JUAN");

        assertThat(screen().status()).isEqualTo("PENDING");
    }

    @Test
    void afterATemporaryBanEndsTheSameFaceCanBookAgain() throws Exception {
        BlacklistedFace ended = new BlacklistedFace();
        ended.setFaceEncoding(FaceEncodings.format(axis(3)));
        ended.setExpiresAt(java.time.LocalDate.now().minusDays(1)); // a 30-day suspension that ended yesterday
        when(bans.findAll()).thenReturn(List.of(ended));
        pythonMatches(true, axis(3));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("DELA CRUZ, JUAN");

        KycService.KycResult result = screen();

        assertThat(result.refused()).isFalse();
        assertThat(result.status()).isEqualTo("PENDING");
    }

    @Test
    void aTemporaryBanStillRunningRefusesTheBooking() throws Exception {
        BlacklistedFace running = new BlacklistedFace();
        running.setFaceEncoding(FaceEncodings.format(axis(3)));
        running.setExpiresAt(java.time.LocalDate.now().plusDays(20));
        when(bans.findAll()).thenReturn(List.of(running));
        pythonMatches(true, axis(3));

        assertThat(screen().reason()).isEqualTo("FACE_BANNED");
    }

    @Test
    void withoutTheFaceBanRepositoryNothingIsBanned() throws Exception {
        kyc.faceBans = null;
        pythonMatches(true, axis(3));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("DELA CRUZ, JUAN");
        assertThat(screen().status()).isEqualTo("PENDING");
    }

    @Test
    void aMatchWithoutAUsableVectorGoesToManualReview() throws Exception {
        reply.set(new JSONObject().put("reason", "OK").put("id_detected", true).put("id_crop_b64", pngBase64())
                .put("is_face_match", true).put("live_face_encoding", new JSONArray(new double[] {1, 2, 3})).toString());
        KycService.KycResult result = screen();
        assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(result.reason()).isEqualTo("NO_FACE_ENCODING");
    }

    // --- OCR name rule on the ID close-up (unchanged rule) ---

    @Test
    void anOcrSlipInTheNameStillCountsAsVerified() throws Exception {
        pythonMatches(true, axis(1));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("REPUBLIC OF THE PHILIPPINES\nSANT0S, N1CHOLAS R.\n");

        KycService.KycResult result = kyc.screen(ID_CARD, SELFIE, "Nicholas", "Santos");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.ocrNameMatch()).isTrue();
        verify(ocr, times(1)).extractText(any(BufferedImage.class)); // matched on the first read, no mirrored retry
    }

    @Test
    void nameMissingFromTheCardGoesToManualReviewButKeepsTheVectorForABan() throws Exception {
        pythonMatches(true, axis(1));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("SANTOS, MARIA");

        KycService.KycResult result = screen();

        assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(result.reason()).isEqualTo("NAME_MISMATCH");
        assertThat(result.faceEncoding()).isNotNull();
        verify(ocr, times(2)).extractText(any(BufferedImage.class)); // as taken, then un-mirrored
    }

    @Test
    void aMirroredIdPhotoIsReadOnTheSecondTry() throws Exception {
        pythonMatches(true, axis(1));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("Z∀ИAƎЯ", "DELA CRUZ, JUAN");
        assertThat(screen().reason()).isEqualTo("OK_MIRRORED");
    }

    @Test
    void theOcrNameMatchFlagIsSetOnlyWhenTesseractReadTheName() throws Exception {
        pythonMatches(true, axis(1));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("DELA CRUZ, JUAN");
        assertThat(screen().ocrNameMatch()).isTrue();

        when(ocr.extractText(any(BufferedImage.class))).thenReturn("Z∀ИAƎЯ", "DELA CRUZ, JUAN"); // mirrored, then read
        assertThat(screen().ocrNameMatch()).isTrue();

        when(ocr.extractText(any(BufferedImage.class))).thenReturn("SANTOS, MARIA");
        assertThat(screen().ocrNameMatch()).isFalse();

        python.stop(0); // AI offline: no OCR at all
        assertThat(screen().ocrNameMatch()).isFalse();
    }

    @Test
    void nothingIsEverApprovedAutomatically() throws Exception {
        pythonMatches(true, axis(1));
        for (String idText : List.of("DELA CRUZ, JUAN", "SANTOS, MARIA")) {
            when(ocr.extractText(any(BufferedImage.class))).thenReturn(idText);
            assertThat(screen().status()).isNotEqualTo("APPROVED").isIn("PENDING", "PENDING_MANUAL_REVIEW");
        }
    }

    @Test
    void tesseractMissingOnThisMachineGoesToManualReview() throws Exception {
        pythonMatches(true, axis(1));
        when(ocr.extractText(any(BufferedImage.class))).thenThrow(new UnsatisfiedLinkError("libtesseract not found"));
        assertThat(screen().status()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    // --- System problems: manual review, never a refusal ---

    @Test
    void aiSideProblemsGoToManualReview() throws Exception {
        for (String reason : List.of("OPENCV_UNAVAILABLE", "FACE_MODELS_UNAVAILABLE", "BAD_IMAGE", "INTERNAL_ERROR")) {
            pythonHasASystemProblem(reason);
            KycService.KycResult result = screen();
            assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
            assertThat(result.reason()).isEqualTo(reason);
        }
    }

    @Test
    void aiServiceOfflineGoesToManualReview() {
        python.stop(0);
        KycService.KycResult result = screen();
        assertThat(result.status()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(result.reason()).isEqualTo("AI_SERVICE_UNAVAILABLE");
    }

    @Test
    void aiServiceErrorOrGarbageGoesToManualReview() throws Exception {
        replyStatus = 500;
        reply.set("Internal Server Error");
        assertThat(screen().reason()).isEqualTo("AI_SERVICE_UNAVAILABLE");

        replyStatus = 200;
        reply.set("<html>not json</html>");
        assertThat(screen().status()).isEqualTo("PENDING_MANUAL_REVIEW");

        reply.set(new JSONObject().put("reason", "OK").put("is_face_match", true).put("id_detected", true)
                .put("id_crop_b64", "!!!").put("live_face_encoding", new JSONArray(axis(1))).toString());
        assertThat(screen().status()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    @Test
    void aSlowAiServiceGoesToManualReviewInsteadOfHanging() {
        reply.set("{}");
        replyDelayMs = 3_000;
        kyc.timeout = Duration.ofMillis(300);
        long start = System.nanoTime();
        assertThat(screen().reason()).isEqualTo("AI_SERVICE_UNAVAILABLE");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void productionTimeoutIsTwentySeconds() {
        assertThat(KycService.TIMEOUT).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theNameRuleToleratesOcrSlipsButNotADifferentName() {
        assertThat(KycService.namesMatch("DELA CRUZ, JUAN P.", "Juan", "Dela Cruz")).isTrue();
        assertThat(KycService.namesMatch("DELA CRUZ, JUAM P.", "Juan", "Dela Cruz")).isTrue();     // one misread letter
        assertThat(KycService.namesMatch("JUANITO DELA CRUZ", "Juan", "Dela Cruz")).isFalse();      // 3 edits away
        assertThat(KycService.namesMatch("DELA CRUZ, JUAN", "", "Dela Cruz")).isFalse();
        assertThat(KycService.namesMatch(null, "Juan", "Dela Cruz")).isFalse();
    }
}
