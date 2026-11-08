package com.paf.aerovisit.service;

import com.paf.aerovisit.controller.GuardOperationController;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🚶 WALK-IN (TODAY) rules, in one place: people already at the museum book for today on the visitor portal, without a
 * selfie and without the Director's approval. Their seats are held at once (SeatReservationService), they pay within
 * minutes (Director setting, default 30), and the guard checks the ID in person and verifies the receipt.
 *
 * A window is offered only while it has seats left and at least MIN_MINUTES_LEFT minutes before its end, so nobody books
 * a window that closes before they reach the gate. /save enforces the same rule (windowError).
 */
@Service
public class WalkInService {

    public static final int MIN_MINUTES_LEFT = 20;
    public static final int MIN_MAX_PAX = 1, MAX_MAX_PAX = 100;
    public static final int MIN_MINUTES = 5, MAX_MINUTES = 240;

    static final String CLOSED = "The museum is closed today. Please book a visit for another day.";
    static final String NO_SLOTS = "There are no visit slots today. Please book a visit for another day.";
    static final String ENDED = "Today's last time window has ended. Please book a visit for another day.";
    static final String FULL = "Today is fully booked. Please book a visit for another day.";

    // One of today's time windows a walk-in may pick; current = preselected (the one under way, else the next)
    public record Window(String label, int remaining, boolean current) {}

    // reason is null when at least one window is offered
    public record Availability(List<Window> windows, String reason) {
        public boolean open() { return reason == null; }
    }

    private final BookingSlotRepository bookingRepo;
    private final VisitorRepository repo;

    // Optional, so test slices without a database still start; without it the defaults apply
    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    // 🎌 Holidays & Closed Days: no walk-ins on a listed holiday. Optional (test slices start without it: no holidays).
    @Autowired(required = false)
    Holidays holidays;

    // "Closed: Bonifacio Day. Please book a visit for another day." when today is a listed holiday, else null
    private String holidayReason(LocalDate today) {
        if (holidays == null) return null;
        return holidays.on(today).map(h -> Holidays.closedReason(h) + ". Please book a visit for another day.").orElse(null);
    }

    public WalkInService(BookingSlotRepository bookingRepo, VisitorRepository repo) {
        this.bookingRepo = bookingRepo;
        this.repo = repo;
    }

    // --- Settings (PortalSettings, Settings page) ---

    private PortalSettings settings() {
        if (settingsRepo == null) return new PortalSettings();
        try {
            return settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Walk-in settings could not be read; using the defaults. " + e.getMessage());
            return new PortalSettings();
        }
    }

    public int maxPax() { return settings().walkInMax(); }

    public int minutes() { return settings().walkInMinutes(); }

    // Saves the Director's walk-in settings (already validated)
    public void saveSettings(int maxPax, int minutes) {
        PortalSettings s = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        s.setWalkInMaxPax(maxPax);
        s.setWalkInPaymentMinutes(minutes);
        settingsRepo.save(s);
    }

    public static String settingsError(int maxPax, int minutes) {
        if (maxPax < MIN_MAX_PAX || maxPax > MAX_MAX_PAX) {
            return "The walk-in maximum must be a whole number of persons from " + MIN_MAX_PAX + " to " + MAX_MAX_PAX + ".";
        }
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            return "The walk-in payment time must be a whole number of minutes from " + MIN_MINUTES + " to " + MAX_MINUTES + ".";
        }
        return null;
    }

    public static String tooManyMessage(int maxPax) {
        return "Walk-in reservations must not exceed " + maxPax + " persons (pax).";
    }

    // --- Today's windows ---

    public Availability today(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (repo.findClosureDates().contains(today)) return new Availability(List.of(), CLOSED);
        String holiday = holidayReason(today);
        if (holiday != null) return new Availability(List.of(), holiday);

        // Capacity per window label (two slots with the same label add up, as in /save)
        Map<String, Integer> capacity = new LinkedHashMap<>();
        for (BookingSlot s : bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(today)) {
            if (today.equals(s.getSlotDate()) && s.getTimeWindow() != null) capacity.merge(s.getTimeWindow(), s.getMaxCapacity(), Integer::sum);
        }
        if (capacity.isEmpty()) return new Availability(List.of(), NO_SLOTS);

        record Open(String label, LocalTime start, int remaining) {}
        List<Open> open = new ArrayList<>();
        boolean anyTimeLeft = false;
        for (Map.Entry<String, Integer> e : capacity.entrySet()) {
            LocalTime[] window = GuardOperationController.parseTimeWindow(e.getKey());
            if (window == null || !enoughTimeLeft(window, now.toLocalTime())) continue;
            anyTimeLeft = true;
            Long held = repo.sumSeatsHeld(today, e.getKey(), Visitor.SEAT_HOLDING_STATUSES, null);
            int remaining = e.getValue() - (held == null ? 0 : held.intValue());
            if (remaining > 0) open.add(new Open(e.getKey(), window[0], remaining));
        }
        if (!anyTimeLeft) return new Availability(List.of(), ENDED);
        if (open.isEmpty()) return new Availability(List.of(), FULL);

        open.sort(Comparator.comparing(Open::start));
        // Preselect the window under way (it has 20+ minutes left, or it would not be offered), else the next one
        LocalTime t = now.toLocalTime();
        int current = 0;
        for (int i = 0; i < open.size(); i++) {
            if (!open.get(i).start().isAfter(t)) { current = i; break; }
        }
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) windows.add(new Window(open.get(i).label(), open.get(i).remaining(), i == current));
        return new Availability(windows, null);
    }

    // At least MIN_MINUTES_LEFT minutes before the window's end time
    static boolean enoughTimeLeft(LocalTime[] window, LocalTime now) {
        return !now.isAfter(window[1].minusMinutes(MIN_MINUTES_LEFT));
    }

    // Why a walk-in can't book this window of today, or null. The capacity check stays in /save (and the seat lock).
    public String windowError(String timeWindow, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        String holiday = holidayReason(today);
        if (holiday != null) return holiday;
        boolean todaySlot = timeWindow != null && bookingRepo.findBySlotDateAndTimeWindow(today, timeWindow).stream()
                .anyMatch(s -> s.getMaxCapacity() > 0);
        LocalTime[] window = GuardOperationController.parseTimeWindow(timeWindow);
        if (!todaySlot || window == null) return "Please choose one of today's open time windows.";
        if (now.toLocalTime().isAfter(window[1])) {
            return "That time window has already ended. Please choose a later window today or book in advance.";
        }
        if (!enoughTimeLeft(window, now.toLocalTime())) {
            return "That time window ends in less than " + MIN_MINUTES_LEFT + " minutes. Please choose a later window today or book in advance.";
        }
        return null;
    }

    // --- Deadlines ---

    // The end of the booked window today, or null when the label can't be read
    public static LocalDateTime windowEnd(Visitor v) {
        LocalTime[] window = GuardOperationController.parseTimeWindow(v.getTimeWindow());
        return v.getVisitDate() == null || window == null ? null : v.getVisitDate().atTime(window[1]);
    }

    private static LocalDateTime windowStart(Visitor v) {
        LocalTime[] window = GuardOperationController.parseTimeWindow(v.getTimeWindow());
        return v.getVisitDate() == null || window == null ? null : v.getVisitDate().atTime(window[0]);
    }

    private static LocalDateTime capAtWindowEnd(Visitor v, LocalDateTime t) {
        LocalDateTime end = windowEnd(v);
        return end != null && end.isBefore(t) ? end : t;
    }

    // Pay by the EARLIER of now + the walk-in minutes and the end of the booked window
    public static LocalDateTime paymentDeadline(Visitor v, LocalDateTime now, int minutes) {
        return capAtWindowEnd(v, now.plusMinutes(minutes));
    }

    // After a rejected receipt: at least the walk-in minutes again from now, never past the end of the window
    public static LocalDateTime deadlineAfterRejection(Visitor v, LocalDateTime now, int minutes) {
        LocalDateTime extended = now.plusMinutes(minutes);
        if (v.getPaymentDueAt() != null && v.getPaymentDueAt().isAfter(extended)) extended = v.getPaymentDueAt();
        return capAtWindowEnd(v, extended);
    }

    // When a walk-in that isn't checked in is released. Free: the walk-in minutes after booking (counted from the window's
    // start when booked for a later window, so the gate is open for them), never past the window's end. Paid: the
    // window's end (paying takes time; an unpaid one is released earlier by its payment deadline).
    public static LocalDateTime checkInDeadline(Visitor v, LocalDateTime now, int minutes) {
        LocalDateTime end = windowEnd(v);
        if (v.paymentRequired()) return end;
        LocalDateTime start = windowStart(v);
        LocalDateTime from = start != null && start.isAfter(now) ? start : now;
        return capAtWindowEnd(v, from.plusMinutes(minutes));
    }
}
