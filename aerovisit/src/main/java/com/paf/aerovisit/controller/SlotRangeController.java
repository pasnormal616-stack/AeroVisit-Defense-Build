package com.paf.aerovisit.controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.paf.aerovisit.service.SlotRangeDeletion;

// 🗑️ DELETE SLOTS (Slots & Staff, under the Bulk Slot Generator). Director only: /admin/slots/** in SecurityConfig.
// Step 1, Preview: how many slots are in the range and which of them have bookings (those are kept). Nothing changes.
// Step 2, Delete: only with the word DELETE typed, checked here; the slots are worked out again under a lock, so a
// booking made after the preview still keeps its slot. Every deletion is permanent.
@Controller
public class SlotRangeController {

    static final String BACK = "redirect:/admin/staff#delete-slots";
    static final String CONFIRM_WORD = "DELETE";
    static final String BAD_DATES = "Please choose a valid start and end date (the end date cannot be before the start date). Nothing was deleted.";
    static final String NOT_CONFIRMED = "Type DELETE to confirm. Nothing was deleted.";
    // 🔒 At most two years per run (security review of slot deletion): an unbounded range (e.g. year 1 to 9999) would
    // lock every slot row at once and put every booked slot ever into the page's flash
    static final int MAX_RANGE_DAYS = 731;
    static final String RANGE_TOO_LONG = "Please choose a range of at most 2 years (" + MAX_RANGE_DAYS + " days). Nothing was deleted.";

    private final SlotRangeDeletion deletion;

    public SlotRangeController(SlotRangeDeletion deletion) {
        this.deletion = deletion;
    }

    @PostMapping("/admin/slots/delete-range/preview")
    public String preview(@RequestParam(value = "startDate", required = false) String startDate,
                          @RequestParam(value = "endDate", required = false) String endDate,
                          RedirectAttributes redirectAttributes) {
        LocalDate[] range = range(startDate, endDate);
        if (range == null) {
            redirectAttributes.addFlashAttribute("error", BAD_DATES);
            return BACK;
        }
        if (tooLong(range)) {
            redirectAttributes.addFlashAttribute("error", RANGE_TOO_LONG);
            return BACK;
        }
        redirectAttributes.addFlashAttribute("slotDeletePreview", deletion.preview(range[0], range[1]));
        return BACK;
    }

    @PostMapping("/admin/slots/delete-range")
    public String delete(@RequestParam(value = "startDate", required = false) String startDate,
                         @RequestParam(value = "endDate", required = false) String endDate,
                         @RequestParam(value = "confirm", required = false) String confirm,
                         RedirectAttributes redirectAttributes) {
        LocalDate[] range = range(startDate, endDate);
        if (range == null) {
            redirectAttributes.addFlashAttribute("error", BAD_DATES);
            return BACK;
        }
        if (tooLong(range)) {
            redirectAttributes.addFlashAttribute("error", RANGE_TOO_LONG);
            return BACK;
        }
        if (confirm == null || !CONFIRM_WORD.equals(confirm.trim())) {
            redirectAttributes.addFlashAttribute("error", NOT_CONFIRMED);
            redirectAttributes.addFlashAttribute("slotDeletePreview", deletion.preview(range[0], range[1]));
            return BACK;
        }
        SlotRangeDeletion.Result result = deletion.delete(range[0], range[1]);
        int kept = result.kept().size();
        redirectAttributes.addFlashAttribute("success", "🗑️ Deleted " + result.deletable() + (result.deletable() == 1 ? " slot" : " slots")
                + " (" + range[0] + " to " + range[1] + ")."
                + (kept > 0 ? " " + kept + (kept == 1 ? " slot was" : " slots were") + " kept because " + (kept == 1 ? "it has" : "they have")
                        + " bookings (listed below)." : ""));
        redirectAttributes.addFlashAttribute("slotDeleteResult", result);
        return BACK;
    }

    private static boolean tooLong(LocalDate[] range) {
        return java.time.temporal.ChronoUnit.DAYS.between(range[0], range[1]) + 1 > MAX_RANGE_DAYS;
    }

    // [start, end], or null when either date is missing or invalid, or the end is before the start
    private static LocalDate[] range(String startDate, String endDate) {
        try {
            if (startDate == null || endDate == null) return null;
            LocalDate from = LocalDate.parse(startDate.trim()), to = LocalDate.parse(endDate.trim());
            return from.isAfter(to) ? null : new LocalDate[] {from, to};
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
