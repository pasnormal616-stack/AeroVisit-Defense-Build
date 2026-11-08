package com.paf.aerovisit.controller;

import com.paf.aerovisit.config.ClockConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.Holidays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 🎌 HOLIDAYS & CLOSED DAYS (Slots & Staff), Director-only: covered by /admin/slots/** in SecurityConfig.
 * A listed date stops new bookings, walk-ins and moves onto it, and the Bulk Slot Generator skips it. It never cancels
 * or emails anyone: a date that already has slots or bookings is not saved until the Director confirms ("Save anyway"),
 * and the warning points to Emergency Closure, which cancels those bookings and notifies the visitors.
 */
@Controller
public class HolidayController {

    private final HolidayRepository holidayRepo;
    private final BookingSlotRepository bookingRepo;
    private final VisitorRepository visitorRepo;

    @Autowired(required = false)
    java.time.Clock clock;

    public HolidayController(HolidayRepository holidayRepo, BookingSlotRepository bookingRepo, VisitorRepository visitorRepo) {
        this.holidayRepo = holidayRepo;
        this.bookingRepo = bookingRepo;
        this.visitorRepo = visitorRepo;
    }

    private LocalDate today() {
        return LocalDate.now(ClockConfig.or(clock));
    }

    @PostMapping("/admin/slots/holidays/add")
    public String addHoliday(@RequestParam("holidayDate") String date,
                             @RequestParam("label") String label,
                             @RequestParam(value = "repeatsYearly", required = false) String repeatsYearly,
                             @RequestParam(value = "confirm", required = false) String confirm,
                             RedirectAttributes redirectAttributes) {
        Holiday holiday = new Holiday();
        String error = applyAndValidate(holiday, date, label, repeatsYearly);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/staff";
        }
        if (!"true".equals(confirm) && warnAboutBookings(holiday, null, redirectAttributes)) return "redirect:/admin/staff";
        holidayRepo.save(holiday);
        redirectAttributes.addFlashAttribute("success", "🎌 Holiday listed: " + describe(holiday) + ". New bookings, walk-ins and the Bulk Slot Generator skip it.");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/slots/holidays/{id}/update")
    public String updateHoliday(@PathVariable Long id,
                                @RequestParam("holidayDate") String date,
                                @RequestParam("label") String label,
                                @RequestParam(value = "repeatsYearly", required = false) String repeatsYearly,
                                @RequestParam(value = "confirm", required = false) String confirm,
                                RedirectAttributes redirectAttributes) {
        Holiday holiday = holidayRepo.findById(id).orElse(null);
        if (holiday == null) {
            redirectAttributes.addFlashAttribute("error", "That holiday no longer exists.");
            return "redirect:/admin/staff";
        }
        // Checked on a copy, so a refused edit leaves the saved holiday as it was
        Holiday edited = new Holiday(holiday.getHolidayDate(), holiday.getLabel(), holiday.isRepeatsYearly());
        edited.setId(holiday.getId());
        String error = applyAndValidate(edited, date, label, repeatsYearly);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/staff";
        }
        if (!"true".equals(confirm) && warnAboutBookings(edited, holiday, redirectAttributes)) return "redirect:/admin/staff";
        holiday.setHolidayDate(edited.getHolidayDate());
        holiday.setLabel(edited.getLabel());
        holiday.setRepeatsYearly(edited.isRepeatsYearly());
        holidayRepo.save(holiday);
        redirectAttributes.addFlashAttribute("success", "🎌 Holiday updated: " + describe(holiday) + ".");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/slots/holidays/{id}/delete")
    public String deleteHoliday(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Holiday holiday = holidayRepo.findById(id).orElse(null);
        if (holiday == null) {
            redirectAttributes.addFlashAttribute("error", "That holiday no longer exists.");
            return "redirect:/admin/staff";
        }
        holidayRepo.delete(holiday);
        redirectAttributes.addFlashAttribute("success", "Holiday removed: " + describe(holiday) + ". Its date can be booked again wherever it has open slots.");
        return "redirect:/admin/staff";
    }

    // "Nov 30, 2026 (Bonifacio Day)", or "Nov 30 every year (Bonifacio Day)"
    static String describe(Holiday h) {
        String when = h.isRepeatsYearly() ? h.getHolidayDate().format(Holidays.SHORT) + " every year" : h.getHolidayDate().format(Holidays.LONG);
        return when + " (" + h.getLabel() + ")";
    }

    // Fills the holiday from the form; returns the error to show, or null
    private String applyAndValidate(Holiday holiday, String date, String label, String repeatsYearly) {
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(date == null ? "" : date.trim());
        } catch (DateTimeParseException e) {
            return "Holiday not saved: please pick a valid date.";
        }
        String cleanLabel = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        if (cleanLabel.isEmpty() || cleanLabel.length() > Holiday.LABEL_MAX) {
            return "Holiday not saved: the name is required (up to " + Holiday.LABEL_MAX + " characters).";
        }
        boolean yearly = "true".equals(repeatsYearly) || "on".equals(repeatsYearly);
        if (!yearly && parsed.isBefore(today())) {
            return "Holiday not saved: " + parsed.format(Holidays.LONG) + " has already passed.";
        }
        Holiday candidate = new Holiday(parsed, cleanLabel, yearly);
        for (Holiday other : holidayRepo.findAll()) {
            if (holiday.getId() != null && holiday.getId().equals(other.getId())) continue;
            if (sameDay(candidate, other)) {
                return "Holiday not saved: " + describe(other) + " is already listed on that day.";
            }
        }
        holiday.setHolidayDate(parsed);
        holiday.setLabel(cleanLabel);
        holiday.setRepeatsYearly(yearly);
        return null;
    }

    // Two holidays that would fall on the same date at least once
    private static boolean sameDay(Holiday a, Holiday b) {
        if (a.getHolidayDate() == null || b.getHolidayDate() == null) return false;
        if (a.isRepeatsYearly()) return a.fallsOn(b.getHolidayDate());
        if (b.isRepeatsYearly()) return b.fallsOn(a.getHolidayDate());
        return a.getHolidayDate().equals(b.getHolidayDate());
    }

    // ⚠️ Upcoming dates of this holiday that already have booking slots or seat-holding bookings: not saved yet. The page
    // shows the counts, points to Emergency Closure and offers "Save anyway" (the same form again with confirm=true).
    // `before` = the holiday as saved now (an edit), whose own dates were already accepted.
    private boolean warnAboutBookings(Holiday holiday, Holiday before, RedirectAttributes redirectAttributes) {
        LocalDate today = today();
        List<BookingSlot> upcoming = bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(today);
        LocalDate lastSlot = upcoming.stream().map(BookingSlot::getSlotDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(today);
        LocalDate until = lastSlot.isAfter(today.plusYears(1)) ? lastSlot : today.plusYears(1);

        List<LocalDate> dates = Holidays.occurrences(holiday, today, until);
        if (before != null) dates.removeIf(before::fallsOn);
        int slots = 0, bookings = 0;
        List<LocalDate> affected = new java.util.ArrayList<>();
        for (LocalDate d : dates) {
            int daySlots = (int) upcoming.stream().filter(s -> d.equals(s.getSlotDate())).count();
            int dayBookings = (int) visitorRepo.findByVisitDate(d).stream().filter(v -> Visitor.SEAT_HOLDING_STATUSES.contains(v.getStatus())).count();
            if (daySlots + dayBookings > 0) affected.add(d);
            slots += daySlots;
            bookings += dayBookings;
        }
        if (affected.isEmpty()) return false;

        String when = affected.size() == 1 ? affected.get(0).format(Holidays.LONG)
                : affected.size() + " of its dates (" + affected.stream().map(d -> d.format(Holidays.LONG)).collect(java.util.stream.Collectors.joining(", ")) + ")";
        redirectAttributes.addFlashAttribute("warning", "⚠️ Not saved yet: " + when + (affected.size() == 1 ? " has " : " have ")
                + slots + (slots == 1 ? " booking slot" : " booking slots") + " and " + bookings + (bookings == 1 ? " active booking" : " active bookings") + ". "
                + "Listing it as a holiday stops new bookings, but existing bookings stay and are not emailed. "
                + "To cancel them and notify the visitors, use Emergency Closure for that date.");
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("action", before == null ? "/admin/slots/holidays/add" : "/admin/slots/holidays/" + before.getId() + "/update");
        pending.put("holidayDate", holiday.getHolidayDate().toString());
        pending.put("label", holiday.getLabel());
        pending.put("repeatsYearly", holiday.isRepeatsYearly());
        redirectAttributes.addFlashAttribute("holidayPending", pending);
        return true;
    }
}
