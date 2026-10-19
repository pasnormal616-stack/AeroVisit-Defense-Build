package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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
import com.paf.aerovisit.service.FaceEncodings;
import com.paf.aerovisit.service.KycService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * One person, one booking per date: the same face booking the same date under another email. A near-certain match
 * is refused, a borderline one goes to the Director tagged "Possible duplicate". No database, no Python.
 */
@WebMvcTest({VisitorPortalController.class, OtpController.class})
@Import(SecurityConfig.class)
class DuplicateFaceBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "second.email@example.com";
    private static final byte[] SELFIE = {(byte) 0xFF, (byte) 0xD8, 9};
    private static final byte[] ID_CARD = {(byte) 0xFF, (byte) 0xD8, 4, 4};

    // A face, and faces at a chosen similarity to it: cos * e1 + sin * e2 (both unit length)
    private static double[] faceAt(double cosine) {
        double[] v = new double[FaceEncodings.DIMENSIONS];
        v[0] = cosine;
        v[1] = Math.sqrt(1 - cosine * cosine);
        return v;
    }
    private static final double[] NEW_FACE = faceAt(1.0);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private KycService kycService;

    private final List<Path> createdFiles = new ArrayList<>();

    private static Visitor booked(String reference, String status, double[] face) {
        Visitor v = new Visitor();
        v.setReferenceId(reference);
        v.setStatus(status);
        v.setVisitDate(DAY);
        v.setFaceEncoding(face == null ? null : FaceEncodings.format(face));
        return v;
    }

    @BeforeEach
    void openSlotAndPassKyc() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString()))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, FaceEncodings.format(NEW_FACE)));
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

    private MockMultipartHttpServletRequestBuilder booking() {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", "selfie-with-id.jpg", "image/jpeg", SELFIE));
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_CARD));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", "1");
        return req;
    }

    private Visitor firstSaved() {
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    // --- /save end to end ---

    @Test
    void theSameFaceUnderAnotherEmailIsRefusedAndNothingIsSaved() throws Exception {
        when(repo.findByVisitDate(DAY)).thenReturn(new ArrayList<>(List.of(booked("PAF-FIRST1", "APPROVED", faceAt(0.9)))));
        try (var before = Files.list(Files.createDirectories(Paths.get("uploads")))) {
            long filesBefore = before.count();
            mvc.perform(booking()).andExpect(redirectedUrl("/"))
               .andExpect(flash().attribute("error", VisitorPortalController.DUPLICATE_FACE_MESSAGE));
            try (var after = Files.list(Paths.get("uploads"))) {
                assertThat(after.count()).isEqualTo(filesBefore); // both photos deleted again
            }
        }
        verify(repo, never()).save(any());
        verify(otpService, never()).consume(anyString()); // the email code stays valid
    }

    @Test
    void aLookAlikeIsSavedForTheDirectorTaggedWithTheOtherBooking() throws Exception {
        when(repo.findByVisitDate(DAY)).thenReturn(new ArrayList<>(List.of(booked("PAF-FIRST1", "PENDING", faceAt(0.45)))));

        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getStatus()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(saved.getPossibleDuplicateOf()).isEqualTo("PAF-FIRST1");
        assertThat(saved.isOcrNameMatch()).isTrue(); // the ID check itself still passed
    }

    @Test
    void aDifferentPersonOnTheSameDateBooksNormally() throws Exception {
        when(repo.findByVisitDate(DAY)).thenReturn(new ArrayList<>(List.of(booked("PAF-FIRST1", "APPROVED", faceAt(0.2)))));

        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));

        Visitor saved = firstSaved();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPossibleDuplicateOf()).isNull();
    }

    @Test
    void aCancelledOrRejectedBookingDoesNotHoldTheDateForItsFace() throws Exception {
        when(repo.findByVisitDate(DAY)).thenReturn(new ArrayList<>(List.of(
                booked("PAF-CANCEL", "CANCELLED_BY_VISITOR", faceAt(1.0)),
                booked("PAF-REJECT", "REJECTED", faceAt(1.0)))));

        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));

        assertThat(firstSaved().getPossibleDuplicateOf()).isNull();
    }

    @Test
    void thePossibleDuplicateTagCannotBeSetFromTheForm() throws Exception {
        when(repo.findByVisitDate(DAY)).thenReturn(new ArrayList<>());

        mvc.perform(booking().param("possibleDuplicateOf", "PAF-FAKE00")).andExpect(redirectedUrl("/success-page"));

        assertThat(firstSaved().getPossibleDuplicateOf()).isNull();
    }

    // --- the rule itself ---

    @Test
    void nearCertainMatchesRefuseAndBorderlineOnesOnlyFlag() {
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of(booked("PAF-A", "APPROVED", faceAt(0.56)))).refuse()).isTrue();
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of(booked("PAF-A", "APPROVED", faceAt(0.54)))).refuse()).isFalse();
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of(booked("PAF-A", "APPROVED", faceAt(0.37))))).isNotNull();
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of(booked("PAF-A", "APPROVED", faceAt(0.36))))).isNull();
    }

    @Test
    void theClosestActiveBookingIsTheOneReported() {
        VisitorPortalController.DuplicateFace match = VisitorPortalController.duplicateFace(NEW_FACE, List.of(
                booked("PAF-LOOK", "PENDING", faceAt(0.4)),
                booked("PAF-SAME", "CHECKED-IN", faceAt(0.95)),
                booked("PAF-GONE", "CANCELLED", faceAt(1.0))));
        assertThat(match.referenceId()).isEqualTo("PAF-SAME");
        assertThat(match.refuse()).isTrue();
    }

    @Test
    void missingFacesSkipTheCheck() {
        assertThat(VisitorPortalController.duplicateFace(null, List.of(booked("PAF-A", "APPROVED", faceAt(1.0))))).isNull();
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of(booked("PAF-A", "APPROVED", null)))).isNull();
        assertThat(VisitorPortalController.duplicateFace(NEW_FACE, List.of())).isNull();
    }
}
