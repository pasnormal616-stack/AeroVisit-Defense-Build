package com.paf.aerovisit.controller;

import com.paf.aerovisit.service.FeedbackEmails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 📝 Settings: how many minutes after a visit's time window ends its feedback email is sent (0-180, default 30).
 * SecurityConfig limits /admin/settings/feedback to the DIRECTOR.
 */
@Controller
public class FeedbackSettingsController {

    // Optional, so test slices without it still start
    @Autowired(required = false)
    private FeedbackEmails feedbackEmails;

    @PostMapping("/admin/settings/feedback")
    public String saveDelay(@RequestParam(value = "feedbackDelayMinutes", required = false) String value,
                            RedirectAttributes redirectAttributes) {
        int minutes;
        try {
            minutes = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        String error = FeedbackEmails.delayError(minutes);
        if (error == null && feedbackEmails == null) error = "The feedback email setting is unavailable right now. Please try again.";
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/settings#feedbackSettings";
        }
        feedbackEmails.saveDelay(minutes);
        redirectAttributes.addFlashAttribute("success", minutes == 0
                ? "Feedback email setting saved: sent as soon as a visit's time window ends."
                : "Feedback email setting saved: sent " + minutes + " minutes after a visit's time window ends.");
        return "redirect:/admin/settings#feedbackSettings";
    }
}
