package com.paf.aerovisit.service;

import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.repository.HolidayRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 🎌 Holidays & Closed Days (Slots & Staff): which dates are listed, in one place. The booking page, /save, walk-ins,
 * Manage Booking and the Bulk Slot Generator all ask here. The callers hold it as an optional bean, so web test slices
 * without a database start without it (no holidays).
 */
@Service
public class Holidays {

    public static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    public static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final HolidayRepository repo;

    public Holidays(HolidayRepository repo) {
        this.repo = repo;
    }

    public List<Holiday> all() {
        return repo.findAllByOrderByHolidayDateAsc();
    }

    // The holiday listed on that date, if any (the first one in date order)
    public Optional<Holiday> on(LocalDate date) {
        if (date == null) return Optional.empty();
        return all().stream().filter(h -> h.fallsOn(date)).findFirst();
    }

    // Every listed holiday from `from` to `to` (both included), by date
    public TreeMap<LocalDate, Holiday> between(LocalDate from, LocalDate to) {
        TreeMap<LocalDate, Holiday> found = new TreeMap<>();
        if (from == null || to == null || to.isBefore(from)) return found;
        for (Holiday h : all()) {
            for (LocalDate d : occurrences(h, from, to)) found.putIfAbsent(d, h);
        }
        return found;
    }

    // The dates the holiday falls on from `from` to `to` (both included)
    public static List<LocalDate> occurrences(Holiday h, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        if (h.getHolidayDate() == null || from == null || to == null || to.isBefore(from)) return dates;
        if (!h.isRepeatsYearly()) {
            if (!h.getHolidayDate().isBefore(from) && !h.getHolidayDate().isAfter(to)) dates.add(h.getHolidayDate());
            return dates;
        }
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            LocalDate d = atYear(h, year);
            if (d != null && !d.isBefore(from) && !d.isAfter(to)) dates.add(d);
        }
        return dates;
    }

    // The next date (today included) the holiday falls on; null for a one-off date that has passed
    public static LocalDate nextOccurrence(Holiday h, LocalDate today) {
        if (h.getHolidayDate() == null) return null;
        if (!h.isRepeatsYearly()) return h.getHolidayDate().isBefore(today) ? null : h.getHolidayDate();
        for (int year = today.getYear(); year <= today.getYear() + 8; year++) { // 8 years always reach the next Feb 29
            LocalDate d = atYear(h, year);
            if (d != null && !d.isBefore(today)) return d;
        }
        return null;
    }

    // The list on the Slots & Staff page: upcoming first (by next date), then one-off dates that have passed
    public List<Holiday> listed(LocalDate today) {
        List<Holiday> list = new ArrayList<>(all());
        list.sort(Comparator.comparing((Holiday h) -> nextOccurrence(h, today), Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(Holiday::getHolidayDate, Comparator.nullsLast(Comparator.<LocalDate>reverseOrder())));
        return list;
    }

    // "Closed: Bonifacio Day": what visitors see on the calendar, for walk-ins and in Manage Booking
    public static String closedReason(Holiday h) {
        return "Closed: " + h.getLabel();
    }

    private static LocalDate atYear(Holiday h, int year) {
        LocalDate base = h.getHolidayDate();
        if (base.getMonthValue() == 2 && base.getDayOfMonth() == 29 && !java.time.Year.isLeap(year)) return null;
        return LocalDate.of(year, base.getMonth(), base.getDayOfMonth());
    }
}
