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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * 💱 Admission categories on /save and Manage Booking, and the Terms and Conditions consent the server requires and records.
 * groupSize = discounted + regular + free (set by the server); the total is priced by the server at the Director's prices
 * and saved on the booking, so a later price change never alters it. No database, no Python.
 */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class AdmissionBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    // Real JPEGs: /save checks the photos' bytes (signature + decode), not only their declared type
    private static final byte[] ID_CARD = com.paf.aerovisit.TestImages.jpeg(44);
    private static final AdmissionPricing.Prices TODAY_PRICES = new AdmissionPricing.Prices(10_000L, 15_000L); // PHP 100 / 150

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private AdmissionPricing admissionPricing;
    // 💳 A paid advance booking pays first: /save hands it to the payment service and opens the payment page
    @MockitoBean private com.paf.aerovisit.service.PaymentWorkflow paymentWorkflow;

    private final List<Path> createdFiles = new ArrayList<>();

    @BeforeEach
    void openSlot() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(admissionPricing.current()).thenReturn(TODAY_PRICES);
        when(otpService.isVerified(EMAIL)).thenReturn(true);
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

    private static MockMultipartHttpServletRequestBuilder booking(String discounted, String regular, String free) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_CARD));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL).param("visitDate", DAY.toString()).param("timeWindow", WINDOW)
           .param("discountedCount", discounted).param("regularCount", regular).param("freeCount", free);
        return req;
    }

    private Visitor saved() {
        ArgumentCaptor<Visitor> captor = ArgumentCaptor.forClass(Visitor.class);
        verify(repo, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }

    // --- Terms and Conditions: the one consent, required and recorded by the server ---

    @Test
    void aBookingWithoutTheTermsConsentIsRefusedAndNothingIsSaved() throws Exception {
        for (String answer : new String[] {null, "", "false", "yes"}) {
            MockMultipartHttpServletRequestBuilder req = booking("0", "1", "0");
            if (answer != null) req.param("termsAccepted", answer);
            mvc.perform(req).andExpect(redirectedUrl("/"))
               .andExpect(flash().attribute("error", "Please read and agree to the Terms and Conditions before booking."));
        }
        verify(repo, never()).save(any());
    }

    @Test
    void aSavedBookingRecordsWhenAndWhichTermsVersionWereAccepted() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        mvc.perform(booking("0", "1", "0").param("termsAccepted", "true")
                .param("termsVersion", "forged").param("termsAcceptedAt", "2020-01-01T00:00"))
           .andExpect(redirectedUrl("/manage-booking/payment"));

        Visitor v = saved();
        assertThat(v.getTermsVersion()).isEqualTo(VisitorPortalController.TERMS_VERSION).isEqualTo("2026-11d");
        assertThat(v.getTermsAcceptedAt()).isNotNull().isBetween(before, LocalDateTime.now());
    }

    // --- Admission counts ---

    @Test
    void theCountsAddUpToGroupSizeAndTheTotalUsesTodaysPrices() throws Exception {
        mvc.perform(booking("2", "3", "1").param("termsAccepted", "true")).andExpect(redirectedUrl("/manage-booking/payment"));

        Visitor v = saved();
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT); // pays first, seats held
        verify(paymentWorkflow).onAdvanceBooking(org.mockito.ArgumentMatchers.eq(v), any());
        assertThat(v.getGroupSize()).isEqualTo(6);
        assertThat(v.getDiscountedCount()).isEqualTo(2);
        assertThat(v.getRegularCount()).isEqualTo(3);
        assertThat(v.getFreeCount()).isEqualTo(1);
        assertThat(v.getDiscountedPriceCentavos()).isEqualTo(10_000L);
        assertThat(v.getRegularPriceCentavos()).isEqualTo(15_000L);
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(65_000L); // 2 x 100 + 3 x 150; the free visitor is not charged
        assertThat(v.admissionTotalText()).isEqualTo("PHP 650.00");
        assertThat(v.admissionSummary()).isEqualTo("2 Discounted · 3 Regular · 1 Free");
    }

    @Test
    void aTamperedTotalGroupSizeOrPriceFromTheBrowserIsIgnored() throws Exception {
        mvc.perform(booking("1", "1", "0").param("termsAccepted", "true")
                .param("admissionTotalCentavos", "1").param("estimatedTotal", "0.01").param("groupSize", "1")
                .param("regularPriceCentavos", "0").param("discountedPriceCentavos", "0"))
           .andExpect(redirectedUrl("/manage-booking/payment"));

        Visitor v = saved();
        assertThat(v.getGroupSize()).isEqualTo(2);
        assertThat(v.getRegularPriceCentavos()).isEqualTo(15_000L);
        assertThat(v.getDiscountedPriceCentavos()).isEqualTo(10_000L);
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(25_000L);
    }

    @Test
    void noVisitorsOrANegativeCountIsRefused() throws Exception {
        mvc.perform(booking("0", "0", "0").param("termsAccepted", "true"))
           .andExpect(flash().attribute("error", "Please enter at least 1 visitor."));
        mvc.perform(booking("-1", "3", "0").param("termsAccepted", "true"))
           .andExpect(flash().attribute("error", "Visitor counts cannot be negative."));
        verify(repo, never()).save(any());
    }

    @Test
    void capacityIsEnforcedOnTheSumOfTheThreeCounts() throws Exception {
        // 30 + 25 = 55 people for a 50-seat slot: refused even though each count alone would fit
        mvc.perform(booking("30", "25", "0").param("termsAccepted", "true"))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Insufficient slots available for this specific shift! Please choose another slot."));
        // Free visitors take seats too
        mvc.perform(booking("0", "1", "50").param("termsAccepted", "true"))
           .andExpect(flash().attribute("error", "Insufficient slots available for this specific shift! Please choose another slot."));
        verify(repo, never()).save(any());
    }

    @Test
    void theBookingPageShowsTheDirectorsPrices() throws Exception {
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(8_000L, 17_550L));
        mvc.perform(get("/"))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("<h3 class=\"vp-admission-list-title\">Admission</h3>"),
               Matchers.containsString("<dt>Private Individuals (Regular)</dt><dd>PHP 175.50</dd>"),
               Matchers.containsString("<dd>PHP 80.00</dd>"),
               Matchers.containsString("data-price-centavos=\"17550\""),
               Matchers.containsString("data-price-centavos=\"8000\""))));
    }

    // --- A price change never alters an existing booking ---

    private static MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("manageVisitorId", 7L);
        session.setAttribute("manageFormToken", "tok-123");
        return session;
    }

    private Visitor bookedAtOldPrices() {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail(EMAIL);
        v.setStatus("PENDING");
        v.setReferenceId("PAF-A7X92K");
        v.setVisitDate(DAY);
        v.setTimeWindow(WINDOW);
        v.setDiscountedCount(1);
        v.setRegularCount(1);
        v.setFreeCount(0);
        v.setGroupSize(2);
        v.setDiscountedPriceCentavos(10_000L);
        v.setRegularPriceCentavos(15_000L);
        v.setAdmissionTotalCentavos(25_000L);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findAll()).thenReturn(List.of(slot));
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(v));
        return v;
    }

    @Test
    void aPriceChangeDoesNotAlterAnExistingBooking() throws Exception {
        Visitor v = bookedAtOldPrices();
        // The Director raises both prices after the booking was made
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(20_000L, 30_000L));

        mvc.perform(get("/manage-booking").session(signedIn()))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("1 Discounted · 1 Regular · 0 Free"),
               Matchers.containsString("PHP 250.00"),
               // 📅 Manage Booking only reschedules: the group and its counts are not editable there
               Matchers.not(Matchers.containsString("name=\"regularCount\"")),
               Matchers.not(Matchers.containsString("name=\"groupSize\"")))));
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(25_000L);
    }

    // --- 💳 After payment: the date can still change (payment kept) ---

    private Visitor paidAndApproved() {
        Visitor v = bookedAtOldPrices();
        v.setStatus("APPROVED");
        v.setQrCodeUrl("QR_7.png");
        v.setBillingNumber("000123");
        v.setPaymentStatus(Visitor.PAY_PAID);
        return v;
    }

    @Test
    void aPaidBookingCanMoveToAnotherDateAndStaysPaid() throws Exception {
        Visitor v = paidAndApproved();
        LocalDate later = DAY.plusDays(1);
        BookingSlot today = new BookingSlot();
        today.setSlotDate(DAY);
        today.setTimeWindow(WINDOW);
        today.setMaxCapacity(50);
        BookingSlot next = new BookingSlot();
        next.setSlotDate(later);
        next.setTimeWindow(WINDOW);
        next.setMaxCapacity(50);
        when(bookingRepo.findAll()).thenReturn(List.of(today, next));

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn()).param("formToken", "tok-123")
                .param("visitDate", later.toString()).param("timeWindow", WINDOW))
           .andExpect(flash().attribute("success", Matchers.startsWith("Booking updated. Your new schedule")));
        assertThat(v.getVisitDate()).isEqualTo(later);
        assertThat(v.getStatus()).isEqualTo("PENDING"); // the museum approves the new date...
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID); // ...without a new payment
        assertThat(v.getBillingNumber()).isEqualTo("000123");
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(25_000L);
    }

}
