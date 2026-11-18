package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 🛡️ GUARD DASHBOARD (least privilege): the gate's view of today, in four tabs, left to right:
//  - Walk-ins Today (the default: the guard's main work): today's walk-in bookings. Each row's Process panel verifies the
//    receipt and checks the group in (/admin/walk-ins/{id}/process; Reject at /admin/payments/{id}/reject), allowed for
//    today's walk-ins only.
//  - Advance Bookings (formerly "Expected Arrivals"): APPROVED advance bookings for today, or ?date= another day.
//  - Inside Now (CHECKED-IN) and Left Today (CHECKED-OUT).
// No approve / reject / KYC review and no contact details. Deny Entry (/admin/deny-entry), Check-Out
// (/admin/manual-checkin) and Ban Visitor (/admin/biometric-ban/{id}) post to the existing endpoints.
// ?tab=expected (old bookmarks, bell and keep-my-place links) still opens Advance Bookings; ?tab=advance is the new name.
@Controller
public class GuardDashboardController {

    // Tab key -> the one status it lists (walkins: every status of today's walk-ins); "expected" = old name of "advance"
    static final Map<String, String> TAB_STATUS = Map.of("advance", "APPROVED", "expected", "APPROVED", "inside", "CHECKED-IN",
            "left", "CHECKED-OUT", "walkins", "");
    static final String DEFAULT_TAB = "walkins";

    private final VisitorRepository repo;

    // The ban a flagged walk-in's name resembles (name + photo shown on its row). Optional, so test slices still start.
    @Autowired(required = false)
    private BlacklistedFaceRepository faceBanRepo;

    // ⏱️ "Today" (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    // 🔑 Today's walk-in code for the gate (security fix M2). Optional, so test slices without it still start.
    @Autowired(required = false)
    com.paf.aerovisit.service.WalkInService walkInService;

    public GuardDashboardController(VisitorRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/guard/dashboard")
    public String showGuardDashboard(@RequestParam(value = "tab", defaultValue = DEFAULT_TAB) String tab,
                                     @RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate selectedDate,
                                     Model model) {
        if (!TAB_STATUS.containsKey(tab)) tab = DEFAULT_TAB;
        if ("expected".equals(tab)) tab = "advance";
        LocalDate today = LocalDate.now(com.paf.aerovisit.config.ClockConfig.or(clock));
        // ?date= only moves Advance Bookings (who is booked for that day); Inside Now stays live and Left Today stays today
        if (selectedDate == null) selectedDate = today;
        boolean arrivalsToday = selectedDate.equals(today);

        List<Visitor> expected = new ArrayList<>();
        List<Visitor> left = new ArrayList<>();
        List<Visitor> walkIns = new ArrayList<>();
        for (Visitor v : repo.findByVisitDate(today)) {
            if (v.walkIn()) walkIns.add(v);
            if (arrivalsToday && "APPROVED".equals(v.getStatus()) && !v.walkIn()) expected.add(v); // 🚶 walk-ins: own tab
            else if ("CHECKED-OUT".equals(v.getStatus())) left.add(v);
        }
        if (!arrivalsToday) {
            for (Visitor v : repo.findByVisitDate(selectedDate)) {
                if ("APPROVED".equals(v.getStatus()) && !v.walkIn()) expected.add(v);
            }
        }
        // Inside now, whatever the visit date (the night sweep checks everyone out, so this is normally today only)
        List<Visitor> inside = new ArrayList<>(repo.findByStatus("CHECKED-IN"));

        // Arrivals in time-window order; inside / left with the most recent movement first
        expected.sort(Comparator.comparing(GuardDashboardController::windowStart, Comparator.nullsLast(Comparator.<java.time.LocalTime>naturalOrder()))
                .thenComparing(Visitor::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        inside.sort(Comparator.comparing(Visitor::getCheckInTime, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
        left.sort(Comparator.comparing(Visitor::getCheckOutTime, Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
        walkIns.sort(Comparator.comparing(GuardDashboardController::windowStart, Comparator.nullsLast(Comparator.<java.time.LocalTime>naturalOrder()))
                .thenComparing(Visitor::getId, Comparator.nullsLast(Comparator.<Long>naturalOrder())));

        long headcountInside = inside.stream().mapToLong(v -> Math.max(1, v.getGroupSize())).sum();

        model.addAttribute("tab", tab);
        model.addAttribute("advanceCount", expected.size());
        model.addAttribute("insideCount", inside.size());
        model.addAttribute("leftCount", left.size());
        model.addAttribute("walkInCount", walkIns.size());
        model.addAttribute("walkInBans", "walkins".equals(tab) ? bansOf(walkIns) : Map.of());
        // ☑️ Each walk-in's single "ID(s) and payment" box label (data-px-verify-text), the one the server names
        Map<Long, String> verifyTexts = new HashMap<>();
        if ("walkins".equals(tab)) for (Visitor v : walkIns) verifyTexts.put(v.getId(), GuardOperationController.verifyTickLabel(v));
        model.addAttribute("verifyTexts", verifyTexts);
        model.addAttribute("headcountInside", headcountInside);
        model.addAttribute("today", today);
        model.addAttribute("walkInCode", walkInService != null && walkInService.codeRequired() ? walkInService.todaysCode(today) : null);
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("arrivalsToday", arrivalsToday);
        model.addAttribute("visitors", "inside".equals(tab) ? inside : "left".equals(tab) ? left : "walkins".equals(tab) ? walkIns : expected);
        return "guard";
    }

    // 🚶 visitor id -> the ban its name resembles, for the flagged walk-ins (the guard compares the ID with the ban photo)
    private Map<Long, BlacklistedFace> bansOf(List<Visitor> walkIns) {
        Map<Long, BlacklistedFace> bans = new HashMap<>();
        if (faceBanRepo == null) return bans;
        for (Visitor v : walkIns) {
            if (v.getPossibleBanId() != null) faceBanRepo.findById(v.getPossibleBanId()).ifPresent(ban -> bans.put(v.getId(), ban));
        }
        return bans;
    }

    // Start of the booked time window ("Morning (08:00 AM - 12:00 PM)" -> 08:00), null when it can't be read
    private static java.time.LocalTime windowStart(Visitor v) {
        java.time.LocalTime[] window = GuardOperationController.parseTimeWindow(v.getTimeWindow());
        return window == null ? null : window[0];
    }
}
