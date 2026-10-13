package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

// 🛡️ GUARD DASHBOARD (least privilege): the gate's view of today. Only three groups of visitors are ever loaded:
// expected arrivals (APPROVED for today), visitors inside (CHECKED-IN) and visitors who left today (CHECKED-OUT).
// No approve / reject / KYC review and no contact details. The page's actions post to the existing endpoints:
// Deny Entry (/admin/deny-entry), Check-Out (/admin/manual-checkin) and Ban Visitor (/admin/biometric-ban/{id}).
@Controller
public class GuardDashboardController {

    // Tab key -> the one status it lists
    static final Map<String, String> TAB_STATUS = Map.of("expected", "APPROVED", "inside", "CHECKED-IN", "left", "CHECKED-OUT");

    private final VisitorRepository repo;

    public GuardDashboardController(VisitorRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/guard/dashboard")
    public String showGuardDashboard(@RequestParam(value = "tab", defaultValue = "expected") String tab,
                                     @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                                     Model model) {
        if (!TAB_STATUS.containsKey(tab)) tab = "expected";
        LocalDate today = LocalDate.now();
        // ?date= only moves Expected Arrivals (who is booked for that day); Inside Now stays live and Left Today stays today
        if (selectedDate == null) selectedDate = today;
        boolean arrivalsToday = selectedDate.equals(today);

        List<Visitor> expected = new ArrayList<>();
        List<Visitor> left = new ArrayList<>();
        for (Visitor v : repo.findByVisitDate(today)) {
            if (arrivalsToday && "APPROVED".equals(v.getStatus())) expected.add(v);
            else if ("CHECKED-OUT".equals(v.getStatus())) left.add(v);
        }
        if (!arrivalsToday) {
            for (Visitor v : repo.findByVisitDate(selectedDate)) {
                if ("APPROVED".equals(v.getStatus())) expected.add(v);
            }
        }
        // Inside now, whatever the visit date (the night sweep checks everyone out, so this is normally today only)
        List<Visitor> inside = new ArrayList<>(repo.findByStatus("CHECKED-IN"));

        // Arrivals in time-window order; inside / left with the most recent movement first
        expected.sort(Comparator.comparing(GuardDashboardController::windowStart, Comparator.nullsLast(Comparator.<java.time.LocalTime>naturalOrder()))
                .thenComparing(Visitor::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        inside.sort(Comparator.comparing(Visitor::getCheckInTime, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
        left.sort(Comparator.comparing(Visitor::getCheckOutTime, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));

        long headcountInside = inside.stream().mapToLong(v -> Math.max(1, v.getGroupSize())).sum();

        model.addAttribute("tab", tab);
        model.addAttribute("expectedCount", expected.size());
        model.addAttribute("insideCount", inside.size());
        model.addAttribute("leftCount", left.size());
        model.addAttribute("headcountInside", headcountInside);
        model.addAttribute("today", today);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("arrivalsToday", arrivalsToday);
        model.addAttribute("visitors", "inside".equals(tab) ? inside : "left".equals(tab) ? left : expected);
        return "guard";
    }

    // Start of the booked time window ("Morning (08:00 AM - 12:00 PM)" -> 08:00), null when it can't be read
    private static java.time.LocalTime windowStart(Visitor v) {
        java.time.LocalTime[] window = GuardOperationController.parseTimeWindow(v.getTimeWindow());
        return window == null ? null : window[0];
    }
}
