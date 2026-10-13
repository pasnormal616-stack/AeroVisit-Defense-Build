package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.KycService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Panel-defense upgrade: email OTP replaced reCAPTCHA on /save, and the selfie-with-ID check replaced the plain ID OCR.
 * /save end to end in a web slice (possible now that no live Google call sits in front of it). No database, no Python.
 */
@WebMvcTest({VisitorPortalController.class, OtpController.class})
@Import(SecurityConfig.class)
class EmailOtpSelfieBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    private static final String OTP_ERROR = "Security Error: Please verify your email address with the 6-digit code we sent before submitting.";
    private static final byte[] SELFIE = {(byte) 0xFF, (byte) 0xD8, 9};
    private static final byte[] ID_CARD = {(byte) 0xFF, (byte) 0xD8, 4, 4};
    private static final String FACE = com.paf.aerovisit.service.FaceEncodings.format(new double[128]);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private KycService kycService;

    private final List<Path> createdFiles = new ArrayList<>();

    @BeforeEach
    void openSlot() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getIdImageUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getIdImageUrl()));
            if (v.getIdCardImageUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getIdCardImageUrl()));
            if (v.getQrCodeUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getQrCodeUrl()));
            return v;
        });
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        for (Path p : createdFiles) Files.deleteIfExists(p);
    }

    private MockMultipartHttpServletRequestBuilder booking(boolean master) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", "selfie-with-id.jpg", "image/jpeg", SELFIE));
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_CARD));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", "1");
        if (master) req.param("isMasterBooking", "true").param("eventJustification", "School field trip").param("groupSize", "1");
        return req;
    }

    private Visitor firstSaved() {
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    // --- /save ---

    @Test
    void bookingWithoutAVerifiedEmailIsRefused() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(false);

        mvc.perform(booking(false)).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", OTP_ERROR));

        verify(repo, never()).save(any());
        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
    }

    @Test
    void verifiedEmailAndMatchingSelfieWaitForStaffWithTheBadgeAndTheCodeIsUsedUp() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(eq(ID_CARD), eq(SELFIE), eq("Juan"), eq("Dela Cruz")))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, FACE));

        mvc.perform(booking(false)).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        // Human-in-the-loop: a booking that passed every check still waits for staff; no QR code until they approve
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getQrCodeUrl()).isNull();
        verify(emailService).sendPendingNotification(eq(EMAIL), any(), any(), anyBoolean(), any());
        verify(emailService, never()).sendApprovalEmailWithQR(any(), any(), any(), any(), any());
        assertThat(saved.getFaceEncoding()).isEqualTo(FACE);
        assertThat(saved.isOcrNameMatch()).isTrue(); // admin badge: "ID Name Verified"
        // Selfie in idImageUrl (admin "View Document", gate scanner), ID close-up in its own field
        assertThat(Files.readAllBytes(Paths.get("uploads").resolve(saved.getIdImageUrl()))).isEqualTo(SELFIE);
        assertThat(Files.readAllBytes(Paths.get("uploads").resolve(saved.getIdCardImageUrl()))).isEqualTo(ID_CARD);
        verify(otpService).consume(EMAIL);
    }

    // --- Two-step KYC refusals: nothing is saved, both photos are deleted, the email code stays valid ---

    private void assertRefusedWith(String message) throws Exception {
        try (var before = Files.list(Files.createDirectories(Paths.get("uploads")))) {
            long filesBefore = before.count();
            mvc.perform(booking(false)).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", message));
            try (var after = Files.list(Paths.get("uploads"))) {
                assertThat(after.count()).isEqualTo(filesBefore);
            }
        }
        verify(repo, never()).save(any());
        verify(otpService, never()).consume(anyString());
    }

    @Test
    void aBannedFaceIsRefusedAndNothingIsSaved() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.REFUSED, "FACE_BANNED", KycService.FACE_BANNED, null));
        assertRefusedWith(KycService.FACE_BANNED);
    }

    @Test
    void aClearFaceMismatchIsStoredAsRejectedWithItsPhotosAndFaceButNoSeatOrPendingEmail() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.REJECTED, "FACE_MISMATCH", KycService.FACE_MISMATCH, FACE));

        mvc.perform(booking(false))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", KycService.FACE_MISMATCH));

        Visitor saved = firstSaved();
        assertThat(saved.getStatus()).isEqualTo("REJECTED");
        assertThat(saved.getRejectionReason()).isEqualTo("Automatic KYC: live face does not match the ID portrait");
        assertThat(saved.getFaceEncoding()).isEqualTo(FACE);   // the Director can ban this face
        assertThat(saved.getIdImageUrl()).isNotNull();          // both photos kept as evidence
        assertThat(saved.getIdCardImageUrl()).isNotNull();
        assertThat(saved.getReferenceId()).startsWith("PAF-");
        verify(otpService).consume(EMAIL);                      // one email code = one attempt
        verify(emailService, never()).sendPendingNotification(any(), any(), any(), anyBoolean(), any());
        verify(emailService, never()).sendPendingNotification(any(), any(), any(), anyBoolean());
    }

    @Test
    void anUnclearIdFaceIsSavedForManualReviewInsteadOfBlockingTheVisitor() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.MANUAL_REVIEW, "NO_ID_FACE", null, FACE));

        mvc.perform(booking(false)).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(saved.getFaceEncoding()).isEqualTo(FACE);   // staff can still biometric-ban this face
        assertThat(saved.getIdImageUrl()).isNotNull();          // both photos kept for the human check
        assertThat(saved.getIdCardImageUrl()).isNotNull();
        verify(otpService).consume(EMAIL);
    }

    @Test
    void aBannedFaceIsRefusedLikeABlacklistedEmail() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.REFUSED, "FACE_BANNED", KycService.FACE_BANNED, null));
        assertRefusedWith("Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance.");
    }

    @Test
    void onlyOnePhotoSkipsTheFaceCheckAndGoesToManualReview() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", "selfie.jpg", "image/jpeg", SELFIE));
        req.file(new MockMultipartFile("highResIdImage", new byte[0]));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", "1");

        mvc.perform(req).andExpect(redirectedUrl("/success-page"));

        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
        assertThat(firstSaved().getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    @Test
    void theIdPhotoMustAlsoBeAJpegOrPng() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", "selfie.jpg", "image/jpeg", SELFIE));
        req.file(new MockMultipartFile("highResIdImage", "id.gif", "image/gif", new byte[] {1, 2}));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", "1");

        mvc.perform(req).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Security Alert: System only accepts standard .JPG or .PNG formats for ID verification."));
        verify(repo, never()).save(any());
    }

    @Test
    void theFaceVectorCannotBeSetFromTheForm() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult("PENDING_MANUAL_REVIEW", "NAME_MISMATCH"));

        mvc.perform(booking(false).param("faceEncoding", FACE).param("idCardImageUrl", "../secret.jpg").param("ocrNameMatch", "true"))
           .andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getFaceEncoding()).isNull();
        assertThat(saved.getIdCardImageUrl()).doesNotContain("..");
        assertThat(saved.isOcrNameMatch()).isFalse(); // a visitor can't mark their own name as verified
    }

    @Test
    void anySelfieProblemSendsTheBookingToManualReview() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult("PENDING_MANUAL_REVIEW", "NO_FACE"));

        mvc.perform(booking(false)).andExpect(redirectedUrl("/success-page"));

        assertThat(firstSaved().getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
        verify(emailService).sendPendingNotification(eq(EMAIL), any(), any(), anyBoolean(), any());
    }

    // --- Zero-trust KYC: Master Bookings go through exactly the same checks as everyone else ---

    @Test
    void masterBookingsAreScreenedLikeAnyOtherBookingAndWaitForStaff() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(eq(ID_CARD), eq(SELFIE), eq("Juan"), eq("Dela Cruz")))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, FACE));

        mvc.perform(booking(true)).andExpect(redirectedUrl("/success-page"));

        verify(kycService).screen(eq(ID_CARD), eq(SELFIE), eq("Juan"), eq("Dela Cruz"));
        Visitor saved = firstSaved();
        assertThat(saved.isMasterBooking()).isTrue();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.isOcrNameMatch()).isTrue();
        assertThat(saved.getFaceEncoding()).isEqualTo(FACE);
    }

    @Test
    void anUnclearMasterBookingGoesToManualReview() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString()))
                .thenReturn(new KycService.KycResult(KycService.MANUAL_REVIEW, "NO_ID_FACE", null, FACE));

        mvc.perform(booking(true)).andExpect(redirectedUrl("/success-page"));

        assertThat(firstSaved().getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    @Test
    void aMasterBookingWithAMismatchedOrBannedFaceIsRefused() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.REFUSED, "FACE_BANNED", KycService.FACE_BANNED, null));

        mvc.perform(booking(true)).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance."));
        verify(repo, never()).save(any());
    }

    @Test
    void aBookingWithNoPhotosAtAllIsNeverTreatedAsPassed() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", new byte[0]));
        req.file(new MockMultipartFile("highResIdImage", new byte[0]));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL).param("visitDate", DAY.toString())
           .param("timeWindow", WINDOW).param("groupSize", "1")
           .param("isMasterBooking", "true").param("eventJustification", "School field trip");

        mvc.perform(req).andExpect(redirectedUrl("/success-page"));

        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
        assertThat(firstSaved().getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
    }

    @Test
    void aRefusedBookingDoesNotUseUpTheVerification() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(new KycService.KycResult(
                KycService.REFUSED, "FACE_BANNED", KycService.FACE_BANNED, null));

        mvc.perform(booking(false)).andExpect(redirectedUrl("/"));

        verify(otpService, never()).consume(anyString());
    }

    // --- /otp (public, POST only) ---

    @Test
    void anonymousVisitorsCanRequestACode() throws Exception {
        when(otpService.sendCode(eq(EMAIL), anyString())).thenReturn(new OtpService.SendResult(OtpService.SendOutcome.SENT, 60));

        mvc.perform(post("/otp/send").with(csrf()).param("email", EMAIL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.retryAfter").value(60));
    }

    @Test
    void tooManyRequestsIs429WithRetryAfter() throws Exception {
        when(otpService.sendCode(anyString(), anyString())).thenReturn(new OtpService.SendResult(OtpService.SendOutcome.COOLDOWN, 42));

        mvc.perform(post("/otp/send").with(csrf()).param("email", EMAIL))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "42"))
            .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void mailFailureIs503SoTheFormCanSayTryAgain() throws Exception {
        when(otpService.sendCode(anyString(), anyString())).thenReturn(new OtpService.SendResult(OtpService.SendOutcome.MAIL_FAILED, 0));
        mvc.perform(post("/otp/send").with(csrf()).param("email", EMAIL)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void verifyAnswersOkOrA400WithAReason() throws Exception {
        when(otpService.verify(EMAIL, "123456")).thenReturn(OtpService.VerifyOutcome.VERIFIED);
        when(otpService.verify(EMAIL, "000000")).thenReturn(OtpService.VerifyOutcome.EXPIRED);

        mvc.perform(post("/otp/verify").with(csrf()).param("email", EMAIL).param("code", "123456"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/otp/verify").with(csrf()).param("email", EMAIL).param("code", "000000"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("That code has expired. Please request a new one."));
    }

    @Test
    void behindNgrokTheProxyAddedHopIsTheClient() throws Exception {
        when(otpService.sendCode(anyString(), anyString())).thenReturn(new OtpService.SendResult(OtpService.SendOutcome.SENT, 60));

        mvc.perform(post("/otp/send").with(csrf()).param("email", EMAIL)
                .header("X-Forwarded-For", "6.6.6.6, 198.51.100.4")
                .with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }))
            .andExpect(status().isOk());

        verify(otpService).sendCode(EMAIL, "198.51.100.4");
    }

    @Test
    void codesCannotBeRequestedWithGet() throws Exception {
        mvc.perform(get("/otp/send").param("email", EMAIL)).andExpect(result ->
            assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
        verify(otpService, never()).sendCode(anyString(), anyString());
    }
}
