package com.paf.aerovisit.service;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * ♻️ REINSTATE BOOKING (Director): a visitor paid at Landbank but the receipt missed the deadline, so the payment sweep
 * released the booking (PAYMENT_EXPIRED). The Director brings it back as PAID + APPROVED, in its original slot when that
 * is still in the future with seats, otherwise in another open slot the Director picks (the minimum-days-ahead rule
 * does not apply to the Director). The billing number never changes.
 *
 * This class answers "which slots are open" and prepares the booking; the save itself is race-safe in
 * SeatReservationService.reinstateIfSeatsRemain (only a booking still PAYMENT_EXPIRED, only if the group fits).
 */
@Service
public class BookingReinstatement {

    public static final int MAX_NOTE = 300;

    // One date + time window with enough seats for the booking's group
    public record Slot(LocalDate date, String timeWindow, int remaining) {}

    private final VisitorRepository repo;
    private final BookingSlotRepository bookingRepo;

    public BookingReinstatement(VisitorRepository repo, BookingSlotRepository bookingRepo) {
        this.repo = repo;
        this.bookingRepo = bookingRepo;
    }

    // Why the booking's own date + window can't take it back now ("is full", "has already passed", ...), or null
    public String originalProblem(Visitor v, LocalDateTime now) {
        if (v.getVisitDate() == null || v.getTimeWindow() == null) return "is not a bookable time slot";
        int capacity = bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), v.getTimeWindow()).stream()
                .mapToInt(BookingSlot::getMaxCapacity).sum();
        if (capacity <= 0) return "is no longer offered";
        LocalDateTime end = WalkInService.windowEnd(v);
        if (end == null || !end.isAfter(now)) return "has already passed";
        if (repo.findClosureDates().contains(v.getVisitDate())) return "is on a day the museum is closed";
        Long held = repo.sumSeatsHeld(v.getVisitDate(), v.getTimeWindow(), Visitor.SEAT_HOLDING_STATUSES, v.getId());
        if (capacity - (held == null ? 0 : held) < Math.max(1, v.getGroupSize())) return "is full";
        return null;
    }

    // Every slot from today on whose window hasn't ended, on a day the museum is open, with seats for the whole group.
    // Several slot rows for one date + window add up, as in the seat lock.
    public List<Slot> openSlots(Visitor v, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        Set<LocalDate> closed = new HashSet<>(repo.findClosureDates());
        Map<String, Integer> held = new HashMap<>();
        for (Object[] row : repo.sumSeatsHeldFrom(today, Visitor.SEAT_HOLDING_STATUSES)) {
            if (row[0] != null && row[1] != null) held.put(row[0] + "|" + row[1], ((Number) row[2]).intValue());
        }
        Map<String, Slot> capacity = new LinkedHashMap<>();
        for (BookingSlot s : bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(today)) {
            if (s.getSlotDate() == null || s.getTimeWindow() == null || s.getMaxCapacity() <= 0 || closed.contains(s.getSlotDate())) continue;
            LocalDateTime end = windowEnd(s.getSlotDate(), s.getTimeWindow());
            if (end == null || !end.isAfter(now)) continue;
            String key = s.getSlotDate() + "|" + s.getTimeWindow();
            Slot prev = capacity.get(key);
            capacity.put(key, new Slot(s.getSlotDate(), s.getTimeWindow(), (prev == null ? 0 : prev.remaining()) + s.getMaxCapacity()));
        }
        int group = Math.max(1, v.getGroupSize());
        List<Slot> open = new ArrayList<>();
        for (Map.Entry<String, Slot> e : capacity.entrySet()) {
            int remaining = e.getValue().remaining() - held.getOrDefault(e.getKey(), 0);
            if (remaining >= group) open.add(new Slot(e.getValue().date(), e.getValue().timeWindow(), remaining));
        }
        return open;
    }

    // The chosen slot, when it is one of the open ones (a hand-made request is rechecked), else null
    public Slot findOpen(Visitor v, LocalDate date, String timeWindow, LocalDateTime now) {
        return openSlots(v, now).stream()
                .filter(s -> s.date().equals(date) && s.timeWindow().equals(timeWindow))
                .findFirst().orElse(null);
    }

    // Dates on which the same email holds another active (seat-holding) booking: the visitor may have rebooked
    public Set<LocalDate> otherActiveDates(Visitor v) {
        Set<LocalDate> dates = new TreeSet<>();
        if (v.getEmail() == null || v.getEmail().isBlank()) return dates;
        for (Visitor o : repo.findByEmailIgnoreCase(v.getEmail().trim())) {
            if (!Objects.equals(o.getId(), v.getId()) && o.holdsSeat() && o.getVisitDate() != null) dates.add(o.getVisitDate());
        }
        return dates;
    }

    // Prepares the booking for the race-safe save (the status stays PAYMENT_EXPIRED here: the conditional UPDATE in
    // SeatReservationService moves it to APPROVED). A walk-in stays a walk-in only when it is reinstated for today;
    // moved to a later date it becomes an advance booking (and gets a QR code).
    public static void prepare(Visitor v, LocalDate date, String timeWindow, String note, String director, LocalDateTime now) {
        if (!Objects.equals(v.getVisitDate(), date) || !Objects.equals(v.getTimeWindow(), timeWindow)) {
            v.setReinstatedFrom(v.getVisitDate() + " · " + v.getTimeWindow());
            v.setVisitDate(date);
            v.setTimeWindow(timeWindow);
        }
        if (v.walkIn() && !now.toLocalDate().equals(date)) v.setBookingCategory(null);
        PaymentWorkflow.recordPaid(v, director, now, note);
        v.setReinstatedBy(director);
        v.setReinstatedAt(now);
        v.setReinstatementNote(note.trim());
        // 🚶 A paid walk-in may check in until its window ends (the 15-minute sweep releases it after that)
        v.setCheckInDueAt(v.walkIn() ? WalkInService.windowEnd(v) : null);
    }

    private static LocalDateTime windowEnd(LocalDate date, String timeWindow) {
        java.time.LocalTime[] window = com.paf.aerovisit.controller.GuardOperationController.parseTimeWindow(timeWindow);
        return window == null ? null : date.atTime(window[1]);
    }
}
