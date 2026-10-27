package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.KycService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * 🪪 Identity bans on /save (IdentityBans): with AI features off there is no face to compare, so the same email or phone
 * number as an active ban is refused, and a similar lead or companion name is only flagged for staff. No database, no Python.
 */
@WebMvcTest({VisitorPortalController.class, OtpController.class})
@Import(SecurityConfig.class)
class IdentityBanBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    private static final byte[] SELFIE = {(byte) 0xFF, (byte) 0xD8, 9};
    private static final byte[] ID_CARD = {(byte) 0xFF, (byte) 0xD8, 4, 4};
    // What KycService answers when AI features are switched off in Settings: manual review, no face data
    private static final KycService.KycResult AI_OFF = new KycService.KycResult(KycService.MANUAL_REVIEW, "AI_OFF", null, null);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private KycService kycService;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private final List<Path> createdFiles = new ArrayList<>();

    @BeforeEach
    void openSlotWithAiOff() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(otpService.isVerified(anyString())).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString())).thenReturn(AI_OFF);
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getIdImageUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getIdImageUrl()));
            if (v.getIdCardImageUrl() != null) createdFiles.add(Paths.get("uploads").resolve(v.getIdCardImageUrl()));
            return v;
        });
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        for (Path p : createdFiles) Files.deleteIfExists(p);
    }

    private MockMultipartHttpServletRequestBuilder booking(String fullName, String email, String phone) {
        return booking(fullName, email, phone, 1);
    }

    private MockMultipartHttpServletRequestBuilder booking(String fullName, String email, String phone, int groupSize) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("livenessImage", "selfie-with-id.jpg", "image/jpeg", SELFIE));
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_CARD));
        req.param("fullName", fullName).param("email", email).param("contactNumber", phone)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", String.valueOf(groupSize));
        return req;
    }

    private static BlacklistedFace ban(long id, String name, String email, String phone) {
        BlacklistedFace b = new BlacklistedFace();
        b.setId(id);
        b.setName(name);
        b.setEmail(email);
        b.setPhone(phone);
        b.setReason("Fake ID");
        return b;
    }

    private Visitor saved() {
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(repo, atLeastOnce()).save(saved.capture());
        return saved.getAllValues().get(0);
    }

    // --- Same email or phone number: refused, nothing saved, no AI call ---

    @Test
    void theBannedEmailIsRefusedWithTheNeutralMessage() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12, "Juan Dela Cruz", EMAIL, null)));

        mvc.perform(booking("Totally Different", "  JUAN@example.com", "09998887777"))
           .andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", KycService.FACE_BANNED));

        verify(repo, never()).save(any());
        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
        verify(otpService, never()).consume(anyString());
    }

    @Test
    void theBannedPhoneNumberInAnotherFormatIsRefused() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12, "Juan Dela Cruz", "old@example.com", "09171234567")));

        mvc.perform(booking("Totally Different", "new@example.com", "+63 917 123 4567"))
           .andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", KycService.FACE_BANNED));

        verify(repo, never()).save(any());
    }

    @Test
    void anExpiredBanNoLongerRefusesTheEmail() throws Exception {
        BlacklistedFace ended = ban(12, "Someone Else", EMAIL, null);
        ended.setExpiresAt(LocalDate.now().minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(ended));

        mvc.perform(booking("Dela Cruz, Juan P.", EMAIL, "09171234567")).andExpect(redirectedUrl("/success-page"));

        assertThat(saved().getPossibleBanMatch()).isNull();
    }

    // --- Similar name: saved, but flagged for staff ---

    @Test
    void aSimilarNameIsSavedForManualReviewWithTheBanNamed() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12, "Juan Dela Cruz", "old@example.com", "09171234567")));

        mvc.perform(booking("Dela Cruz, Juan P.", "new@example.com", "09998887777")).andExpect(redirectedUrl("/success-page"));

        Visitor v = saved();
        assertThat(v.getStatus()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(v.getPossibleBanMatch()).isEqualTo("Ban #12: Juan Dela Cruz");
    }

    @Test
    void aBookingThatPassedEveryCheckIsStillFlaggedWhenTheNameMatches() throws Exception {
        when(kycService.screen(any(), any(), anyString(), anyString()))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, null));
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12, "Juan Dela Cruz", null, null)));

        mvc.perform(booking("Juan Dela Cruz", "new@example.com", "09998887777")).andExpect(redirectedUrl("/success-page"));

        assertThat(saved().getStatus()).isEqualTo(KycService.MANUAL_REVIEW);
    }

    @Test
    void aCompanionWithABannedNameFlagsTheGroup() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(15, "Pedro Santos", null, null)));

        mvc.perform(booking("Maria Reyes", "maria@example.com", "09998887777", 2).param("companionNames", "Santos, Pedro"))
           .andExpect(redirectedUrl("/success-page"));

        assertThat(saved().getPossibleBanMatch()).isEqualTo("Ban #15: Pedro Santos");
    }

    @Test
    void anUnrelatedNameIsNotFlagged() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12, "Juan Dela Cruz", "old@example.com", "09171234567")));

        mvc.perform(booking("Maria Reyes", "maria@example.com", "09998887777")).andExpect(redirectedUrl("/success-page"));

        assertThat(saved().getPossibleBanMatch()).isNull();
    }
}
