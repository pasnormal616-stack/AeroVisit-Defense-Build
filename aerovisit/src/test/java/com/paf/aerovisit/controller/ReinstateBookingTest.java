package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BookingReinstatement;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.SeatReservationService;

/**
 * ♻️ "Reinstate booking" (Director): a PAYMENT_EXPIRED booking the visitor had paid comes back as PAID + APPROVED, in its
 * own slot when that is still in the future with seats, otherwise in an open slot the Director picks. Web slice with the
 * real reinstatement rules, seat service and receipt checks; the database is mocked (SeatReservationReinstateTest covers
 * the conditional UPDATE on a real one).
 */
@WebMvcTest({ReinstateBookingController.class, GuardDashboardController.class})
@Import({SecurityConfig.class, BookingReinstatement.class, SeatReservationService.class, ReceiptStorage.class})
class ReinstateBookingTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate FUTURE = TODAY.plusDays(3);
    private static final LocalDate LATER = TODAY.plusDays(5);
    private static final LocalDate PAST = TODAY.minusDays(1);
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String ALL_DAY = "All Day (12:00 AM - 11:59 PM)"; // still open whenever the test runs
    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final String NOTE = "Paid; confirmed in Landbank records, receipt sent by email";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private final List<BookingSlot> allSlots = new ArrayList<>();
    private final List<Visitor> bookings = new ArrayList<>();

    @BeforeEach
    void slotsAndRepository() {
        slot(FUTURE, MORNING, 10);
        slot(LATER, MORNING, 10);
        slot(PAST, MORNING, 10);
        slot(TODAY, ALL_DAY, 10);
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(any())).thenAnswer(inv -> allSlots.stream()
                .filter(s -> !s.getSlotDate().isBefore(inv.getArgument(0))).toList());
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.claimStatus(anyLong(), eq(Visitor.STATUS_PAYMENT_EXPIRED), eq("APPROVED"))).thenReturn(1);
    }

    @AfterEach
    void removeFiles() throws Exception {
        for (Visitor v : bookings) {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + v.getId() + ".png"));
            if (v.getReceiptImageUrl() != null && v.getReceiptImageUrl().startsWith("receipt-")
                    && !v.getReceiptImageUrl().equals("receipt-old.jpg")) {
                Files.deleteIfExists(Paths.get("uploads", v.getReceiptImageUrl()));
            }
        }
    }

    private void slot(LocalDate date, String window, int capacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(capacity);
        allSlots.add(s);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, window)).thenReturn(List.of(s));
        when(bookingRepo.lockBySlotDateAndTimeWindow(date, window)).thenReturn(List.of(s));
    }

    // A released advance booking: 2 regular at PHP 150 = PHP 300.00, billing 000555
    private Visitor expired(long id, LocalDate date, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan" + id + "@example.com");
        v.setReferenceId("PAF-R" + id);
        v.setStatus(Visitor.STATUS_PAYMENT_EXPIRED);
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setGroupSize(2);
        v.setRegularCount(2);
        v.setDiscountedCount(0);
        v.setFreeCount(0);
        v.setRegularPriceCentavos(15_000L);
        v.setAdmissionTotalCentavos(30_000L);
        v.setBillingNumber("000555");
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        v.setSecureToken("qr-token-" + id);
        when(repo.findById(id)).thenReturn(Optional.of(v));
        bookings.add(v);
        return v;
    }

    private Visitor expiredWalkIn(long id, LocalDate date, String window) {
        Visitor v = expired(id, date, window);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        return v;
    }

    private MockMultipartHttpServletRequestBuilder reinstate(long id) {
        MockMultipartHttpServletRequestBuilder request = multipart("/admin/bookings/{id}/reinstate", id);
        request.with(csrf());
        return request;
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(DIRECTOR)).andExpect(redirectedUrl("/admin"));
    }

    private void assertNothingSaved() {
        verify(repo, never()).save(any(Visitor.class));
        verify(emailService, never()).sendApprovalEmailWithQR(any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).bookingReinstated(any());
    }

    // --- Reinstate into the original slot ---

    @Test
    void anAdvanceBookingWithSeatsIsPaidApprovedAndGetsItsQrEmailLikeANormalApproval() throws Exception {
        Visitor v = expired(801, FUTURE, MORNING);

        send(reinstate(801).param("note", NOTE)).andExpect(flash().attribute("success", containsString("billing 000555")));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("director");
        assertThat(v.getPaymentVerifiedAt()).isNotNull();
        assertThat(v.getPaymentVerificationNote()).isEqualTo(NOTE);
        assertThat(v.getReinstatedBy()).isEqualTo("director");
        assertThat(v.getReinstatementNote()).isEqualTo(NOTE);
        assertThat(v.reinstatedText()).startsWith("Reinstated by director on ").endsWith(": " + NOTE);
        assertThat(v.getReinstatedFrom()).isNull();                       // not moved
        assertThat(v.getBillingNumber()).isEqualTo("000555");             // the billing number stays the same
        assertThat(v.getVisitDate()).isEqualTo(FUTURE);
        assertThat(v.getQrCodeUrl()).isEqualTo("QR_801.png");
        verify(repo).claimStatus(801L, Visitor.STATUS_PAYMENT_EXPIRED, "APPROVED");
        verify(emailService).sendApprovalEmailWithQR(eq(v.getEmail()), any(), eq(String.valueOf(FUTURE)), eq("QR_801.png"),
                eq("PAF-R801"), eq(MORNING));
        verify(notificationService).bookingReinstated(v);
    }

    @Test
    void aWalkInIsReinstatedWithoutAQrAndIsOnWalkInsTodayReadyToCheckIn() throws Exception {
        Visitor v = expiredWalkIn(802, TODAY, ALL_DAY);

        send(reinstate(802).param("note", NOTE)).andExpect(flash().attribute("success", containsString("Walk-ins Today")));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.walkIn()).isTrue();
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.getCheckInDueAt()).isEqualTo(TODAY.atTime(LocalTime.of(23, 59))); // until its window ends
        verify(emailService, never()).sendApprovalEmailWithQR(any(), any(), any(), any(), any(), any());
        verify(notificationService).bookingReinstated(v);

        when(repo.findByVisitDate(TODAY)).thenReturn(List.of(v));
        mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
                .andExpect(model().attribute("visitors", List.of(v)));
    }

    // --- Full or past original slot: refused unless another open slot is chosen ---

    @Test
    void aFullOriginalSlotIsRefusedAndNothingChanges() throws Exception {
        Visitor v = expired(803, FUTURE, MORNING);
        when(repo.sumSeatsHeld(eq(FUTURE), eq(MORNING), any(), any())).thenReturn(9L); // 1 seat left, group of 2

        send(reinstate(803).param("note", NOTE))
                .andExpect(flash().attribute("error", containsString("The original time slot is full. Choose another open date")));

        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        verify(repo, never()).claimStatus(anyLong(), anyString(), anyString());
        assertNothingSaved();
    }

    @Test
    void aPastOriginalSlotIsRefused() throws Exception {
        Visitor v = expired(804, PAST, MORNING);

        send(reinstate(804).param("note", NOTE))
                .andExpect(flash().attribute("error", containsString("The original time slot has already passed")));

        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertNothingSaved();
    }

    @Test
    void withAnotherOpenSlotChosenThePastBookingMovesThereAndIsReinstated() throws Exception {
        Visitor v = expired(805, PAST, MORNING);

        send(reinstate(805).param("note", NOTE).param("visitDate", FUTURE.toString()).param("timeWindow", MORNING))
                .andExpect(flash().attribute("success", containsString("moved to " + FUTURE + " · " + MORNING)));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getVisitDate()).isEqualTo(FUTURE);
        assertThat(v.getTimeWindow()).isEqualTo(MORNING);
        assertThat(v.getReinstatedFrom()).isEqualTo(PAST + " · " + MORNING);
        verify(emailService).sendApprovalEmailWithQR(eq(v.getEmail()), any(), eq(String.valueOf(FUTURE)), eq("QR_805.png"), any(), any());
    }

    @Test
    void aFullOriginalSlotCanMoveToAnotherOpenSlot() throws Exception {
        Visitor v = expired(806, FUTURE, MORNING);
        when(repo.sumSeatsHeld(eq(FUTURE), eq(MORNING), any(), any())).thenReturn(10L);

        send(reinstate(806).param("note", NOTE).param("visitDate", LATER.toString()).param("timeWindow", MORNING));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getVisitDate()).isEqualTo(LATER);
    }

    @Test
    void aWalkInMovedToALaterDateBecomesAnAdvanceBookingWithAQr() throws Exception {
        Visitor v = expiredWalkIn(807, PAST, MORNING);

        send(reinstate(807).param("note", NOTE).param("visitDate", FUTURE.toString()).param("timeWindow", MORNING));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.walkIn()).isFalse();
        assertThat(v.getBookingCategory()).isNull();
        assertThat(v.getCheckInDueAt()).isNull();
        verify(emailService).sendApprovalEmailWithQR(eq(v.getEmail()), any(), any(), eq("QR_807.png"), any(), any());
    }

    @Test
    void aChosenSlotThatIsNotOfferedFullOrClosedIsRefused() throws Exception {
        expired(808, PAST, MORNING);
        send(reinstate(808).param("note", NOTE).param("visitDate", FUTURE.toString()).param("timeWindow", "Evening (06:00 PM - 08:00 PM)"))
                .andExpect(flash().attribute("error", ReinstateBookingController.SLOT_NOT_OPEN));
        send(reinstate(808).param("note", NOTE).param("visitDate", PAST.toString()).param("timeWindow", MORNING))
                .andExpect(flash().attribute("error", ReinstateBookingController.SLOT_NOT_OPEN));
        send(reinstate(808).param("note", NOTE).param("visitDate", "not-a-date").param("timeWindow", MORNING))
                .andExpect(flash().attribute("error", ReinstateBookingController.SLOT_NOT_OPEN));

        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.<Object[]>of(new Object[] {FUTURE, MORNING, 9L}));
        send(reinstate(808).param("note", NOTE).param("visitDate", FUTURE.toString()).param("timeWindow", MORNING))
                .andExpect(flash().attribute("error", ReinstateBookingController.SLOT_NOT_OPEN));

        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.of());
        when(repo.findClosureDates()).thenReturn(List.of(LATER));
        send(reinstate(808).param("note", NOTE).param("visitDate", LATER.toString()).param("timeWindow", MORNING))
                .andExpect(flash().attribute("error", ReinstateBookingController.SLOT_NOT_OPEN));
        assertNothingSaved();
    }

    // --- Note and receipt ---

    @Test
    void theNoteIsRequired() throws Exception {
        Visitor v = expired(809, FUTURE, MORNING);
        send(reinstate(809)).andExpect(flash().attribute("error", ReinstateBookingController.NOTE_REQUIRED));
        send(reinstate(809).param("note", "   ")).andExpect(flash().attribute("error", ReinstateBookingController.NOTE_REQUIRED));
        send(reinstate(809).param("note", "x".repeat(301))).andExpect(flash().attribute("error", ReinstateBookingController.NOTE_TOO_LONG));
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertNothingSaved();
    }

    @Test
    void anAttachedReceiptIsStoredAndTheEarlierOneIsKept() throws Exception {
        Visitor v = expired(810, FUTURE, MORNING);
        v.setReceiptImageUrl("receipt-old.jpg");

        send(reinstate(810).file(new MockMultipartFile("receipt", "receipt.png", "image/png", TestImages.png(40))).param("note", NOTE));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getReceiptImageUrl()).startsWith("receipt-").endsWith(".png").isNotEqualTo("receipt-old.jpg");
        assertThat(Files.exists(Paths.get("uploads", v.getReceiptImageUrl()))).isTrue();
        assertThat(v.earlierReceiptList()).containsExactly("receipt-old.jpg"); // a receipt is never dropped
        assertThat(v.getReceiptUploadedAt()).isNotNull();
    }

    @Test
    void theReinstatementWorksWithoutAReceipt() throws Exception {
        Visitor v = expired(811, FUTURE, MORNING);
        send(reinstate(811).param("note", NOTE));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getReceiptImageUrl()).isNull();
    }

    @Test
    void aFakeOrOversizedReceiptIsRefusedWithTheVisitorMessage() throws Exception {
        Visitor v = expired(812, FUTURE, MORNING);
        send(reinstate(812).file(new MockMultipartFile("receipt", "receipt.png", "image/png", "not an image".getBytes())).param("note", NOTE))
                .andExpect(flash().attribute("error", "Please upload a JPG, PNG or WEBP screenshot of your receipt."));
        send(reinstate(812).file(new MockMultipartFile("receipt", "big.png", "image/png", new byte[(int) ReceiptStorage.MAX_RECEIPT_BYTES + 1]))
                .param("note", NOTE))
                .andExpect(flash().attribute("error", "The receipt is too large. Please upload a screenshot of 5 MB or less."));
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertNothingSaved();
    }

    // --- Only PAYMENT_EXPIRED, once; Director only ---

    @Test
    void onlyAPaymentExpiredBookingCanBeReinstated() throws Exception {
        Visitor v = expired(813, FUTURE, MORNING);
        v.setStatus("APPROVED");
        send(reinstate(813).param("note", NOTE)).andExpect(flash().attribute("error", ReinstateBookingController.NOT_EXPIRED));
        v.setStatus("REJECTED");
        send(reinstate(813).param("note", NOTE)).andExpect(flash().attribute("error", ReinstateBookingController.NOT_EXPIRED));
        assertNothingSaved();
    }

    @Test
    void aSecondReinstateDoesNothing() throws Exception {
        Visitor v = expired(814, FUTURE, MORNING);
        when(repo.claimStatus(814L, Visitor.STATUS_PAYMENT_EXPIRED, "APPROVED")).thenReturn(0); // already claimed by the first click

        send(reinstate(814).param("note", NOTE)).andExpect(flash().attribute("error", ReinstateBookingController.ALREADY_DONE));

        assertNothingSaved();
        assertThat(Files.exists(Paths.get("uploads", "QR_814.png"))).isFalse(); // the attempt's QR was removed
    }

    @Test
    void aGuardGets403OnBothEndpoints() throws Exception {
        Visitor v = expired(815, FUTURE, MORNING);
        mvc.perform(reinstate(815).param("note", NOTE).with(GUARD)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/bookings/815/reinstate-options").with(GUARD)).andExpect(status().isForbidden());
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertNothingSaved();
    }

    // --- The dialog's data ---

    @Test
    void theOptionsWarnWhenTheSameEmailHasAnotherActiveBookingThatDate() throws Exception {
        Visitor v = expired(816, FUTURE, MORNING);
        Visitor rebooked = expired(817, FUTURE, MORNING);
        rebooked.setStatus(Visitor.STATUS_AWAITING_RECEIPT);
        Visitor cancelled = expired(818, LATER, MORNING);
        cancelled.setStatus("CANCELLED_BY_VISITOR");
        when(repo.findByEmailIgnoreCase(v.getEmail())).thenReturn(List.of(v, rebooked, cancelled));

        mvc.perform(get("/admin/bookings/816/reinstate-options").with(DIRECTOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingNumber").value("000555"))
                .andExpect(jsonPath("$.amount").value(v.amountDueText()))
                .andExpect(jsonPath("$.date").value(FUTURE.toString()))
                .andExpect(jsonPath("$.timeWindow").value(MORNING))
                .andExpect(jsonPath("$.originalAvailable").value(true))
                .andExpect(jsonPath("$.otherActiveDates").value(org.hamcrest.Matchers.contains(FUTURE.toString())))
                .andExpect(jsonPath("$.openSlots[*].date").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(PAST.toString()))));
    }

    @Test
    void theOptionsOfANonExpiredBookingAreRefused() throws Exception {
        Visitor v = expired(819, FUTURE, MORNING);
        v.setStatus("APPROVED");
        mvc.perform(get("/admin/bookings/819/reinstate-options").with(DIRECTOR))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(ReinstateBookingController.NOT_EXPIRED));
    }
}
