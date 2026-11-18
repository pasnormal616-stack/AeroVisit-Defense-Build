package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 🔒 Security fix M4: a booking changed by someone else between "read" and "save" is never overwritten. The sweeps write
 * through conditional UPDATEs (0 rows = the booking moved on: no email, no bell), and staff/visitor saves go through
 * VisitorRowGuard / SeatReservationService, which lock the row and compare it with what the request read. No database.
 */
class StaleWriteRaceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private static Visitor booking(long id, String status, String paymentStatus) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus(status);
        v.setVisitDate(LocalDate.of(2026, 11, 6));
        v.setTimeWindow(MORNING);
        v.setGroupSize(1);
        v.setAdmissionTotalCentavos(15_000L);
        v.setBillingNumber(String.format("%06d", id));
        v.setPaymentStatus(paymentStatus);
        return v;
    }

    private static Visitor copyOf(Visitor v) {
        Visitor c = booking(v.getId(), v.getStatus(), v.getPaymentStatus());
        c.setVisitDate(v.getVisitDate());
        c.setTimeWindow(v.getTimeWindow());
        c.setReceiptImageUrl(v.getReceiptImageUrl());
        return c;
    }

    // --- Sweeps: a receipt or check-in that landed after the list was read wins ---

    @Test
    void thePaymentSweepSkipsABookingWhoseReceiptArrivedMeanwhile() {
        AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);
        engine.notificationService = notifications;
        Visitor overdue = booking(1, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING);
        overdue.setPaymentDueAt(NOW.minusMinutes(5));
        when(repo.findByStatusInAndPaymentStatusIn(any(), any())).thenReturn(List.of(overdue));
        when(repo.expireIfStillUnpaid(anyLong(), any(), any(), any())).thenReturn(0); // the database already has the receipt

        assertThat(engine.expireUnpaidBookings(NOW)).isZero();

        assertThat(overdue.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        verify(repo, never()).save(any());
        verify(emailService, never()).sendPaymentExpired(any());
        verifyNoInteractions(notifications);
    }

    @Test
    void theLateWalkInSweepSkipsAGroupTheGuardJustCheckedIn() {
        AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);
        Visitor late = booking(2, "APPROVED", Visitor.PAY_NOT_REQUIRED);
        late.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        late.setAdmissionTotalCentavos(0L);
        late.setCheckInDueAt(NOW.minusMinutes(1));
        when(repo.findByBookingCategoryAndStatusAndCheckInDueAtBefore(Visitor.CATEGORY_WALK_IN, "APPROVED", NOW)).thenReturn(List.of(late));
        when(repo.releaseIfStatus(2L, "APPROVED", "NO_SHOW")).thenReturn(0); // already CHECKED-IN in the database

        assertThat(engine.releaseLateWalkIns(NOW)).isZero();
        assertThat(late.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void theNoShowSweepSkipsALastMinuteCheckIn() {
        AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);
        Visitor missed = booking(3, "APPROVED", Visitor.PAY_PAID);
        missed.setVisitDate(NOW.toLocalDate().minusDays(1));
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(missed));
        when(repo.releaseIfStatus(3L, "APPROVED", "NO_SHOW")).thenReturn(0);

        assertThat(engine.markNoShows(NOW)).isZero();
        assertThat(missed.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    // --- VisitorRowGuard: lock, compare, then change and save ---

    @Test
    void theGuardRefusesWhenTheRowMovedOnAndRunsNothing() throws Exception {
        VisitorRowGuard guard = new VisitorRowGuard(repo);
        Visitor read = booking(4, "APPROVED", Visitor.PAY_AWAITING);
        VisitorRowGuard.Snapshot asRead = VisitorRowGuard.Snapshot.of(read);

        for (Visitor moved : List.of(
                withStatus(copyOf(read), Visitor.STATUS_PAYMENT_EXPIRED),           // the sweep expired it
                withReceipt(copyOf(read), "receipt-new.jpg"),                       // another tab uploaded first
                withDate(copyOf(read), read.getVisitDate().plusDays(1)))) {         // the visitor rescheduled
            when(repo.lockById(4L)).thenReturn(Optional.of(moved));
            AtomicBoolean ran = new AtomicBoolean();
            assertThat(guard.saveIfUnchanged(read, asRead, () -> ran.set(true))).isFalse();
            assertThat(ran).isFalse();
        }
        when(repo.lockById(4L)).thenReturn(Optional.empty()); // deleted meanwhile
        assertThat(guard.saveIfUnchanged(read, asRead, () -> {})).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void theGuardSavesWhenTheRowIsStillAsRead() throws Exception {
        VisitorRowGuard guard = new VisitorRowGuard(repo);
        Visitor read = booking(5, "APPROVED", Visitor.PAY_AWAITING);
        VisitorRowGuard.Snapshot asRead = VisitorRowGuard.Snapshot.of(read);
        when(repo.lockById(5L)).thenReturn(Optional.of(copyOf(read)));

        assertThat(guard.saveIfUnchanged(read, asRead, () -> read.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED))).isTrue();
        verify(repo).save(read);
        assertThat(read.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
    }

    // --- Seat-holding saves (Approve, Force Approve, reschedule): slot lock, then the visitor row ---

    @Test
    void anApprovalOfABookingMovedToAnotherDateIsRefused() {
        BookingSlotRepository bookingRepo = mock(BookingSlotRepository.class);
        SeatReservationService seats = new SeatReservationService(bookingRepo, repo);
        Visitor read = booking(6, "PENDING", Visitor.PAY_PROOF_SUBMITTED);
        VisitorRowGuard.Snapshot asRead = VisitorRowGuard.Snapshot.of(read);
        BookingSlot slot = new BookingSlot();
        slot.setMaxCapacity(50);
        when(bookingRepo.lockBySlotDateAndTimeWindow(read.getVisitDate(), MORNING)).thenReturn(List.of(slot));
        when(repo.lockById(6L)).thenReturn(Optional.of(withDate(copyOf(read), read.getVisitDate().plusDays(2))));

        read.setStatus("APPROVED");
        assertThatThrownBy(() -> seats.saveIfSeatsRemain(read, asRead)).isInstanceOf(VisitorRowGuard.StaleBookingException.class);
        verify(repo, never()).save(any());
    }

    private static Visitor withStatus(Visitor v, String status) { v.setStatus(status); return v; }
    private static Visitor withReceipt(Visitor v, String receipt) { v.setReceiptImageUrl(receipt); return v; }
    private static Visitor withDate(Visitor v, LocalDate date) { v.setVisitDate(date); return v; }
}
