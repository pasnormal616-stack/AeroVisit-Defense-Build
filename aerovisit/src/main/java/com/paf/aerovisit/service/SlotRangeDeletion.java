package com.paf.aerovisit.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;

// 🗑️ DELETE SLOTS BY DATE RANGE (Slots & Staff, Director only): undoes a Bulk Slot Generator mistake. A slot is a date +
// time window, and that is how a booking points at it, so a slot with ANY booking on its date + window (any status:
// cancelled, rejected, expired and anonymized rows included) is never deleted: it is kept and reported with the reason.
// preview() only reads; delete() locks the range's slots (same id order as the booking seat lock), works the bookings out
// again (a booking made after the preview is still protected) and deletes only the clean slots.
@Service
public class SlotRangeDeletion {

    // One slot that is kept, and why
    public record Kept(LocalDate date, String timeWindow, long bookings, String reason) {}

    // What a preview found, or what a delete did: total slots in the range, the ones without bookings, the kept ones
    public record Result(LocalDate from, LocalDate to, int total, int deletable, List<Kept> kept) {}

    private final BookingSlotRepository slots;
    private final VisitorRepository visitors;

    public SlotRangeDeletion(BookingSlotRepository slots, VisitorRepository visitors) {
        this.slots = slots;
        this.visitors = visitors;
    }

    public Result preview(LocalDate from, LocalDate to) {
        List<BookingSlot> inRange = slots.findBySlotDateBetween(from, to);
        return sort(from, to, inRange, new ArrayList<>());
    }

    @Transactional
    public Result delete(LocalDate from, LocalDate to) {
        List<BookingSlot> inRange = slots.lockBySlotDateBetween(from, to);
        List<BookingSlot> clean = new ArrayList<>();
        Result result = sort(from, to, inRange, clean);
        if (!clean.isEmpty()) slots.deleteAllInBatch(clean);
        return result;
    }

    // Splits the range's slots into clean ones (added to `clean`) and kept ones (with their bookings, by status)
    private Result sort(LocalDate from, LocalDate to, List<BookingSlot> inRange, List<BookingSlot> clean) {
        // date|window -> status -> count, for every booking in the range (any status)
        Map<String, Map<String, Long>> booked = new HashMap<>();
        for (Object[] row : visitors.countByDateWindowAndStatus(from, to)) {
            String key = row[0] + "|" + row[1];
            String status = row[2] == null ? "UNKNOWN" : row[2].toString();
            booked.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(status, ((Number) row[3]).longValue(), Long::sum);
        }
        List<Kept> kept = new ArrayList<>();
        for (BookingSlot s : inRange) {
            Map<String, Long> byStatus = booked.get(s.getSlotDate() + "|" + s.getTimeWindow());
            if (byStatus == null) {
                clean.add(s);
            } else {
                long n = byStatus.values().stream().mapToLong(Long::longValue).sum();
                kept.add(new Kept(s.getSlotDate(), s.getTimeWindow(), n, reason(n, byStatus)));
            }
        }
        kept.sort(Comparator.comparing(Kept::date).thenComparing(k -> k.timeWindow() == null ? "" : k.timeWindow()));
        return new Result(from, to, inRange.size(), inRange.size() - kept.size(), kept);
    }

    // "Has 3 bookings (2 Approved, 1 Cancelled): deleting it would orphan them."
    static String reason(long n, Map<String, Long> byStatus) {
        List<String> parts = new ArrayList<>();
        byStatus.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> parts.add(e.getValue() + " " + statusName(e.getKey())));
        return "Has " + n + (n == 1 ? " booking" : " bookings") + " (" + String.join(", ", parts) + "): deleting it would orphan "
                + (n == 1 ? "it." : "them.");
    }

    // CANCELLED_BY_VISITOR -> "Cancelled by visitor", CHECKED-OUT -> "Checked-out"
    private static String statusName(String status) {
        String s = status.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
