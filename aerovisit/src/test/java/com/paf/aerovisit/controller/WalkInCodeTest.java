package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.AdvanceBookingRules;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.WalkInService;

/**
 * 🔑 Security fix M2: a walk-in (seats held at once, no review) needs today's on-site walk-in code, so nobody can hold
 * today's seats from home again and again. The code is turned ON here (the suites' default is off). No database.
 */
@WebMvcTest(VisitorPortalController.class)
@Import({FixedClock.class, SecurityConfig.class, WalkInService.class, PaymentWorkflow.class, AdvanceBookingRules.class})
@TestPropertySource(properties = "aerovisit.walk-in.code-required=true")
class WalkInCodeTest {

    private static final String EMAIL = "juan@example.com";
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
    private static final byte[] ID_PHOTO = com.paf.aerovisit.TestImages.jpeg(46);

    @Autowired private MockMvc mvc;
    @Autowired private WalkInService walkInService;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private AdmissionPricing admissionPricing;
    @MockitoBean private SeatReservationService seatReservation;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private final LocalDate today = FixedClock.TODAY;
    private final List<BookingSlot> allSlots = new ArrayList<>();
    private String openWindow;

    @BeforeEach
    void setUp() {
        LocalTime now = FixedClock.TIME;
        openWindow = "Open (" + CLOCK.format(now.minusHours(1)) + " - " + CLOCK.format(now.plusHours(1)) + ")";
        slot(today, openWindow);
        slot(today.plusDays(2), MORNING);
        when(bookingRepo.findAll()).thenReturn(allSlots);
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(billingNumbers.next()).thenReturn("000778");
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(61L);
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findById(61L)).thenAnswer(inv -> Optional.empty());
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        ArgumentCaptor<Visitor> all = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation, org.mockito.Mockito.atLeast(0)).saveIfSeatsRemain(all.capture());
        for (Visitor v : all.getAllValues()) {
            if (v.getIdCardImageUrl() != null) java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads").resolve(v.getIdCardImageUrl()));
        }
    }

    private void slot(LocalDate date, String window) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(50);
        allSlots.add(s);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, window)).thenReturn(List.of(s));
    }

    private MockMultipartHttpServletRequestBuilder walkIn(String code) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        req.param("bookingType", "WALK_IN").param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.")
           .param("email", EMAIL).param("contactNumber", "0917-555-0101").param("timeWindow", openWindow).param("freeCount", "1");
        if (code != null) req.param("walkInCode", code);
        return req;
    }

    private void refused(String code, String message) throws Exception {
        mvc.perform(walkIn(code)).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", message));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(repo, never()).save(any());
    }

    @Test
    void aWalkInWithoutTheCodeIsRefusedAndNothingIsSaved() throws Exception {
        refused(null, WalkInService.CODE_REQUIRED_MESSAGE);
        refused("   ", WalkInService.CODE_REQUIRED_MESSAGE);
    }

    @Test
    void aWrongCodeIsRefused() throws Exception {
        String right = walkInService.todaysCode(today);
        String wrong = (right.charAt(0) == 'A' ? "B" : "A") + right.substring(1);
        refused(wrong, WalkInService.CODE_WRONG_MESSAGE);
    }

    @Test
    void yesterdaysCodeNoLongerWorks() throws Exception {
        String yesterday = walkInService.todaysCode(today.minusDays(1));
        assertThat(yesterday).isNotEqualTo(walkInService.todaysCode(today));
        refused(yesterday, WalkInService.CODE_WRONG_MESSAGE);
    }

    @Test
    void todaysCodeBooksInAnyCaseAndWithSpaces() throws Exception {
        String code = walkInService.todaysCode(today);
        assertThat(code).matches("[A-HJ-NP-Z2-9]{6}");
        String typed = " " + code.substring(0, 3).toLowerCase(Locale.ROOT) + " " + code.substring(3) + " ";
        mvc.perform(walkIn(typed)).andExpect(redirectedUrl("/success-page"));
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(saved.capture());
        assertThat(saved.getValue().walkIn()).isTrue();
        assertThat(saved.getValue().getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void anAdvanceBookingNeedsNoCode() throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        req.param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("contactNumber", "0917-555-0101").param("visitDate", today.plusDays(2).toString())
           .param("timeWindow", MORNING).param("freeCount", "1");
        mvc.perform(req).andExpect(flash().attribute("error", Matchers.nullValue()));
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(saved.capture());
        assertThat(saved.getValue().walkIn()).isFalse();
    }

    @Test
    void theBookingPageAsksForTheCode() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(Matchers.allOf(
                Matchers.containsString("id=\"walkInCodeBlock\""),
                Matchers.containsString("name=\"walkInCode\""))));
    }
}
