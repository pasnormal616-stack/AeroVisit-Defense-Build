package com.paf.aerovisit.controller;

import com.paf.aerovisit.service.AdvanceBookingRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 📅 Settings: the fewest days ahead an advance booking may be made (1-30, default 1 = tomorrow; today is Walk-in only).
 * SecurityConfig limits /admin/settings/advance-booking to the DIRECTOR.
 */
@Controller
public class AdvanceBookingSettingsController {

    // Optional, so test slices without it still start
    @Autowired(required = false)
    private AdvanceBookingRules advanceBookingRules;

    @PostMapping("/admin/settings/advance-booking")
    public String saveMinDays(@RequestParam(value = "advanceMinDaysAhead", required = false) String value,
                              RedirectAttributes redirectAttributes) {
        int days;
        try {
            days = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            days = -1;
        }
        String error = AdvanceBookingRules.settingsError(days);
        if (error == null && advanceBookingRules == null) error = "The advance booking setting is unavailable right now. Please try again.";
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/settings#advanceBookingSettings";
        }
        advanceBookingRules.saveMinDays(days);
        redirectAttributes.addFlashAttribute("success", "Advance booking setting saved: the earliest advance date is "
                + days + " day(s) from today.");
        return "redirect:/admin/settings#advanceBookingSettings";
    }
}
