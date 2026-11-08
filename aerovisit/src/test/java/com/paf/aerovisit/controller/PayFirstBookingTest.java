package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.SeatReservationService;

/**
 * 💳 PAY FIRST, end to end in a web slice (real PaymentWorkflow + ReceiptStorage, no database, no real email):
 * a paid advance booking gets its billing number and payment page at once and holds its seats from /save (through the
 * seat lock); the receipt upload puts it in Pending Approval with no new seat check; Approve marks it paid and sends the
 * QR; Reject Receipt keeps the seats and gives a short new deadline. A free booking goes straight to Pending Approval.
 * No call to the AI service at any step (no KYC).
 */
@WebMvcTest({VisitorPortalController.class, PaymentController.class, PaymentAdminController.class, GuardOperationController.class})
@Import({SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class})
class PayFirstBookingTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "juan@example.com";
    private static final long ID = 990_801L;
    private static final byte[] ID_PHOTO = TestImages.jpeg(44);
    private static final String FULL = "Insufficient slots available for this specific shift! Please choose another slot.";
    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService; // the only AI client in Java: never called by a booking
    @MockitoBean private OtpService otpService;
    @MockitoBean private AdmissionPricing admissionPricing;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private SeatReservationService seatReservation;

    private final List<Visitor> bookings = new ArrayList<>();

    @BeforeEach
    void openSlotAndRepository() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L)); // PHP 100 / 150
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(billingNumbers.next()).thenReturn("000321", "000322");
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(ID);
            bookings.add(v);
            when(repo.findById(ID)).thenReturn(Optional.of(v));
            when(repo.findByVisitDate(DAY)).thenReturn(List.of(v));
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByPaymentToken(anyString())).thenAnswer(inv -> bookings.stream()
                .filter(b -> inv.getArgument(0).equals(b.getPaymentToken())).findFirst());
    }

    @AfterEach
    void removeFiles() throws Exception {
        for (Visitor v : bookings) {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + v.getId() + ".png"));
            if (v.getIdCardImageUrl() != null) Files.deleteIfExists(Paths.get("uploads", v.getIdCardImageUrl()));
            if (v.getReceiptImageUrl() != null) Files.deleteIfExists(Paths.get("uploads", v.getReceiptImageUrl()));
            for (String earlier : v.earlierReceiptList()) Files.deleteIfExists(Paths.get("uploads", earlier));
        }
    }

    private static MockMultipartHttpServletRequestBuilder booking(String regular, String free) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id-card.jpg", "image/jpeg", ID_PHOTO));
        req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL).param("contactNumber", "09171234567")
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW)
           .param("discountedCount", "0").param("regularCount", regular).param("freeCount", free).param("termsAccepted", "true");
        return req;
    }

    private Visitor booked() {
        assertThat(bookings).hasSize(1);
        return bookings.get(0);
    }

    private org.springframework.test.web.servlet.ResultActions upload(Visitor v, String name, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile("receipt", name, name.endsWith(".png") ? "image/png" : "image/jpeg", bytes);
        return mvc.perform(multipart("/payment/proof").file(file).param("token", v.getPaymentToken()).with(csrf()));
    }

    // --- Booking: pays first, seats held from /save ---

    @Test
    void aPaidBookingGetsItsBillingNumberAndPaymentPageAtOnceAndHoldsItsSeats() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        MvcResult result = mvc.perform(booking("2", "0")).andExpect(redirectedUrl("/manage-booking/payment")).andReturn();

        Visitor v = booked();
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(v.holdsSeat()).isTrue();
        assertThat(v.inPendingQueue()).isFalse();      // not in Pending Approval until the receipt is in
        assertThat(v.awaitsApproval()).isFalse();      // and not approvable
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getBillingNumber()).isEqualTo("000321");
        assertThat(v.getPaymentToken()).hasSize(36);
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(30_000L);
        // the Director's window (default 48 h), never past the visit start (3 days away)
        assertThat(v.getPaymentDueAt()).isAfter(before.plusHours(47)).isBefore(LocalDateTime.now().plusHours(48).plusMinutes(1));
        assertThat(v.getQrCodeUrl()).isNull();
        // Saved under the seat lock: the seats are held from submission
        ArgumentCaptor<Visitor> locked = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(locked.capture());
        assertThat(locked.getValue().getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        // The payment link is emailed; no "booking received" email, no bell until the receipt arrives
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRequest(notice.capture());
        assertThat(notice.getValue().billingNumber()).isEqualTo("000321");
        assertThat(notice.getValue().amount()).isEqualTo("PHP 300.00");
        assertThat(notice.getValue().approved()).isFalse();
        verify(emailService, never()).sendPendingNotification(anyString(), anyString(), anyString(), anyString());
        verify(notificationService, never()).bookingRequest(any());
        verify(otpService).consume(EMAIL);
        // 🪪 No KYC: nothing is sent to the AI service, no face data is kept
        verifyNoInteractions(aiService);
        assertThat(v.getFaceEncoding()).isNull();
        assertThat(v.getIdFaceCropUrl()).isNull();

        // The visitor lands on the payment page, signed into Manage Booking (no token in the URL)
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
        String page = mvc.perform(get("/manage-booking/payment").session(session))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("000321", "PHP 300.00", "Booking received, your seats are reserved.", "Submit Proof of Payment");
    }

    @Test
    void aFullSlotRefusesAPaidBookingWithoutUsingABillingNumber() throws Exception {
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenThrow(new SeatReservationService.CapacityFullException());

        mvc.perform(booking("2", "0")).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", FULL));

        verify(billingNumbers, never()).next();
        verify(emailService, never()).sendPaymentRequest(any());
        verify(otpService, never()).consume(anyString());
    }

    @Test
    void theReceiptUploadPutsItInPendingApprovalWithNoNewSeatCheck() throws Exception {
        mvc.perform(booking("2", "0")).andExpect(redirectedUrl("/manage-booking/payment"));
        Visitor v = booked();

        upload(v, "receipt.jpg", TestImages.jpeg(8))
           .andExpect(flash().attribute("success", Matchers.containsString("will review your booking and payment")));

        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.getReceiptImageUrl()).matches("receipt-[0-9a-f-]{36}\\.jpg");
        assertThat(v.inPendingQueue()).isTrue();
        assertThat(v.holdsSeat()).isTrue();
        verify(seatReservation, times(1)).saveIfSeatsRemain(any()); // only at /save
        verify(notificationService).bookingRequest(v);             // the Director's bell rings now
    }

    @Test
    void approveMarksThePaymentPaidAndSendsTheQr() throws Exception {
        mvc.perform(booking("2", "0"));
        Visitor v = booked();
        upload(v, "receipt.jpg", TestImages.jpeg(8));
        LocalDateTime before = LocalDateTime.now();

        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR)).andExpect(flash().attributeExists("success"));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("director");
        assertThat(v.getPaymentVerifiedAt()).isBetween(before.minusSeconds(1), LocalDateTime.now());
        assertThat(v.getQrCodeUrl()).isEqualTo("QR_" + ID + ".png");
        assertThat(v.inPendingQueue()).isFalse();
        verify(emailService).sendApprovalEmailWithQR(eq(EMAIL), any(), any(), eq("QR_" + ID + ".png"), any(), any());
    }

    @Test
    void rejectingTheReceiptKeepsTheSeatsGivesAShortDeadlineAndAReUploadBringsItBack() throws Exception {
        mvc.perform(booking("2", "0"));
        Visitor v = booked();
        LocalDateTime originalDue = v.getPaymentDueAt();
        upload(v, "first.png", TestImages.png(3));
        String first = v.getReceiptImageUrl();

        mvc.perform(post("/admin/payments/" + ID + "/reject").with(csrf()).with(DIRECTOR).param("reason", "The amount does not match."))
           .andExpect(redirectedUrl("/admin?filter=pending"))
           .andExpect(flash().attribute("success", Matchers.containsString("may upload a new receipt until")));

        assertThat(v.getStatus()).isEqualTo("PENDING");          // the booking stays, its seats too
        assertThat(v.holdsSeat()).isTrue();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_REJECTED);
        assertThat(v.inPendingQueue()).isFalse();               // out of the tab until a new receipt arrives
        // At least 24 more hours, never shorter than the deadline it already had, never past the visit start
        assertThat(v.getPaymentDueAt()).isAfter(LocalDateTime.now().plusHours(23)).isEqualTo(originalDue)
            .isBefore(DAY.atTime(8, 0).plusSeconds(1));
        LocalDateTime soon = LocalDateTime.now();
        Visitor lateReject = new Visitor();
        lateReject.setVisitDate(DAY);
        lateReject.setTimeWindow(WINDOW);
        lateReject.setPaymentDueAt(soon.plusHours(2)); // only 2 hours were left: the rejection gives 24
        assertThat(PaymentWorkflow.deadlineAfterRejection(lateReject, soon)).isEqualTo(soon.plusHours(24));
        lateReject.setVisitDate(soon.toLocalDate().plusDays(1)); // the visit starts at 8:00 AM tomorrow
        assertThat(PaymentWorkflow.deadlineAfterRejection(lateReject, soon)).isBeforeOrEqualTo(soon.toLocalDate().plusDays(1).atTime(8, 0));
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRejected(notice.capture());
        assertThat(notice.getValue().reason()).isEqualTo("The amount does not match.");
        assertThat(notice.getValue().deadline()).isEqualTo(PaymentWorkflow.deadlineText(v.getPaymentDueAt()));
        // Approve is refused while the receipt is rejected
        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("error", GuardOperationController.RECEIPT_REJECTED_APPROVAL));

        upload(v, "second.jpg", TestImages.jpeg(8)).andExpect(flash().attributeExists("success"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.inPendingQueue()).isTrue();
        assertThat(Paths.get("uploads", first)).exists();       // a receipt is proof of payment: never deleted
        assertThat(v.earlierReceiptList()).containsExactly(first);
        verify(seatReservation, times(1)).saveIfSeatsRemain(any());
    }

    @Test
    void theRejectionEmailStatesTheNewDeadlineAndThatTheSeatsAreReleasedAfterIt() throws Exception {
        String template = new String(getClass().getResourceAsStream("/templates/email/payment-rejected.html").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(template).contains("New deadline: upload a new receipt by <span th:text=\"${deadline}\">",
                "or your booking will expire and its seats will be released.");
    }

    // --- Free bookings: straight to Pending Approval, seats held ---

    @Test
    void aFreeBookingGoesStraightToPendingApprovalAndHoldsItsSeats() throws Exception {
        mvc.perform(booking("0", "3")).andExpect(redirectedUrl("/success-page"));

        Visitor v = booked();
        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_NOT_REQUIRED);
        assertThat(v.getBillingNumber()).isNull();
        assertThat(v.getPaymentToken()).isNull();
        assertThat(v.holdsSeat()).isTrue();
        assertThat(v.inPendingQueue()).isTrue();
        verify(seatReservation).saveIfSeatsRemain(v);
        verify(notificationService).bookingRequest(v);
        verify(emailService).sendPendingNotification(eq(EMAIL), any(), any(), any());
        verify(emailService, never()).sendPaymentRequest(any());
        verify(billingNumbers, never()).next();
        verifyNoInteractions(aiService);

        // Approve sends the QR at once: nothing to pay
        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("success", GuardOperationController.APPROVED_MESSAGE));
        assertThat(v.getQrCodeUrl()).isEqualTo("QR_" + ID + ".png");
    }

    @Test
    void aFullSlotRefusesAFreeBookingToo() throws Exception {
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenThrow(new SeatReservationService.CapacityFullException());

        mvc.perform(booking("0", "3")).andExpect(redirectedUrl("/")).andExpect(flash().attribute("error", FULL));

        verify(notificationService, never()).bookingRequest(any());
        verify(emailService, never()).sendPendingNotification(anyString(), anyString(), anyString(), anyString());
    }
}
