package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdvanceBookingRules;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.Holidays;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.SeatReservationService;

/**
 * 📅 Manage Booking does two things in a no-refund system: attach the payment receipt (the same upload, rules and
 * deadline as the payment page) and reschedule to another open slot (the rules of a new advance booking). There is no
 * cancelling and no editing of the group or contact details: those routes are gone (404). No database, no real email.
 */
@WebMvcTest({VisitorPortalController.class, PaymentController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, AdvanceBookingRules.class, Holidays.class})
class ManageBookingRescheduleTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final LocalDate DAY = TODAY.plusDays(5);
    private static final LocalDate LATER = TODAY.plusDays(8);
    private static final LocalDate HOLIDAY = TODAY.plusDays(7);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private SeatReservationService seatReservation;
    @MockitoBean private HolidayRepository holidayRepo;
    @MockitoBean private BillingNumberService billingNumbers; // needed by PaymentWorkflow; never called here

    private final List<Visitor> all = new ArrayList<>();
    private final List<BookingSlot> slots = new ArrayList<>();

    @BeforeEach
    void museum() {
        for (LocalDate d : List.of(TODAY, TODAY.plusDays(2), DAY, HOLIDAY, LATER)) slots.add(slot(d, 10));
        when(bookingRepo.findAll()).thenReturn(slots);
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(HOLIDAY, "Bonifacio Day", false)));
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        // Seats are counted from each booking's own date and window, so a move frees the old slot by itself
        when(repo.findByVisitDate(any())).thenAnswer(inv -> all.stream().filter(b -> inv.getArgument(0).equals(b.getVisitDate())).toList());
        when(repo.findByEmailAndVisitDate(any(), any())).thenAnswer(inv -> new ArrayList<>(all.stream()
                .filter(b -> inv.getArgument(0).equals(b.getEmail()) && inv.getArgument(1).equals(b.getVisitDate())).toList()));
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void removeFiles() throws Exception {
        for (Visitor v : all) {
            if (v.getReceiptImageUrl() != null) Files.deleteIfExists(Paths.get("uploads", v.getReceiptImageUrl()));
            for (String earlier : v.earlierReceiptList()) Files.deleteIfExists(Paths.get("uploads", earlier));
        }
    }

    private static BookingSlot slot(LocalDate date, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(max);
        return s;
    }

    // A paid advance booking waiting for its receipt (pay first): 2 regular at PHP 150, seats held, 48 h to pay
    private Visitor awaitingReceipt(long id, LocalDate date, int people) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan" + id + "@example.com");
        v.setReferenceId("PAF-" + id);
        v.setStatus(Visitor.STATUS_AWAITING_RECEIPT);
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(people);
        v.setDiscountedCount(0);
        v.setRegularCount(people);
        v.setFreeCount(0);
        v.setDiscountedPriceCentavos(10_000L);
        v.setRegularPriceCentavos(15_000L);
        v.setAdmissionTotalCentavos(15_000L * people);
        v.setBillingNumber("000" + id);
        v.setPaymentToken("pay-token-" + id);
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        v.setPaymentDueAt(FixedClock.NOW.plusHours(48));
        v.setTermsAcceptedAt(FixedClock.NOW.minusHours(1));
        v.setTermsVersion("2026-11b");
        when(repo.findById(id)).thenReturn(Optional.of(v));
        all.add(v);
        return v;
    }

    private static MockHttpSession signedIn(Visitor v) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.MANAGE_VISITOR_ID, v.getId());
        session.setAttribute(VisitorPortalController.MANAGE_FORM_TOKEN, "tok-" + v.getId());
        return session;
    }

    private org.springframework.test.web.servlet.ResultActions reschedule(Visitor v, LocalDate date) throws Exception {
        return mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(v)).param("formToken", "tok-" + v.getId())
                .param("visitDate", date.toString()).param("timeWindow", WINDOW));
    }

    // --- 💳 Attach payment receipt ---

    @Test
    void aReceiptAttachedFromManageBookingIsAcceptedLikeThePaymentPage() throws Exception {
        Visitor v = awaitingReceipt(41L, DAY, 2);
        MockHttpSession session = signedIn(v);
        String page = mvc.perform(get("/manage-booking").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("href=\"/manage-booking/payment\"", "Pay / Upload Proof of Payment");

        MockMultipartFile receipt = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", TestImages.jpeg(9));
        mvc.perform(multipart("/payment/proof").file(receipt).param("formToken", "tok-41").session(session).with(csrf()))
           .andExpect(redirectedUrl("/manage-booking/payment"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Thank you! Your proof of payment was submitted. The museum will review")));
        assertThat(v.getStatus()).isEqualTo("PENDING");                   // into Pending Approval, as from the emailed link
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.getReceiptImageUrl()).matches("receipt-[0-9a-f-]{36}\\.jpg");
        assertThat(v.holdsSeat()).isTrue();
        verify(seatReservation, never()).saveIfSeatsRemain(any());        // seats held since booking: no new seat check
        verify(notificationService).bookingRequest(v);
    }

    @Test
    void afterThePaymentDeadlineManageBookingRefusesTheReceiptLikeThePaymentPage() throws Exception {
        Visitor v = awaitingReceipt(42L, DAY, 2);
        v.setPaymentDueAt(FixedClock.NOW.minusMinutes(1));
        MockHttpSession session = signedIn(v);
        assertThat(mvc.perform(get("/manage-booking").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
            .doesNotContain("href=\"/manage-booking/payment\"");

        MockMultipartFile receipt = new MockMultipartFile("receipt", "receipt.jpg", "image/jpeg", TestImages.jpeg(9));
        mvc.perform(multipart("/payment/proof").file(receipt).param("formToken", "tok-42").session(session).with(csrf()))
           .andExpect(redirectedUrl("/manage-booking/payment"))
           .andExpect(flash().attribute("error", PaymentWorkflow.uploadBlocker(v, FixedClock.NOW)));
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(v.getReceiptImageUrl()).isNull();
        verify(repo, never()).save(any());
    }

    // --- 📅 Reschedule ---

    @Test
    void aValidRescheduleMovesTheSeatsAndAsksForNoNewPaymentOrTerms() throws Exception {
        Visitor v = awaitingReceipt(43L, DAY, 2);
        Visitor other = awaitingReceipt(44L, LATER, 8); // LATER: 8 of 10 seats taken, so the group of 2 fits exactly
        LocalDateTime terms = v.getTermsAcceptedAt();

        reschedule(v, LATER).andExpect(redirectedUrl("/manage-booking")).andExpect(flash().attribute("success", "Booking updated."));

        ArgumentCaptor<Visitor> locked = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(locked.capture()); // saved under the seat lock
        assertThat(locked.getValue().getVisitDate()).isEqualTo(LATER);
        assertThat(v.getTimeWindow()).isEqualTo(WINDOW);
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(v.getGroupSize()).isEqualTo(2);
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(30_000L);
        assertThat(v.getBillingNumber()).isEqualTo("00043");
        assertThat(v.getPaymentToken()).isEqualTo("pay-token-43");
        assertThat(v.getTermsAcceptedAt()).isEqualTo(terms);
        verify(emailService, never()).sendPaymentRequest(any());

        // The new slot is full now and the old one is free again
        Visitor third = awaitingReceipt(45L, TODAY.plusDays(2), 1);
        reschedule(third, LATER).andExpect(flash().attribute("error", "Not enough seats: only 0 left in that time slot."));
        assertThat(other.getVisitDate()).isEqualTo(LATER);
        reschedule(third, DAY).andExpect(flash().attribute("success", "Booking updated."));
        assertThat(third.getVisitDate()).isEqualTo(DAY);
    }

    @Test
    void anApprovedPaidBookingMovesBackToPendingApprovalAndStaysPaid() throws Exception {
        Visitor v = awaitingReceipt(46L, DAY, 2);
        v.setStatus("APPROVED");
        v.setPaymentStatus(Visitor.PAY_PAID);
        v.setQrCodeUrl("QR_46.png");
        v.setReminderSent(true);

        reschedule(v, LATER).andExpect(flash().attribute("success", Matchers.startsWith("Booking updated. Your new schedule has been sent back")));
        assertThat(v.getVisitDate()).isEqualTo(LATER);
        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.isReminderSent()).isFalse();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        verify(notificationService).bookingRescheduled(v);
    }

    @Test
    void aRescheduleIsRefusedForTodayAHolidayAClosureTheSameSlotOrADateTooSoon() throws Exception {
        Visitor v = awaitingReceipt(47L, DAY, 2);
        String tooSoon = AdvanceBookingRules.tooSoonMessage(1);

        reschedule(v, TODAY).andExpect(flash().attribute("error", tooSoon));
        reschedule(v, TODAY.minusDays(1)).andExpect(flash().attribute("error", "Please choose a date that has not passed."));
        reschedule(v, HOLIDAY).andExpect(flash().attribute("error", "Closed: Bonifacio Day. Please choose another date."));
        reschedule(v, DAY).andExpect(flash().attribute("error", "That is your current date and time. Please choose a different one."));
        when(repo.findClosureDates()).thenReturn(List.of(LATER));
        reschedule(v, LATER).andExpect(flash().attribute("error", "The museum is closed on this date due to an emergency. Please choose another date."));

        // The Director's minimum days ahead applies, as for a new advance booking
        PortalSettings settings = new PortalSettings();
        settings.setAdvanceMinDaysAhead(3);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        reschedule(v, TODAY.plusDays(2)).andExpect(flash().attribute("error", AdvanceBookingRules.tooSoonMessage(3)));

        assertThat(v.getVisitDate()).isEqualTo(DAY);
        verify(repo, never()).save(any());
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    @Test
    void theRescheduleListOffersOnlyDatesANewAdvanceBookingCouldTake() throws Exception {
        Visitor v = awaitingReceipt(48L, DAY, 2);
        String page = mvc.perform(get("/manage-booking").session(signedIn(v))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("\"date\":\"" + TODAY.plusDays(2) + "\"", "\"date\":\"" + LATER + "\"")
            .doesNotContain("\"date\":\"" + TODAY + "\"", "\"date\":\"" + HOLIDAY + "\"", "\"date\":\"" + DAY + "\"");
    }

    @Test
    void aWalkInCannotBeRescheduled() throws Exception {
        Visitor v = awaitingReceipt(49L, TODAY, 1);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setStatus("APPROVED");

        reschedule(v, LATER).andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        assertThat(v.getVisitDate()).isEqualTo(TODAY);
        verify(repo, never()).save(any());
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    @Test
    void aRescheduleNeededBookingCanPickANewDateAfterItsOldOneUntilTheDeadline() throws Exception {
        Visitor v = awaitingReceipt(51L, TODAY.minusDays(3), 2); // not reviewed in time (or the museum closed)
        v.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
        v.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        v.setRejectionReason(Visitor.UNREVIEWED_NOTE);
        String page = mvc.perform(get("/manage-booking").session(signedIn(v))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("The museum could not review your booking before your visit date.",
                TODAY.minusDays(3).plusDays(Visitor.RESCHEDULE_DAYS).toString(), "action=\"/manage-booking/reschedule\"");

        reschedule(v, LATER).andExpect(flash().attribute("success", Matchers.startsWith("Booking updated. Your new schedule")));
        assertThat(v.getVisitDate()).isEqualTo(LATER);
        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.getRejectionReason()).isNull();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);

        // Past the deadline: locked
        Visitor late = awaitingReceipt(52L, TODAY.minusDays(Visitor.RESCHEDULE_DAYS + 1), 2);
        late.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
        reschedule(late, LATER).andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        assertThat(late.getVisitDate()).isEqualTo(TODAY.minusDays(Visitor.RESCHEDULE_DAYS + 1));
    }

    // --- 🗑️ No editing the group or contact details, no cancelling ---

    @Test
    void updateAndCancelAreGoneFromThePageAndTheirRoutesReturn404() throws Exception {
        Visitor v = awaitingReceipt(50L, DAY, 2);
        MockHttpSession session = signedIn(v);
        String page = mvc.perform(get("/manage-booking").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("action=\"/manage-booking/reschedule\"", "Reschedule Your Visit")
            .doesNotContain("/manage-booking/update", "/manage-booking/cancel", "Cancel My Visit", "Update Your Visit",
                    "name=\"contactNumber\"", "name=\"groupSize\"", "name=\"regularCount\"", "name=\"discountedCount\"", "name=\"freeCount\"");

        mvc.perform(post("/manage-booking/update").with(csrf()).session(session).param("formToken", "tok-50")
                .param("visitDate", LATER.toString()).param("timeWindow", WINDOW).param("groupSize", "5").param("contactNumber", "0918"))
           .andExpect(status().isNotFound());
        mvc.perform(post("/manage-booking/cancel").with(csrf()).session(session).param("formToken", "tok-50"))
           .andExpect(status().isNotFound());
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(v.getVisitDate()).isEqualTo(DAY);
        assertThat(v.getGroupSize()).isEqualTo(2);
        verify(repo, never()).save(any());
    }
}
