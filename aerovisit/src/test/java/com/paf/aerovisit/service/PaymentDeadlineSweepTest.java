package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/** 💳 The payment deadline sweep releases overdue unpaid bookings (their seats free up); a submitted receipt is never expired. */
class PaymentDeadlineSweepTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);

    private static Visitor approved(long id, String paymentStatus, LocalDateTime due) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus("APPROVED");
        v.setVisitDate(LocalDate.of(2026, 11, 6));
        v.setAdmissionTotalCentavos(15_000L);
        v.setBillingNumber(String.format("%06d", id));
        v.setPaymentStatus(paymentStatus);
        v.setPaymentDueAt(due);
        return v;
    }

    @Test
    void overdueUnpaidBookingsAreReleasedAndOthersAreUntouched() {
        engine.notificationService = notifications;
        Visitor overdue = approved(1, Visitor.PAY_AWAITING, NOW.minusMinutes(1));
        Visitor rejectedOverdue = approved(2, Visitor.PAY_REJECTED, NOW.minusHours(2));
        Visitor notYetDue = approved(3, Visitor.PAY_AWAITING, NOW.plusMinutes(5));
        Visitor proofSubmitted = approved(4, Visitor.PAY_PROOF_SUBMITTED, NOW.minusDays(1));
        when(repo.findByStatusInAndPaymentStatusIn(any(), any()))
                .thenReturn(List.of(overdue, rejectedOverdue, notYetDue, proofSubmitted));

        assertThat(engine.expireUnpaidBookings(NOW)).isEqualTo(2);

        for (Visitor v : List.of(overdue, rejectedOverdue)) {
            assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
            assertThat(v.holdsSeat()).isFalse(); // the seats free up
        }
        assertThat(notYetDue.getStatus()).isEqualTo("APPROVED");
        assertThat(proofSubmitted.getStatus()).isEqualTo("APPROVED"); // the Director decides, never the sweep
        verify(emailService, times(2)).sendPaymentExpired(any());
        verify(notifications).paymentExpired(overdue);
        verify(notifications).paymentExpired(rejectedOverdue);
    }

    // --- 💳 Pay first: an advance booking holds its seats until its receipt deadline ---

    private static Visitor payFirst(long id, String status, String paymentStatus, LocalDateTime due) {
        Visitor v = approved(id, paymentStatus, due);
        v.setStatus(status);
        return v;
    }

    @Test
    void aPayFirstBookingWithNoReceiptByTheDeadlineExpiresAndFreesItsSeats() {
        engine.notificationService = notifications;
        Visitor noReceipt = payFirst(11, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, NOW.minusMinutes(1));
        Visitor rejectedNoNewOne = payFirst(12, "PENDING", Visitor.PAY_REJECTED, NOW.minusMinutes(1));
        Visitor stillTime = payFirst(13, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, NOW.plusHours(3));
        Visitor receiptIn = payFirst(14, "PENDING", Visitor.PAY_PROOF_SUBMITTED, NOW.minusDays(1));
        for (Visitor v : List.of(noReceipt, rejectedNoNewOne, stillTime, receiptIn)) assertThat(v.holdsSeat()).isTrue();
        when(repo.findByStatusInAndPaymentStatusIn(any(), any())).thenReturn(List.of(noReceipt, rejectedNoNewOne, stillTime, receiptIn));

        assertThat(engine.expireUnpaidBookings(NOW)).isEqualTo(2);

        for (Visitor v : List.of(noReceipt, rejectedNoNewOne)) {
            assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
            assertThat(v.holdsSeat()).isFalse(); // this is what frees an unused booking's seats
        }
        assertThat(stillTime.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(receiptIn.getStatus()).isEqualTo("PENDING"); // a receipt is in: the Director decides, never the sweep
        verify(notifications).paymentExpired(noReceipt);
        verify(notifications).paymentExpired(rejectedNoNewOne);
    }

    @Test
    void theSweepAsksOnlyForBookingsThatCanStillExpire() {
        when(repo.findByStatusInAndPaymentStatusIn(any(), any())).thenReturn(List.of());
        engine.expireUnpaidBookings(NOW);
        verify(repo).findByStatusInAndPaymentStatusIn(
                org.mockito.ArgumentMatchers.argThat(c -> c.containsAll(List.of(Visitor.STATUS_AWAITING_RECEIPT, "APPROVED", "PENDING"))
                        && !c.contains("CHECKED-IN") && !c.contains("CHECKED-OUT")),
                org.mockito.ArgumentMatchers.argThat(c -> c.containsAll(List.of(Visitor.PAY_AWAITING, Visitor.PAY_REJECTED))
                        && !c.contains(Visitor.PAY_PROOF_SUBMITTED) && !c.contains(Visitor.PAY_PAID)));
    }

    // --- 🚶 Walk-ins ---

    private static Visitor walkIn(long id, long total, String paymentStatus, LocalDateTime checkInDue) {
        Visitor v = approved(id, paymentStatus, null);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(NOW.toLocalDate());
        v.setAdmissionTotalCentavos(total);
        v.setCheckInDueAt(checkInDue);
        return v;
    }

    @Test
    void anUnpaidWalkInIsReleasedAtItsPaymentDeadlineLikeAnyBooking() {
        engine.notificationService = notifications;
        Visitor unpaid = walkIn(6, 15_000L, Visitor.PAY_AWAITING, NOW.plusHours(2));
        unpaid.setPaymentDueAt(NOW.minusMinutes(1));
        when(repo.findByStatusInAndPaymentStatusIn(any(), any())).thenReturn(List.of(unpaid));

        assertThat(engine.expireUnpaidBookings(NOW)).isEqualTo(1);
        assertThat(unpaid.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertThat(unpaid.holdsSeat()).isFalse();
    }

    @Test
    void aWalkInNotCheckedInByItsDeadlineIsReleasedWithinFifteenMinutes() {
        // free: its check-in deadline (booking + walk-in minutes) passed
        Visitor lateFree = walkIn(7, 0L, Visitor.PAY_NOT_REQUIRED, NOW.minusMinutes(1));
        // paid: its deadline is the window's end, still ahead
        Visitor paidWaiting = walkIn(8, 15_000L, Visitor.PAY_PAID, NOW.plusHours(1));
        // checked in already: never touched
        Visitor inside = walkIn(9, 0L, Visitor.PAY_NOT_REQUIRED, NOW.minusHours(1));
        inside.setStatus("CHECKED-IN");
        // an advance booking from the same query (cannot happen, but the sweep re-checks): left to the nightly sweep
        Visitor advance = approved(10, Visitor.PAY_PAID, null);
        advance.setCheckInDueAt(NOW.minusHours(1));
        when(repo.findByBookingCategoryAndStatusAndCheckInDueAtBefore(eq(Visitor.CATEGORY_WALK_IN), eq("APPROVED"), eq(NOW)))
                .thenReturn(List.of(lateFree, paidWaiting, inside, advance));

        assertThat(engine.releaseLateWalkIns(NOW)).isEqualTo(1);
        assertThat(lateFree.getStatus()).isEqualTo("NO_SHOW");
        assertThat(lateFree.holdsSeat()).isFalse();
        assertThat(paidWaiting.getStatus()).isEqualTo("APPROVED");
        assertThat(inside.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(advance.getStatus()).isEqualTo("APPROVED");

        // the paid one is released once its window has ended
        when(repo.findByBookingCategoryAndStatusAndCheckInDueAtBefore(eq(Visitor.CATEGORY_WALK_IN), eq("APPROVED"), eq(NOW.plusHours(2))))
                .thenReturn(List.of(paidWaiting));
        assertThat(engine.releaseLateWalkIns(NOW.plusHours(2))).isEqualTo(1);
        assertThat(paidWaiting.getStatus()).isEqualTo("NO_SHOW");
    }

    @Test
    void theDeadlineIsTheEarlierOfTheWindowAndTheStartOfTheVisit() {
        Visitor v = approved(5, Visitor.PAY_AWAITING, null);
        v.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        v.setVisitDate(LocalDate.of(2026, 11, 4)); // starts tomorrow 8:00 AM, 22 hours away
        assertThat(PaymentWorkflow.deadline(v, NOW, 48)).isEqualTo(LocalDateTime.of(2026, 11, 4, 8, 0));
        assertThat(PaymentWorkflow.deadline(v, NOW, 2)).isEqualTo(NOW.plusHours(2));
    }

    // ♻️ The "Booking Released" email names the museum contact email from Settings (and replies go there), for advance
    // bookings and walk-ins alike
    @Test
    void theReleasedEmailCarriesTheSettingsContactEmailAndBillingNumber() {
        PaymentWorkflow workflow = mock(PaymentWorkflow.class);
        when(workflow.contactEmail()).thenReturn("museum.contact@example.com");
        engine.paymentWorkflow = workflow;
        Visitor advance = approved(21, Visitor.PAY_AWAITING, NOW.minusMinutes(1));
        Visitor walkIn = approved(22, Visitor.PAY_AWAITING, NOW.minusMinutes(1));
        walkIn.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        when(repo.findByStatusInAndPaymentStatusIn(any(), any())).thenReturn(List.of(advance, walkIn));

        assertThat(engine.expireUnpaidBookings(NOW)).isEqualTo(2);

        org.mockito.ArgumentCaptor<EmailService.PaymentNotice> notices = org.mockito.ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService, times(2)).sendPaymentExpired(notices.capture());
        assertThat(notices.getAllValues()).extracting(EmailService.PaymentNotice::contactEmail)
                .containsOnly("museum.contact@example.com");
        assertThat(notices.getAllValues()).extracting(EmailService.PaymentNotice::billingNumber).containsExactly("000021", "000022");
        assertThat(notices.getAllValues()).extracting(EmailService.PaymentNotice::walkIn).containsExactly(false, true);
    }
}
