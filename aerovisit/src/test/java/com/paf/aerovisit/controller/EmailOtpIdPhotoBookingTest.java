package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * /save end to end in a web slice: the email code (OTP), then one photo of the valid ID (taken or uploaded; no selfie),
 * checked for its real type and stored for staff to look at. No face match, no OCR, no call to Python: the Director
 * compares the photo. No database.
 */
@WebMvcTest({VisitorPortalController.class, OtpController.class})
@Import(SecurityConfig.class)
class EmailOtpIdPhotoBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    private static final String OTP_ERROR = "Security Error: Please verify your email address with the 6-digit code we sent before submitting.";
    // A real JPEG: /save checks the photo's bytes (signature + decode), not only its declared type
    private static final byte[] ID_CARD = com.paf.aerovisit.TestImages.jpeg(44);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;

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
            if (v.getIdFaceCropUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getIdFaceCropUrl()));
            if (v.getQrCodeUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getQrCodeUrl()));
            return v;
        });
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        for (Path p : createdFiles) Files.deleteIfExists(p);
    }

    private MockMultipartHttpServletRequestBuilder booking() {
        return booking(1, WINDOW);
    }

    private MockMultipartHttpServletRequestBuilder booking(int groupSize, String window) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_CARD));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", window).param("freeCount", String.valueOf(groupSize)).param("termsAccepted", "true");
        return req;
    }

    // What a stale or hand-made form could still send from the removed Master Booking / guest list feature
    private static MockMultipartHttpServletRequestBuilder withRemovedGroupFields(MockMultipartHttpServletRequestBuilder req) {
        req.file(new MockMultipartFile("companionCsv", "guests.csv", "text/csv", "Name\nSantos, Maria\n".getBytes()));
        req.param("isMasterBooking", "true")
           .param("purpose", "Educational Tour")
           .param("eventJustification", "School field trip")
           .param("companionNames", "Santos, Maria\nReyes, Pedro")
           .param("companionManual", "Lim, Ana", "Cruz, Jose");
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

        mvc.perform(booking()).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", OTP_ERROR));

        verify(repo, never()).save(any());
    }

    @Test
    void verifiedEmailAndAnIdPhotoWaitForStaffAndTheCodeIsUsedUp() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);

        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        // Human-in-the-loop: a booking that passed every check still waits for staff; no QR code until they approve
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getQrCodeUrl()).isNull();
        verify(emailService).sendPendingNotification(eq(EMAIL), any(), any(), any());
        verify(emailService, never()).sendApprovalEmailWithQR(any(), any(), any(), any(), any(), any());
        // 🪪 No KYC: no face vector, no face crop, no name check; only the ID photo, under a random name
        assertThat(saved.getFaceEncoding()).isNull();
        assertThat(saved.getIdFaceCropUrl()).isNull();
        assertThat(saved.isOcrNameMatch()).isFalse();
        assertThat(saved.getPossibleDuplicateOf()).isNull();
        assertThat(saved.getIdImageUrl()).isNull();
        assertThat(saved.getIdCardImageUrl()).doesNotContain("id-card");
        assertThat(Files.readAllBytes(Paths.get("uploads").resolve(saved.getIdCardImageUrl()))).isEqualTo(ID_CARD);
        assertThat(saved.getTermsVersion()).isEqualTo("2026-11d");
        verify(otpService).consume(EMAIL);
    }

    // 📷 Taken or uploaded, JPG / PNG / WEBP all work (the page normally sends a JPEG)
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"jpeg", "png", "webp"})
    void aJpegPngOrWebpIdPhotoIsSaved(String format) throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        byte[] photo = switch (format) {
            case "png" -> com.paf.aerovisit.TestImages.png(30);
            case "webp" -> java.util.Base64.getDecoder().decode("UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAAwA0JaQAA3AA/vuUAAA=");
            default -> ID_CARD;
        };
        MockMultipartHttpServletRequestBuilder req = bookingWith(new MockMultipartFile("highResIdImage", "id." + format, "image/" + format, photo));
        mvc.perform(req).andExpect(redirectedUrl("/success-page"));
        assertThat(firstSaved().getIdCardImageUrl()).isNotNull();
    }

    private MockMultipartHttpServletRequestBuilder bookingWith(MockMultipartFile idPhoto) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        if (idPhoto != null) req.file(idPhoto);
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("freeCount", "1").param("termsAccepted", "true");
        return req;
    }

    // --- The ID photo: required, a real JPG / PNG / WEBP, at most 15 MB ---

    @Test
    void aMissingIdPhotoIsRefused() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        mvc.perform(bookingWith(null)).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Please take or upload a photo of your valid ID."));
        mvc.perform(bookingWith(new MockMultipartFile("highResIdImage", new byte[0]))).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Please take or upload a photo of your valid ID."));
        verify(repo, never()).save(any());
    }

    @Test
    void aHeicPhotoIsRefusedWithItsOwnMessage() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        byte[] heic = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1'};
        mvc.perform(bookingWith(new MockMultipartFile("highResIdImage", "IMG_0042.jpg", "image/jpeg", heic)))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.ID_PHOTO_HEIC));
        verify(repo, never()).save(any());
    }

    @Test
    void aFileOver15MbIsRefused() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        byte[] big = java.util.Arrays.copyOf(ID_CARD, (int) VisitorPortalController.ID_PHOTO_MAX_BYTES + 1);
        mvc.perform(bookingWith(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", big)))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.ID_PHOTO_TOO_LARGE));
        verify(repo, never()).save(any());
    }

    // 🔒 The photo's bytes are checked (signature + decode), not the name or the type the browser declares
    @Test
    void aRenamedNonImageOrAGifIsRefusedEvenWithAJpegNameAndType() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        byte[] fake = "MZ\u0090\u0000 this is really a program, not a photo".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] truncated = java.util.Arrays.copyOf(ID_CARD, 4); // a JPEG signature with nothing behind it
        for (MockMultipartFile bad : List.of(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", fake),
                new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", truncated),
                new MockMultipartFile("highResIdImage", "id.gif", "image/gif", "GIF89a\u0001\u0000\u0001\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)))) {
            mvc.perform(bookingWith(bad)).andExpect(redirectedUrl("/"))
               .andExpect(flash().attribute("error", VisitorPortalController.ID_PHOTO_NOT_IMAGE));
        }
        verify(repo, never()).save(any());
    }

    // --- No identity check: the Director looks at the photo ---

    @Test
    void theFaceVectorCannotBeSetFromTheForm() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);

        mvc.perform(booking().param("faceEncoding", "0.1,0.2").param("idCardImageUrl", "../secret.jpg").param("ocrNameMatch", "true")
                .param("idFaceCropUrl", "../x.png").param("idImageUrl", "selfie.jpg"))
           .andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getFaceEncoding()).isNull();
        assertThat(saved.getIdCardImageUrl()).doesNotContain("..");
        assertThat(saved.getIdFaceCropUrl()).isNull();
        assertThat(saved.getIdImageUrl()).isNull();
        assertThat(saved.isOcrNameMatch()).isFalse(); // a visitor can't mark their own name as verified
    }

    // --- Group bookings: the representative gives only the group size ---

    @Test
    void aGroupOfFiveBooksWithNoCompanionNames() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);

        mvc.perform(booking(5, WINDOW)).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getGroupSize()).isEqualTo(5);
        assertThat(saved.getCompanionNames()).isNull();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void aRequestThatStillSendsMasterBookingOrGuestListFieldsBecomesANormalBooking() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);

        mvc.perform(withRemovedGroupFields(booking(3, WINDOW))).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getGroupSize()).isEqualTo(3);
        assertThat(saved.getPurpose()).isEqualTo("Educational Tour");
        assertThat(saved.getTimeWindow()).isEqualTo(WINDOW);
        assertThat(saved.getCompanionNames()).isNull();
        assertThat(saved.getEventJustification()).isNull();
        verify(emailService).sendPendingNotification(eq(EMAIL), any(), any(), any());
    }

    @Test
    void aStaleMasterBookingRequestStillGetsTheNormalCapacityAndSlotChecks() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true);

        // Larger than the slot's 50 seats: no Master Booking bypass any more
        mvc.perform(withRemovedGroupFields(booking(60, WINDOW))).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Insufficient slots available for this specific shift! Please choose another slot."));
        // The old "Master Booking Bypass" time window is not a real slot
        mvc.perform(withRemovedGroupFields(booking(60, "Master Booking Bypass"))).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Invalid or unavailable time slot. Please choose an open date and time from the calendar."));

        verify(repo, never()).save(any());
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
