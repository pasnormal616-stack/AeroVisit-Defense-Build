package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
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
import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
 * Security fixes L12 and L13 on the booking page: the privacy notice names the passport copy and the AWS hosting that
 * stores the files, and an email typed with spaces around it is the same email (OTP, bans and the duplicate check).
 * No database.
 */
@WebMvcTest(VisitorPortalController.class)
@Import({FixedClock.class, SecurityConfig.class, WalkInService.class, PaymentWorkflow.class, AdvanceBookingRules.class})
class LowFindingsPortalTest {

    private static final String EMAIL = "juan@example.com";
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DAY = FixedClock.TODAY.plusDays(2);

    @Autowired private MockMvc mvc;

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

    @BeforeEach
    void setUp() {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(DAY);
        s.setTimeWindow(MORNING);
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, MORNING)).thenReturn(List.of(s));
        when(bookingRepo.findAll()).thenReturn(List.of(s));
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
    }

    // --- L12 ---

    @Test
    void thePrivacyNoticeNamesThePassportCopyAndTheAwsStorage() throws Exception {
        mvc.perform(get("/")).andExpect(content().string(Matchers.allOf(
                Matchers.containsString("Passport copy (foreign visitors):"),
                Matchers.containsString("Amazon Web Services (AWS)"),
                Matchers.containsString("<b>Passport copies</b> of foreign visitors are deleted automatically 30 days after the visit date"),
                Matchers.not(Matchers.containsString("never sent to any outside service")))));
    }

    // --- L13 ---

    @Test
    void anEmailWithSpacesAroundItIsCaughtAsTheSameVisitorsDuplicate() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(true); // verified as typed in the code step: no spaces
        Visitor existing = new Visitor();
        existing.setId(91L);
        existing.setEmail(EMAIL);
        existing.setStatus("PENDING");
        existing.setVisitDate(DAY);
        when(repo.findByEmailAndVisitDate(EMAIL, DAY)).thenReturn(new ArrayList<>(List.of(existing)));

        var req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", com.paf.aerovisit.TestImages.jpeg(47)));
        req.param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.").param("email", "  " + EMAIL + " ")
           .param("contactNumber", "0917-555-0101").param("visitDate", DAY.toString()).param("timeWindow", MORNING)
           .param("freeCount", "1");
        mvc.perform(req)
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Registration Denied: Duplicate booking detected for this date."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(repo, never()).save(any());
    }
}
