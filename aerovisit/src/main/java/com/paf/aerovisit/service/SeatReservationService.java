package com.paf.aerovisit.service;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 🔒 RACE-SAFE CAPACITY: the controllers' seat checks read, then save, so two requests for the last seats could both
// pass (the 99/100 race). This re-runs the check and the save in one transaction while holding a row lock on the
// slot, so concurrent bookings for one date + time window take turns and the second one sees the first one's seats.
@Service
public class SeatReservationService {

    // Thrown (and the transaction rolled back) when the group no longer fits; the controllers turn it into the usual "full" message
    public static class CapacityFullException extends RuntimeException {
        public CapacityFullException() {
            super("Time slot is full");
        }
    }

    // Thrown (and the transaction rolled back) when the booking is no longer PAYMENT_EXPIRED: already reinstated by
    // another click or tab, deleted, or changed by a parallel action
    public static class NotReinstatableException extends RuntimeException {
        public NotReinstatableException() {
            super("Booking is no longer payment-expired");
        }
    }

    private final BookingSlotRepository bookingRepo;
    private final VisitorRepository repo;

    public SeatReservationService(BookingSlotRepository bookingRepo, VisitorRepository repo) {
        this.bookingRepo = bookingRepo;
        this.repo = repo;
    }

    // Saves the booking only if its group still fits its slot, counting seat-holding bookings (APPROVED / CHECKED-IN /
    // CHECKED-OUT) other than this one, the same rule as the controllers' checks. The lock is taken first, so the
    // count that follows is read after any booking that held the lock before us has committed. An existing booking that
    // carries the row its request read (Visitor.expectRow, security fix M4) is also checked as below.
    @Transactional
    public Visitor saveIfSeatsRemain(Visitor visitor) {
        VisitorRowGuard.Snapshot expected = visitor.expectedRow();
        visitor.expectRow(null); // used once
        return saveIfSeatsRemain(visitor, expected);
    }

    // 🔒 The same, for an EXISTING booking changed by a request that read it earlier (Approve, Force Approve, Manage
    // Booking reschedule; security fix M4): after the slot lock, the booking's own row is locked and must still be as
    // read (`expected`), else StaleBookingException and nothing is saved. Slot first, then visitor: the same order
    // everywhere, so two requests can't wait on each other.
    @Transactional
    public Visitor saveIfSeatsRemain(Visitor visitor, VisitorRowGuard.Snapshot expected) {
        List<BookingSlot> slots = bookingRepo.lockBySlotDateAndTimeWindow(visitor.getVisitDate(), visitor.getTimeWindow());
        if (expected != null && visitor.getId() != null
                && !expected.matches(repo.lockById(visitor.getId()).orElse(null))) {
            throw new VisitorRowGuard.StaleBookingException();
        }
        int capacity = slots.stream().mapToInt(BookingSlot::getMaxCapacity).sum();
        Long held = repo.sumSeatsHeld(visitor.getVisitDate(), visitor.getTimeWindow(), Visitor.SEAT_HOLDING_STATUSES, visitor.getId());
        if ((held == null ? 0 : held) + Math.max(1, visitor.getGroupSize()) > capacity) {
            throw new CapacityFullException();
        }
        return repo.save(visitor);
    }

    // ♻️ Reinstate (Director): saves a PAYMENT_EXPIRED booking as APPROVED in its (possibly new) date + time window, only
    // if it is STILL payment-expired (conditional UPDATE, so two clicks or a parallel action can't reinstate it twice)
    // and its group still fits. The slot lock comes first, as above; a full slot rolls the claim back too.
    @Transactional
    public Visitor reinstateIfSeatsRemain(Visitor visitor) {
        List<BookingSlot> slots = bookingRepo.lockBySlotDateAndTimeWindow(visitor.getVisitDate(), visitor.getTimeWindow());
        if (repo.claimStatus(visitor.getId(), Visitor.STATUS_PAYMENT_EXPIRED, "APPROVED") != 1) {
            throw new NotReinstatableException();
        }
        int capacity = slots.stream().mapToInt(BookingSlot::getMaxCapacity).sum();
        Long held = repo.sumSeatsHeld(visitor.getVisitDate(), visitor.getTimeWindow(), Visitor.SEAT_HOLDING_STATUSES, visitor.getId());
        if ((held == null ? 0 : held) + Math.max(1, visitor.getGroupSize()) > capacity) {
            throw new CapacityFullException();
        }
        visitor.setStatus("APPROVED");
        return repo.save(visitor);
    }
}
