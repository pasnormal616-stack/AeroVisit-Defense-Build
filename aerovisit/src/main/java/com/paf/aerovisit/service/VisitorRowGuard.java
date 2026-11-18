package com.paf.aerovisit.service;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 🔒 STALE-WRITE GUARD (security fix M4). Visitor has no @Version, and saving a booking writes every column from the copy
 * loaded at the start of the request. Between that load and the save, someone else may have changed the booking: the
 * payment sweep expired it, the visitor uploaded a new receipt or moved the date, a guard checked the group in. The
 * whole-row save then silently undid that change (a paid booking revived after its seats were released, a receipt
 * reference reverted, a new date put back).
 *
 * saveIfUnchanged locks the booking's row, compares it with the Snapshot taken when the request loaded it, and only then
 * runs the change and saves; otherwise nothing is written and the caller tells the user to reload. Sweeps use
 * conditional UPDATEs instead (VisitorRepository.releaseIfStatus / expireIfStillUnpaid).
 */
@Service
public class VisitorRowGuard {

    public static final String CHANGED_MESSAGE = "This booking was changed a moment ago (for example its payment deadline passed, "
            + "or a new receipt or date arrived). Nothing was saved. Please reload the page and try again.";

    /** What a request relied on when it loaded the booking. */
    public record Snapshot(String status, String paymentStatus, String receiptImageUrl, LocalDate visitDate, String timeWindow) {
        public static Snapshot of(Visitor v) {
            return new Snapshot(v.getStatus(), v.getPaymentStatus(), v.getReceiptImageUrl(), v.getVisitDate(), v.getTimeWindow());
        }

        public boolean matches(Visitor fresh) {
            return fresh != null && Objects.equals(status, fresh.getStatus()) && Objects.equals(paymentStatus, fresh.getPaymentStatus())
                    && Objects.equals(receiptImageUrl, fresh.getReceiptImageUrl()) && Objects.equals(visitDate, fresh.getVisitDate())
                    && Objects.equals(timeWindow, fresh.getTimeWindow());
        }
    }

    /** Thrown inside a locked transaction (SeatReservationService) when the booking moved on; rolls it back. */
    public static class StaleBookingException extends RuntimeException {
        public StaleBookingException() {
            super("Booking changed since it was read");
        }
    }

    /** The checked work run under the lock (e.g. storing the receipt file); its exception rolls the transaction back. */
    @FunctionalInterface
    public interface Change {
        void apply() throws Exception;
    }

    private final VisitorRepository repo;

    public VisitorRowGuard(VisitorRepository repo) {
        this.repo = repo;
    }

    /**
     * Locks the row; if it still matches `expected`, runs `change` (which edits `v`) and saves `v`. False = the booking
     * moved on (or is gone): nothing ran, nothing was saved. An exception from `change` is passed on, nothing saved.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean saveIfUnchanged(Visitor v, Snapshot expected, Change change) throws Exception {
        if (!stillAsRead(v.getId(), expected)) return false;
        change.apply();
        repo.save(v);
        return true;
    }

    /** Inside the caller's transaction: locks the row and checks it still matches (SeatReservationService). */
    public boolean stillAsRead(Long id, Snapshot expected) {
        if (id == null || expected == null) return true; // a new booking: nothing to compare with
        return expected.matches(repo.lockById(id).orElse(null));
    }
}
