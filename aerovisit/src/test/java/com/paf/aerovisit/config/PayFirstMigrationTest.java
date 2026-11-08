package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PaymentWorkflow;

/**
 * 💳 The one-time move of old pending bookings to pay first is production-safe: by default it only reports (counts and
 * Reference IDs) and changes / sends nothing; with apply on it moves the paid ones to AWAITING_RECEIPT with the payment
 * email; a second run finds nothing. No database.
 */
class PayFirstMigrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 9, 0);
    private static final String AM = "Morning (08:00 AM - 12:00 PM)";

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final BookingSlotRepository slots = mock(BookingSlotRepository.class);
    private final PaymentWorkflow payments = mock(PaymentWorkflow.class);
    private final EmailService email = mock(EmailService.class);
    private final List<Visitor> rows = new ArrayList<>();
    private PayFirstMigration migration;

    private Visitor row(long id, String status, LocalDate date, long total) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setReferenceId("PAF-OLD" + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(AM);
        v.setGroupSize(2);
        v.setDiscountedCount(0);
        v.setRegularCount(total > 0 ? 2 : 0);
        v.setFreeCount(total > 0 ? 0 : 2);
        v.setAdmissionTotalCentavos(total);
        rows.add(v);
        return v;
    }

    @BeforeEach
    void setUp() {
        migration = new PayFirstMigration(repo, slots);
        ReflectionTestUtils.setField(migration, "paymentWorkflow", payments);
        ReflectionTestUtils.setField(migration, "emailService", email);
        when(repo.findByStatusIn(any())).thenAnswer(inv -> new ArrayList<>(rows));
        when(repo.findByVisitDate(any())).thenAnswer(inv -> rows.stream().filter(v -> inv.getArgument(0).equals(v.getVisitDate())).toList());
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(NOW.toLocalDate().plusDays(4));
        slot.setTimeWindow(AM);
        slot.setMaxCapacity(3);
        when(slots.findAll()).thenReturn(List.of(slot));
        doAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            v.setBillingNumber("000900");
            v.setPaymentStatus(Visitor.PAY_AWAITING);
            v.setPaymentDueAt(NOW.plusHours(48));
            v.setPaymentToken("tok-" + v.getId());
            return null;
        }).when(payments).onAdvanceBooking(any(), any());
    }

    private void oldRows() {
        row(1, "PENDING", NOW.toLocalDate().plusDays(4), 30_000L);              // paid, upcoming: moves
        row(2, "PENDING_MANUAL_REVIEW", NOW.toLocalDate().plusDays(4), 0L);     // free: holds seats now, no change
        Visitor withReceipt = row(3, "VERIFIED", NOW.toLocalDate().plusDays(9), 30_000L);
        withReceipt.setReceiptImageUrl("receipt-3.jpg");                         // a receipt is in: stays in Pending
        row(4, "PENDING", NOW.toLocalDate().minusDays(1), 30_000L);             // past: left to the nightly sweep
        row(5, "APPROVED", NOW.toLocalDate().plusDays(4), 30_000L);             // not waiting for approval
    }

    @Test
    void byDefaultItOnlyReportsAndChangesNothing() {
        oldRows();
        assertThat(ReflectionTestUtils.getField(migration, "apply")).isEqualTo(false);

        PayFirstMigration.Result r = migration.run(NOW);

        assertThat(r.applied()).isFalse();
        assertThat(r.paidMoved()).containsExactly("PAF-OLD1");
        assertThat(r.freeHolding()).containsExactly("PAF-OLD2");
        assertThat(r.overCapacity()).containsExactly(NOW.toLocalDate().plusDays(4) + " " + AM + " 6/3"); // 2 paid + 2 free + 2 approved held for 3 seats
        assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
        verify(payments, never()).onAdvanceBooking(any(), any());
        verify(email, never()).sendPaymentRequest(any());
    }

    @Test
    void withApplyOnThePaidOnesMoveToPayFirstWithThePaymentEmailAndASecondRunFindsNothing() {
        oldRows();
        ReflectionTestUtils.setField(migration, "apply", true);

        PayFirstMigration.Result r = migration.run(NOW);

        assertThat(r.applied()).isTrue();
        assertThat(r.paidMoved()).containsExactly("PAF-OLD1");
        Visitor moved = rows.get(0);
        assertThat(moved.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(moved.getBillingNumber()).isEqualTo("000900");
        assertThat(moved.holdsSeat()).isTrue();
        verify(repo, times(1)).save(moved);
        verify(email, times(1)).sendPaymentRequest(any());
        // the others are untouched
        assertThat(rows.get(1).getStatus()).isEqualTo("PENDING_MANUAL_REVIEW");
        assertThat(rows.get(2).getStatus()).isEqualTo("VERIFIED");
        assertThat(rows.get(2).getReceiptImageUrl()).isEqualTo("receipt-3.jpg");
        assertThat(rows.get(3).getStatus()).isEqualTo("PENDING");

        // Idempotent: run again, nothing more is moved or emailed
        PayFirstMigration.Result again = migration.run(NOW);
        assertThat(again.paidMoved()).isEmpty();
        verify(email, times(1)).sendPaymentRequest(any());
        verify(payments, times(1)).onAdvanceBooking(any(), any());
    }

    @Test
    void withNoOldPendingBookingsThereIsNothingToDo() {
        PayFirstMigration.Result r = migration.run(NOW);
        assertThat(r.paidMoved()).isEmpty();
        assertThat(r.freeHolding()).isEmpty();
        assertThat(r.overCapacity()).isEmpty();
    }

    @Test
    void aFailureAtStartUpIsOnlyLogged() {
        when(repo.findByStatusIn(any())).thenThrow(new RuntimeException("db down"));
        migration.onStartup(); // never throws: the app starts either way
    }
}
