package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

// Director-only (covered by /admin/slots/** in SecurityConfig): add, edit and delete the operating
// time windows offered by the Bulk Slot Generator on the Staff & Slots page.
@Controller
public class TimeWindowController {

    private final TimeWindowConfigRepository windowRepo;

    public TimeWindowController(TimeWindowConfigRepository windowRepo) {
        this.windowRepo = windowRepo;
    }

    @PostMapping("/admin/slots/windows/add")
    public String addWindow(@RequestParam("name") String name,
                            @RequestParam("startTime") String startTime,
                            @RequestParam("endTime") String endTime,
                            RedirectAttributes redirectAttributes) {
        TimeWindowConfig window = new TimeWindowConfig();
        String error = applyAndValidate(window, name, startTime, endTime);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/staff";
        }
        windowRepo.save(window);
        redirectAttributes.addFlashAttribute("success", "Time window added: " + window.getLabel());
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/slots/windows/{id}/update")
    public String updateWindow(@PathVariable Long id,
                               @RequestParam("name") String name,
                               @RequestParam("startTime") String startTime,
                               @RequestParam("endTime") String endTime,
                               RedirectAttributes redirectAttributes) {
        TimeWindowConfig window = windowRepo.findById(id).orElse(null);
        if (window == null) {
            redirectAttributes.addFlashAttribute("error", "That time window no longer exists.");
            return "redirect:/admin/staff";
        }
        String error = applyAndValidate(window, name, startTime, endTime);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/staff";
        }
        windowRepo.save(window);
        redirectAttributes.addFlashAttribute("success", "Time window updated: " + window.getLabel()
                + ". Slots generated from now on use it; existing slots keep their current label.");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/slots/windows/{id}/delete")
    public String deleteWindow(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (windowRepo.existsById(id)) {
            windowRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Time window removed. Booking slots already generated with it stay open.");
        }
        return "redirect:/admin/staff";
    }

    // Copies the form values onto the window; returns an error message, or null when they are valid
    private String applyAndValidate(TimeWindowConfig window, String name, String startTime, String endTime) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 40) {
            return "Time window name is required (40 characters max).";
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(startTime);
            end = LocalTime.parse(endTime);
        } catch (DateTimeParseException e) {
            return "Please enter a valid start and end time.";
        }
        if (!start.isBefore(end)) {
            return "The end time must be later than the start time.";
        }
        String label = new TimeWindowConfig(cleanName, start, end).getLabel();
        boolean duplicate = windowRepo.findAll().stream()
                .anyMatch(w -> !w.getId().equals(window.getId()) && w.getLabel().equalsIgnoreCase(label));
        if (duplicate) {
            return "A time window with that name and schedule already exists.";
        }
        window.setName(cleanName);
        window.setStartTime(start);
        window.setEndTime(end);
        return null;
    }
}
