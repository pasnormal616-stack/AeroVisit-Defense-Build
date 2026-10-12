package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Same-date re-application after a REJECTED booking: blocked for 4 hours after it was submitted, allowed afterwards. No database. */
@WebMvcTest({VisitorPortalController.class, OtpController.class})
@Import(SecurityConfig.class)
class RejectedCooldownTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    private static final String DUPLICATE = "Registration Denied: Duplicate booking detected for this date.";
    private static final String COOLDOWN = "Please wait 4 hours before reapplying for the same date.";
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
    void openSlotAndVerifyEmail() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(eq(ID_CARD), eq(SELFIE), eq("Juan"), eq("Dela Cruz")))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, FACE));
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

    private static Visitor earlier(long id, String status, LocalDateTime createdAt) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setEmail(EMAIL);
        v.setVisitDate(DAY);
        v.setStatus(status);
        v.setCreatedAt(createdAt);
        return v;
    }

    private void existing(Visitor... bookings) {
        when(repo.findByEmailAndVisitDate(EMAIL, DAY)).thenAnswer(inv -> new ArrayList<>(List.of(bookings)));
    }

    private void assertRefusedWith(String message) throws Exception {
        mvc.perform(booking()).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", message));
        verify(repo, never()).save(any());
        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
        verify(otpService, never()).consume(anyString()); // the email code stays valid for the retry
    }

    // --- The rule on its own (fixed clock) ---

    @Test
    void cooldownIsFourHoursFromTheNewestRejectedApplication() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 11, 14, 0);
        assertThat(VisitorPortalController.REJECTED_COOLDOWN_HOURS).isEqualTo(4);

        assertThat(VisitorPortalController.inRejectedCooldown(List.of(earlier(1, "REJECTED", now.minusMinutes(239))), now)).isTrue();
        assertThat(VisitorPortalController.inRejectedCooldown(List.of(earlier(1, "REJECTED", now.minusHours(4))), now)).isFalse();
        assertThat(VisitorPortalController.inRejectedCooldown(List.of(earlier(1, "REJECTED", now.minusHours(9))), now)).isFalse();
        // two rejections: the newer one decides
        assertThat(VisitorPortalController.inRejectedCooldown(List.of(
            earlier(1, "REJECTED", now.minusDays(2)), earlier(2, "REJECTED", now.minusHours(1))), now)).isTrue();
        // legacy rows from before createdAt existed, and no rejection at all
        assertThat(VisitorPortalController.inRejectedCooldown(List.of(earlier(1, "REJECTED", null)), now)).isFalse();
        assertThat(VisitorPortalController.inRejectedCooldown(List.of(), now)).isFalse();
    }

    // --- /save end to end ---

    @Test
    void reapplyingLessThanFourHoursAfterARejectionIsRefused() throws Exception {
        existing(earlier(1, "REJECTED", LocalDateTime.now().minusHours(3).minusMinutes(59)));
        assertRefusedWith(COOLDOWN);
    }

    @Test
    void reapplyingMoreThanFourHoursAfterARejectionGoesThrough() throws Exception {
        existing(earlier(1, "REJECTED", LocalDateTime.now().minusHours(4).minusMinutes(1)));

        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));

        verify(repo).save(any(Visitor.class));
        verify(otpService).consume(EMAIL);
    }

    @Test
    void aLegacyRejectionWithNoTimestampDoesNotBlock() throws Exception {
        existing(earlier(1, "REJECTED", null));
        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));
    }

    @Test
    void anActiveBookingIsStillADuplicateWhateverTheRejectionsSay() throws Exception {
        for (String active : new String[] {"PENDING", "APPROVED", "VERIFIED", "PENDING_MANUAL_REVIEW",
                                           "CHECKED-IN", "CHECKED-OUT", "NO_SHOW", "DENIED_ENTRY"}) {
            org.mockito.Mockito.clearInvocations(repo, kycService, otpService);
            // an old rejection next to it must not open the date, and the duplicate message wins over the cooldown one
            existing(earlier(1, "REJECTED", LocalDateTime.now().minusHours(1)), earlier(2, active, LocalDateTime.now().minusDays(1)));
            mvc.perform(booking()).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", DUPLICATE));
            verify(repo, never()).save(any());
        }
    }

    @Test
    void cancelledBookingsStillNeverBlock() throws Exception {
        existing(earlier(1, "CANCELLED_BY_VISITOR", LocalDateTime.now().minusMinutes(5)),
                 earlier(2, "CANCELLED_OVERRIDE", LocalDateTime.now().minusMinutes(5)),
                 earlier(3, "CANCELLED", LocalDateTime.now().minusMinutes(5)));
        mvc.perform(booking()).andExpect(redirectedUrl("/success-page"));
    }
}
