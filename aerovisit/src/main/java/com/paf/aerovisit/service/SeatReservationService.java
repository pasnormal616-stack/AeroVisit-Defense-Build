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

    private final BookingSlotRepository bookingRepo;
    private final VisitorRepository repo;

    public SeatReservationService(BookingSlotRepository bookingRepo, VisitorRepository repo) {
        this.bookingRepo = bookingRepo;
        this.repo = repo;
    }

    // Saves the booking only if its group still fits its slot, counting seat-holding bookings (APPROVED / CHECKED-IN /
    // CHECKED-OUT) other than this one, the same rule as the controllers' checks. The lock is taken first, so the
    // count that follows is read after any booking that held the lock before us has committed.
    @Transactional
    public Visitor saveIfSeatsRemain(Visitor visitor) {
        List<BookingSlot> slots = bookingRepo.lockBySlotDateAndTimeWindow(visitor.getVisitDate(), visitor.getTimeWindow());
        int capacity = slots.stream().mapToInt(BookingSlot::getMaxCapacity).sum();
        Long held = repo.sumSeatsHeld(visitor.getVisitDate(), visitor.getTimeWindow(), Visitor.SEAT_HOLDING_STATUSES, visitor.getId());
        if ((held == null ? 0 : held) + Math.max(1, visitor.getGroupSize()) > capacity) {
            throw new CapacityFullException();
        }
        return repo.save(visitor);
    }
}
