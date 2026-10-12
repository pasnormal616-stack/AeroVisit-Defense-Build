package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
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
 * One-time KYC nonce: the booking page puts a random value in the session and the form; /save accepts a submission
 * only when it echoes that value back, and burns it on the first attempt. No database, no Python.
 */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class KycNonceTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
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
    void openSlotAndPassEveryOtherCheck() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(kycService.screen(any(), any(), anyString(), anyString()))
                .thenReturn(new KycService.KycResult("PENDING", "OK", null, FACE));
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

    // A complete booking in this session, sending the given nonce (or none)
    private MockMultipartHttpServletRequestBuilder booking(MockHttpSession session, String nonce) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.file(new MockMultipartFile("livenessImage", "selfie-with-id.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 9}));
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 4}));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW).param("groupSize", "1");
        req.session(session);
        if (nonce != null) req.param(VisitorPortalController.KYC_NONCE, nonce);
        return req;
    }

    private static MockHttpSession sessionWithNonce(String nonce) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.KYC_NONCE, nonce);
        return session;
    }

    private void assertRefusedBeforeAnyCheck() {
        verify(otpService, never()).isVerified(anyString());
        verify(kycService, never()).screen(any(), any(), anyString(), anyString());
        verify(repo, never()).save(any());
    }

    @Test
    void theBookingPagePutsAFreshNonceInTheSessionAndTheFormAndItBooksOnce() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String html = mvc.perform(get("/").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Object nonce = session.getAttribute(VisitorPortalController.KYC_NONCE);
        assertThat(nonce).isInstanceOf(String.class);
        assertThat(html).contains("<input type=\"hidden\" name=\"kycNonce\" value=\"" + nonce + "\">");

        // A second page load replaces it: only the newest form can be submitted
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertThat(session.getAttribute(VisitorPortalController.KYC_NONCE)).isNotEqualTo(nonce);

        String current = (String) session.getAttribute(VisitorPortalController.KYC_NONCE);
        mvc.perform(booking(session, current)).andExpect(redirectedUrl("/success-page"));
        assertThat(session.getAttribute(VisitorPortalController.KYC_NONCE)).isNull(); // burned
    }

    @Test
    void aSubmissionWithoutTheNonceIsRefusedBeforeAnyOtherCheck() throws Exception {
        mvc.perform(booking(sessionWithNonce("issued-nonce"), null))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", VisitorPortalController.KYC_NONCE_MESSAGE));
        assertRefusedBeforeAnyCheck();
    }

    @Test
    void aScriptWithoutABookingPageSessionIsRefused() throws Exception {
        // Straight at the API: a made-up nonce and a session that never loaded the booking page
        mvc.perform(booking(new MockHttpSession(), "made-up-nonce"))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", VisitorPortalController.KYC_NONCE_MESSAGE));
        assertRefusedBeforeAnyCheck();
    }

    @Test
    void aWrongNonceIsRefusedAndBurnsTheRealOneSoItCannotBeGuessed() throws Exception {
        MockHttpSession session = sessionWithNonce("real-nonce");
        mvc.perform(booking(session, "guess")).andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", VisitorPortalController.KYC_NONCE_MESSAGE));
        mvc.perform(booking(session, "real-nonce")).andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", VisitorPortalController.KYC_NONCE_MESSAGE));
        assertRefusedBeforeAnyCheck();
    }

    @Test
    void aCapturedSubmissionCannotBeReplayed() throws Exception {
        MockHttpSession session = sessionWithNonce("one-time");
        mvc.perform(booking(session, "one-time")).andExpect(redirectedUrl("/success-page"));

        // The exact same request again (same session, same nonce) is refused
        mvc.perform(booking(session, "one-time")).andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", VisitorPortalController.KYC_NONCE_MESSAGE));
        verify(repo, org.mockito.Mockito.times(1)).save(any(Visitor.class));
    }

    // A session whose nonce read takes a moment, so two submits sent together both reach the check before either one
    // could remove the nonce. Without the lock in consumeKycNonce both would pass and book twice.
    private static class SlowNonceSession extends MockHttpSession {
        @Override
        public Object getAttribute(String name) {
            Object value;
            synchronized (this) { value = super.getAttribute(name); }
            if (VisitorPortalController.KYC_NONCE.equals(name)) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return value;
        }

        @Override
        public synchronized void setAttribute(String name, Object value) { super.setAttribute(name, value); }

        @Override
        public synchronized void removeAttribute(String name) { super.removeAttribute(name); }
    }

    @Test
    void twoSimultaneousSubmitsOfTheSameFormBookOnlyOnce() throws Exception {
        MockHttpSession session = new SlowNonceSession();
        session.setAttribute(VisitorPortalController.KYC_NONCE, "double-click");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<String> landedOn = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<String>> clicks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                clicks.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(booking(session, "double-click")).andReturn().getResponse().getRedirectedUrl();
                }));
            }
            start.countDown(); // both clicks go at once
            for (java.util.concurrent.Future<String> click : clicks) {
                landedOn.add(click.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // One click books, the other is refused by the burned nonce
        assertThat(landedOn).containsExactlyInAnyOrder("/success-page", "/");
        verify(repo, org.mockito.Mockito.times(1)).save(any(Visitor.class));
    }
}
