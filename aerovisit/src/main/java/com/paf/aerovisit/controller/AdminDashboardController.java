package com.paf.aerovisit.controller;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumPrediction;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.MockVisitorGenerator;
import com.paf.aerovisit.service.PredictiveAdvisorService;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.awt.Color;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class AdminDashboardController {

    private final VisitorRepository repo;
    private final BookingSlotRepository bookingRepo;
    private final PredictiveAdvisorService aiService;

    public AdminDashboardController(VisitorRepository repo, BookingSlotRepository bookingRepo, PredictiveAdvisorService aiService) {
        this.repo = repo;
        this.bookingRepo = bookingRepo;
        this.aiService = aiService;
    }

    @GetMapping("/admin")
    public String showAdminDashboard(@RequestParam(value = "filter", required = false) String filter,
                                     @RequestParam(value = "capacityDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate capacityDate,
                                     @RequestParam(value = "sort", required = false) String sort,
                                     @RequestParam(value = "dateFilter", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFilter,
                                     @RequestParam(value = "range", defaultValue = "today") String range,
                                     Model model, RedirectAttributes redirectAttributes){
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        // 🛂 LEAST PRIVILEGE: the full manifest (pending approvals, KYC, the entire database) is the Director's. A Guard is
        // sent to the Guard Dashboard; the gate actions that end with "redirect:/admin" keep their message on the way.
        if (auth != null && !com.paf.aerovisit.config.SecurityConfig.isDirector()) {
            for (String key : new String[] {"success", "error"}) {
                if (model.getAttribute(key) != null) redirectAttributes.addFlashAttribute(key, model.getAttribute(key));
            }
            return "redirect:/guard/dashboard";
        }
        
        if (auth != null) {
            model.addAttribute("loggedInUser", auth.getName()); 
            
            List<Visitor> checkedInNow = repo.findByStatus("CHECKED-IN");
            long liveOccupancy = 0;
            for(Visitor v : checkedInNow) {
                 liveOccupancy += Math.max(1, v.getGroupSize());
            }
            model.addAttribute("currentOccupancy", liveOccupancy);

            int todayMaxCapacity = 0; 
            List<BookingSlot> allSlots = bookingRepo.findAll();
            for (BookingSlot slot : allSlots) {
                if (slot.getSlotDate() != null && slot.getSlotDate().equals(LocalDate.now())) {
                    todayMaxCapacity += slot.getMaxCapacity(); 
                }
            }
            if (todayMaxCapacity <= 0) todayMaxCapacity = 100;
            model.addAttribute("todayMaxCapacity", todayMaxCapacity);

            double utilizationRate = (todayMaxCapacity > 0) ? ((double) liveOccupancy / todayMaxCapacity) * 100 : 0;
            model.addAttribute("liveOccupancy", liveOccupancy); 
            model.addAttribute("maxCapacity", todayMaxCapacity); 
            model.addAttribute("utilizationRate", Math.round(utilizationRate));

            Double leadTime = repo.calculateAverageLeadTime();
            model.addAttribute("avgLeadTime", leadTime != null ? Math.round(leadTime * 10.0) / 10.0 : 0.0);

            // Manifest date filter (Pending and Entire Database tabs): only visits booked on that date. Today's Manifest is already one date.
            if (!"all".equals(filter) && !"pending".equals(filter)) dateFilter = null;

            List<Visitor> visitors = new ArrayList<>();
            if ("all".equals(filter)) {
                List<Visitor> rawVisitors = (dateFilter != null) ? repo.findByVisitDate(dateFilter) : repo.findAll();
                for (Visitor v : rawVisitors) {
                    if (!"Historical Baseline".equals(v.getPurpose())) {
                        visitors.add(v);
                    }
                }
            } else if ("pending".equals(filter)) {
                List<Visitor> rawVisitors = (dateFilter != null) ? repo.findByVisitDate(dateFilter) : repo.findAll();
                for (Visitor v : rawVisitors) {
                    if ("PENDING".equals(v.getStatus()) || "VERIFIED".equals(v.getStatus()) || "PENDING_MANUAL_REVIEW".equals(v.getStatus())) {
                        visitors.add(v);
                    }
                }
            } else {
                // Range picker (Today | This Week | This Month). Today is the original single-date query, untouched.
                range = manifestRange(range);
                if ("today".equals(range)) {
                    visitors = repo.findByVisitDate(LocalDate.now());
                } else {
                    for (Visitor v : repo.findByVisitDateBetween(rangeStart(range, LocalDate.now()), rangeEnd(range, LocalDate.now()))) {
                        if (!"Historical Baseline".equals(v.getPurpose())) {
                            visitors.add(v);
                        }
                    }
                }
                filter = "today";
            }
            
            // Oldest First (FIFO) or Newest First, by submission time (createdAt). Default per tab: Oldest First on
            // Today's Manifest and Pending (first come, first served), Newest First on the Entire Database (latest
            // registrations on top). A ?sort= choice from the dropdown always wins; anything else falls back to the
            // tab's default. Rows from before createdAt existed have no timestamp: they count as the oldest and are
            // ordered by ID among themselves.
            String sortOrder = "asc".equals(sort) || "desc".equals(sort) ? sort : ("all".equals(filter) ? "desc" : "asc");
            Comparator<Visitor> oldestFirst = Comparator
                    .comparing(Visitor::getCreatedAt, Comparator.nullsFirst(Comparator.<java.time.LocalDateTime>naturalOrder()))
                    .thenComparing(Visitor::getId, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));
            visitors.sort("desc".equals(sortOrder) ? oldestFirst.reversed() : oldestFirst);

            model.addAttribute("listVisitors", visitors);
            model.addAttribute("currentFilter", filter); 
            model.addAttribute("sort", sortOrder);
            model.addAttribute("dateFilter", dateFilter);
            model.addAttribute("range", manifestRange(range));

            // Daily Capacity Tracker: any date the staff pick, today by default
            LocalDate targetDate = (capacityDate != null) ? capacityDate : LocalDate.now();
            model.addAttribute("targetDate", targetDate);
            model.addAttribute("dailyCapacity", buildCapacityForDate(targetDate));
            
            return "admin";
        }

        return "redirect:/login";
    }

    // Strategic analytics, moved off the main dashboard: AI turnout forecast, 37-day traffic chart, peak arrival heatmap,
    // attrition funnel and visitor feedback. Director-only, like before. The calculations are moved unchanged from showAdminDashboard.
    @GetMapping("/admin/analytics")
    public String showAnalytics(Model model) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "redirect:/login";
        }
        // Same Director check the dashboard ran before computing these figures
        if (!com.paf.aerovisit.config.SecurityConfig.isDirector()) {
            return "redirect:/admin";
        }
        model.addAttribute("loggedInUser", auth.getName());

            List<Visitor> allTimeVisitors = repo.findAll();
            
            Map<String, Integer> stats = new TreeMap<>();
            for (int i = 29; i >= 0; i--) {
                stats.put(LocalDate.now().minusDays(i).toString(), 0);
            }
            
            for (Visitor v : allTimeVisitors) {
                if (v.getVisitDate() != null && ("APPROVED".equals(v.getStatus()) || "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()) || "NO_SHOW".equals(v.getStatus()))) {
                    String dateStr = v.getVisitDate().toString();
                    if (stats.containsKey(dateStr)) {
                        stats.put(dateStr, stats.get(dateStr) + Math.max(1, v.getGroupSize()));
                    }
                }
            }

            SimpleRegression regression = new SimpleRegression();
            List<String> combinedDates = new ArrayList<>();
            List<Integer> historicalData = new ArrayList<>();
            List<Integer> predictiveData = new ArrayList<>();

            int dayIndex = 1;
            int lastHistoricalValue = 0;

            for (String date : stats.keySet()) {
                int count = stats.get(date);
                combinedDates.add(date);
                historicalData.add(count);
                predictiveData.add(null); 
                regression.addData(dayIndex++, count);
                lastHistoricalValue = count;
            }

            if (!predictiveData.isEmpty()) {
                predictiveData.set(predictiveData.size() - 1, lastHistoricalValue);
            }

            for (int i = 1; i <= 7; i++) {
                combinedDates.add(LocalDate.now().plusDays(i).toString());
                historicalData.add(null); 
                int predictedValue = (int) Math.max(0, Math.round(regression.predict(dayIndex++)));
                predictiveData.add(predictedValue);
            }

            model.addAttribute("chartDates", combinedDates);
            model.addAttribute("chartHistorical", historicalData);
            model.addAttribute("chartPredictive", predictiveData);

            int[] hourlyTraffic = new int[24]; 
            for (Visitor v : allTimeVisitors) {
                if (v.getCheckInTime() != null && ("CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()))) {
                    int hour = v.getCheckInTime().getHour(); 
                    hourlyTraffic[hour] += Math.max(1, v.getGroupSize()); 
                }
            }
            
            List<String> hourLabels = new ArrayList<>();
            List<Integer> hourData = new ArrayList<>();
            for (int i = 8; i <= 17; i++) { 
                String amPm = (i < 12) ? "AM" : (i == 12 ? "PM" : "PM");
                int displayHour = (i > 12) ? i - 12 : i;
                hourLabels.add(String.format("%02d:00 %s", displayHour, amPm));
                hourData.add(hourlyTraffic[i]);
            }
            model.addAttribute("peakHourLabels", hourLabels);
            model.addAttribute("peakHourData", hourData);

            // 🔥 UPDATED: Dynamic Database-Driven Dashboard Forecast 🔥
            try {
                LocalDate today = LocalDate.now();
                int currentDayOfWeek = today.getDayOfWeek().getValue() - 1; // 0 = Monday

                int actualPreBookedCount = (int) allTimeVisitors.stream()
                    .filter(v -> today.equals(v.getVisitDate()))
                    .filter(v -> "APPROVED".equals(v.getStatus()) || "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()) || "NO_SHOW".equals(v.getStatus()))
                    .mapToInt(v -> v.getGroupSize() > 0 ? v.getGroupSize() : 1)
                    .sum();

                MuseumPrediction prediction = aiService.fetchTurnoutPrediction(currentDayOfWeek, "", actualPreBookedCount);

                if (prediction != null) {
                    model.addAttribute("totalPrediction", prediction.getTotalPrediction());
                    model.addAttribute("walkIns", prediction.getWalkIns());
                    
                    // 🔥 NEW: Confidence Metrics & Weather
                    model.addAttribute("minWalkIns", prediction.getMinWalkIns());
                    model.addAttribute("maxWalkIns", prediction.getMaxWalkIns());
                    model.addAttribute("confidencePercentage", prediction.getConfidencePercentage());
                    model.addAttribute("liveWeather", prediction.getWeather());

                    model.addAttribute("preBooked", actualPreBookedCount); // Shows real DB count
                    model.addAttribute("securityCondition", prediction.getSecurityCondition());
                    model.addAttribute("personnel", prediction.getPersonnel());
                    model.addAttribute("facilityProtocol", prediction.getFacilityProtocol());
                } else {
                    model.addAttribute("totalPrediction", "--");
                    model.addAttribute("walkIns", "--");
                    model.addAttribute("minWalkIns", "--");
                    model.addAttribute("maxWalkIns", "--");
                    model.addAttribute("confidencePercentage", "--");
                    model.addAttribute("liveWeather", "Telemetry Offline");
                    model.addAttribute("preBooked", "--");
                    model.addAttribute("securityCondition", "SYSTEM OFFLINE");
                    model.addAttribute("personnel", "--");
                    model.addAttribute("facilityProtocol", "AI Advisor is currently disconnected from the Command Center.");
                }
            } catch (Exception e) {
                System.out.println("AI Engine Offline: " + e.getMessage());
            }

        model.addAttribute("totalRegistered", repo.countTotalRegistered());
        model.addAttribute("totalApproved", repo.countTotalApproved());
        model.addAttribute("totalCheckedIn", repo.countTotalCheckedIn());

        List<Visitor> allVisitors = repo.findAll();
        double totalStars = 0;
        int reviewCount = 0;
        for (Visitor vis : allVisitors) {
            if (vis.getRating() != null) {
                totalStars += vis.getRating();
                reviewCount++;
            }
        }
        model.addAttribute("averageRating", (reviewCount > 0) ? Math.round((totalStars / reviewCount) * 10.0) / 10.0 : 0.0);
        model.addAttribute("reviewCount", reviewCount);

        // Live Visitor Feedback feed: every rated visit, newest first (the old dashboard fed it from the visitor table's rows)
        List<Visitor> feedbackVisitors = new ArrayList<>();
        for (Visitor vis : allVisitors) {
            if (vis.getRating() != null) {
                feedbackVisitors.add(vis);
            }
        }
        feedbackVisitors.sort((v1, v2) -> v2.getId().compareTo(v1.getId()));
        model.addAttribute("feedbackVisitors", feedbackVisitors);
        addFeedbackInsights(feedbackVisitors, model);

        return "analytics";
    }

    // Topic order used by the AI service's keyword matcher (AeroVisit-AI/main.py)
    private static final List<String> FEEDBACK_TOPICS = List.of("Facilities", "Staff", "Exhibits", "Ticketing/Entry", "General");

    // Feedback Insights (Root Cause Matrix): complaints grouped by topic, by zone, and zone x topic.
    // A complaint is a review the NLP labelled NEGATIVE, or any 1-2 star rating (covers reviews scored while the AI was offline).
    private void addFeedbackInsights(List<Visitor> ratedVisitors, Model model) {
        List<String> topics = new ArrayList<>(FEEDBACK_TOPICS);
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        Map<String, Integer> byTopic = new LinkedHashMap<>();
        Map<String, Integer> byZone = new LinkedHashMap<>();
        int complaintCount = 0;

        for (Visitor v : ratedVisitors) {
            String zone = (v.getZoneName() == null || v.getZoneName().isBlank()) ? "Unspecified" : v.getZoneName();
            byZone.putIfAbsent(zone, 0); // zones with reviews but no complaints still show as 0
            boolean negative = v.getFeedbackSentiment() != null && v.getFeedbackSentiment().toUpperCase(Locale.ROOT).contains("NEGATIVE");
            if (!negative && v.getRating() > 2) continue;

            String topic = (v.getTopicCategory() == null || v.getTopicCategory().isBlank()) ? "Unclassified" : v.getTopicCategory();
            if (!topics.contains(topic)) topics.add(topic);
            complaintCount++;
            byTopic.merge(topic, 1, Integer::sum);
            byZone.merge(zone, 1, Integer::sum);
            matrix.computeIfAbsent(zone, z -> new HashMap<>()).merge(topic, 1, Integer::sum);
        }

        List<Integer> topicData = new ArrayList<>();
        for (String topic : topics) topicData.add(byTopic.getOrDefault(topic, 0));

        List<Map.Entry<String, Integer>> zones = new ArrayList<>(byZone.entrySet());
        zones.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())); // ties: A-Z
        List<String> zoneLabels = new ArrayList<>();
        List<Integer> zoneData = new ArrayList<>();
        for (Map.Entry<String, Integer> e : zones) {
            zoneLabels.add(e.getKey());
            zoneData.add(e.getValue());
        }

        // Matrix rows: zones with at least one complaint, worst first; cells follow the topic order
        List<Map<String, Object>> matrixRows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : zones) {
            Map<String, Integer> cells = matrix.get(e.getKey());
            if (cells == null) continue;
            List<Integer> counts = new ArrayList<>();
            for (String topic : topics) counts.add(cells.getOrDefault(topic, 0));
            Map<String, Object> row = new HashMap<>();
            row.put("zone", e.getKey());
            row.put("counts", counts);
            row.put("total", e.getValue());
            matrixRows.add(row);
        }

        model.addAttribute("complaintCount", complaintCount);
        model.addAttribute("insightTopicLabels", topics);
        model.addAttribute("insightTopicData", topicData);
        model.addAttribute("insightZoneLabels", zoneLabels);
        model.addAttribute("insightZoneData", zoneData);
        model.addAttribute("rootCauseRows", matrixRows);
    }

    // Statuses listed in the Pending Approval tab; the header bell counts the same requests
    static final List<String> AWAITING_REVIEW_STATUSES = List.of("PENDING", "VERIFIED", "PENDING_MANUAL_REVIEW");

    // Header notification bell (polled every 30 seconds by the shell script): requests waiting for approval
    @GetMapping("/admin/api/pending-count")
    @ResponseBody
    public ResponseEntity<Map<String, Integer>> getPendingCount() {
        int count = (int) repo.countByStatusIn(AWAITING_REVIEW_STATUSES);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("count", count));
    }

    @GetMapping("/admin/settings")
    public String showSettings(Model model) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "redirect:/login";
        }
        model.addAttribute("loggedInUser", auth.getName());
        String role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5, 6) + a.substring(6).toLowerCase(Locale.ROOT))
                .findFirst().orElse("Staff");
        model.addAttribute("accountRole", role);
        model.addAttribute("serverDate", LocalDate.now());
        return "settings";
    }

    // Change Password form on the Settings page. Staff accounts are fixed in SecurityConfig (in-memory), so this build
    // cannot change a password: the form is checked, then the user is told plainly that nothing was changed.
    // The submitted passwords are never stored or logged.
    @PostMapping("/admin/settings/password")
    public String changePassword(@RequestParam(value = "currentPassword", required = false) String currentPassword,
                                 @RequestParam(value = "newPassword", required = false) String newPassword,
                                 @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
                                 RedirectAttributes redirectAttributes) {
        if (currentPassword == null || currentPassword.isEmpty() || newPassword == null || newPassword.isEmpty()
                || confirmPassword == null || confirmPassword.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please fill in all three password fields.");
        } else if (!newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "The new password and its confirmation do not match.");
        } else if (newPassword.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "The new password must be at least 8 characters long.");
        } else {
            redirectAttributes.addFlashAttribute("notice", "Password changes are not enabled in this build, so your password has NOT been changed. "
                    + "Staff accounts are managed by the system administrator.");
        }
        return "redirect:/admin/settings";
    }

    // Seats per time window on one date, counted the same way as the public calendar: slot capacity minus
    // seat-holding bookings (Visitor.holdsSeat), and zero when a Master Booking buyout or an emergency closure has the day
    private List<Map<String, Object>> buildCapacityForDate(LocalDate targetDate) {
        Map<String, Integer> maxByWindow = new LinkedHashMap<>();
        for (BookingSlot slot : bookingRepo.findAll()) {
            if (targetDate.equals(slot.getSlotDate()) && slot.getTimeWindow() != null) {
                maxByWindow.merge(slot.getTimeWindow(), slot.getMaxCapacity(), Integer::sum);
            }
        }
        List<Visitor> visitorsOnDate = repo.findByVisitDate(targetDate);
        boolean closed = repo.findBuyoutDates().contains(targetDate) || repo.findClosureDates().contains(targetDate);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : maxByWindow.entrySet()) {
            int booked = 0;
            for (Visitor v : visitorsOnDate) {
                if (v.holdsSeat() && e.getKey().equals(v.getTimeWindow())) {
                    booked += Math.max(1, v.getGroupSize());
                }
            }
            int remaining = closed ? 0 : Math.max(0, e.getValue() - booked);
            Map<String, Object> row = new HashMap<>();
            row.put("window", e.getKey());
            row.put("maxCapacity", e.getValue());
            row.put("remaining", remaining);
            rows.add(row);
        }
        // Window labels start with a name, so order by the start time inside "(hh:mm AM - ...)"
        rows.sort(Comparator.comparing(r -> windowStart((String) r.get("window"))));
        return rows;
    }

    private static java.time.LocalTime windowStart(String label) {
        Matcher m = Pattern.compile("\\((\\d{1,2}:\\d{2} [AP]M)").matcher(label);
        if (m.find()) {
            try {
                return java.time.LocalTime.parse(m.group(1), java.time.format.DateTimeFormatter.ofPattern("hh:mm a", Locale.US));
            } catch (java.time.format.DateTimeParseException ignored) { }
        }
        return java.time.LocalTime.MAX;
    }

    // Today's Manifest range picker: "week" or "month", anything else (missing, blank, unknown) is "today"
    static String manifestRange(String range) {
        return ("week".equals(range) || "month".equals(range)) ? range : "today";
    }

    // First day of the range: Monday of this week, the 1st of this month, or today
    static LocalDate rangeStart(String range, LocalDate today) {
        if ("week".equals(range)) return today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        if ("month".equals(range)) return today.withDayOfMonth(1);
        return today;
    }

    // Last day of the range, included: Sunday of this week, the last day of this month, or today
    static LocalDate rangeEnd(String range, LocalDate today) {
        if ("week".equals(range)) return today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
        if ("month".equals(range)) return today.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        return today;
    }

    // Report / sheet label for a range, e.g. "THIS WEEK (2026-10-05 to 2026-10-11)"
    static String rangeLabel(String range, LocalDate today) {
        if ("today".equals(range)) return "TODAY";
        return ("week".equals(range) ? "THIS WEEK" : "THIS MONTH") + " (" + rangeStart(range, today) + " to " + rangeEnd(range, today) + ")";
    }

    @GetMapping("/admin/report")
    public void exportToPDF(@RequestParam(value = "filter", required = false, defaultValue = "today") String filter,
                            @RequestParam(value = "range", defaultValue = "today") String range, HttpServletResponse response) throws IOException {
        response.setContentType("application/pdf");
        String currentDateTime = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(new Date());
        response.setHeader("Content-Disposition", "attachment; filename=Command_Report_" + currentDateTime + ".pdf");
        
        List<Visitor> listVisitors = new ArrayList<>();
        range = manifestRange(range);
        String filterLabel = filter.toUpperCase();
        if ("today".equals(filter) && !"today".equals(range)) {
            // This Week / This Month roster: same rows as the dashboard's range picker
            for (Visitor v : repo.findByVisitDateBetween(rangeStart(range, LocalDate.now()), rangeEnd(range, LocalDate.now()))) {
                if (!"Historical Baseline".equals(v.getPurpose())) {
                    listVisitors.add(v);
                }
            }
            filterLabel = rangeLabel(range, LocalDate.now());
        } else if ("today".equals(filter)) {
            listVisitors = repo.findByVisitDate(LocalDate.now());
        } else if ("pending".equals(filter)) {
            List<Visitor> rawVisitors = repo.findAll();
            for (Visitor v : rawVisitors) {
                if ("PENDING".equals(v.getStatus()) || "VERIFIED".equals(v.getStatus()) || "PENDING_MANUAL_REVIEW".equals(v.getStatus())) {
                    listVisitors.add(v);
                }
            }
        } else {
            List<Visitor> rawVisitors = repo.findAll();
            for (Visitor v : rawVisitors) {
                if (!"Historical Baseline".equals(v.getPurpose())) {
                    listVisitors.add(v);
                }
            }
        }
        listVisitors.sort((v1, v2) -> v2.getId().compareTo(v1.getId()));

        List<Visitor> allTimeVisitors = repo.findAll();

        Map<String, Integer> stats = new TreeMap<>();
        for (int i = 29; i >= 0; i--) {
            stats.put(LocalDate.now().minusDays(i).toString(), 0);
        }
        for (Visitor v : allTimeVisitors) {
            if (v.getVisitDate() != null && ("APPROVED".equals(v.getStatus()) || "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()) || "NO_SHOW".equals(v.getStatus()))) {
                String dateStr = v.getVisitDate().toString();
                if (stats.containsKey(dateStr)) {
                    stats.put(dateStr, stats.get(dateStr) + Math.max(1, v.getGroupSize()));
                }
            }
        }
        SimpleRegression regression = new SimpleRegression();
        int dayIndex = 1;
        for (String date : stats.keySet()) {
            regression.addData(dayIndex++, stats.get(date));
        }

        int[] hourlyTraffic = new int[24];
        for (Visitor v : allTimeVisitors) {
            if (v.getCheckInTime() != null && ("CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()))) {
                hourlyTraffic[v.getCheckInTime().getHour()] += Math.max(1, v.getGroupSize());
            }
        }
        int peakHour = 8; int maxTraffic = 0;
        for (int i = 8; i <= 17; i++) {
            if (hourlyTraffic[i] > maxTraffic) { maxTraffic = hourlyTraffic[i]; peakHour = i; }
        }
        String amPm = (peakHour < 12) ? "AM" : (peakHour == 12 ? "PM" : "PM");
        int displayHour = (peakHour > 12) ? peakHour - 12 : peakHour;
        String peakHourStr = String.format("%02d:00 %s", displayHour, amPm);

        double totalStars = 0; int reviewCount = 0;
        for (Visitor v : allTimeVisitors) {
            if (v.getRating() != null) { totalStars += v.getRating(); reviewCount++; }
        }
        String avgRatingStr = (reviewCount > 0) ? String.valueOf(Math.round((totalStars / reviewCount) * 10.0) / 10.0) + " / 5.0" : "N/A";

        Double leadTime = repo.calculateAverageLeadTime();
        String avgLeadTimeStr = (leadTime != null) ? String.valueOf(Math.round(leadTime * 10.0) / 10.0) + " Days" : "0.0 Days";

        List<Visitor> checkedInNow = repo.findByStatus("CHECKED-IN");
        long liveOccupancy = 0;
        for(Visitor v : checkedInNow) { liveOccupancy += Math.max(1, v.getGroupSize()); }
        int todayMaxCapacity = 0;
        List<BookingSlot> allSlots = bookingRepo.findAll();
        for (BookingSlot slot : allSlots) {
            if (slot.getSlotDate() != null && slot.getSlotDate().equals(LocalDate.now())) {
                todayMaxCapacity += slot.getMaxCapacity();
            }
        }
        if (todayMaxCapacity <= 0) todayMaxCapacity = 100;
        int utilizationRate = (int) Math.round(((double) liveOccupancy / todayMaxCapacity) * 100);
        String stressLevel = utilizationRate + "% ";
        if (utilizationRate > 85) stressLevel += "(CRITICAL)";
        else if (utilizationRate > 50) stressLevel += "(ELEVATED)";
        else stressLevel += "(NORMAL)";

        long totalReg = repo.countTotalRegistered();
        long totalApp = repo.countTotalApproved();
        long totalCheck = repo.countTotalCheckedIn();

        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, response.getOutputStream());
        document.open();
        
        Font fontTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLUE);
        Paragraph title = new Paragraph("PAF Aerospace Museum - Official Visitor Report", fontTitle);
        title.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); 
        document.add(title);
        
        Font fontSubtitle = FontFactory.getFont(FontFactory.HELVETICA, 12);
        Paragraph subtitle = new Paragraph("Report Filter: " + filterLabel + " | Generated: " + LocalDate.now().toString(), fontSubtitle);
        subtitle.setAlignment(com.lowagie.text.Element.ALIGN_CENTER); 
        document.add(subtitle);
        
        document.add(new Paragraph(" "));

        Font fontSection = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.DARK_GRAY);
        Font fontMetricLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
        Font fontMetricValue = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.DARK_GRAY);

        Paragraph execTitle = new Paragraph("Command Analytics Summary", fontSection);
        execTitle.setSpacingAfter(5);
        document.add(execTitle);

        PdfPTable metricsTable = new PdfPTable(2);
        metricsTable.setWidthPercentage(100f);
        metricsTable.setWidths(new float[] {1.0f, 1.0f});
        metricsTable.setSpacingAfter(10);

        PdfPTable leftMetrics = new PdfPTable(2); 
        leftMetrics.setWidths(new float[] {1.5f, 1.0f});
        leftMetrics.getDefaultCell().setBorder(0); 

        leftMetrics.addCell(new Phrase("Live Base Stress Level:", fontMetricLabel));
        leftMetrics.addCell(new Phrase(stressLevel, fontMetricValue));
        leftMetrics.addCell(new Phrase("Average Booking Lead Time:", fontMetricLabel));
        leftMetrics.addCell(new Phrase(avgLeadTimeStr, fontMetricValue));
        leftMetrics.addCell(new Phrase("Peak Arrival Heatmap Hour:", fontMetricLabel));
        leftMetrics.addCell(new Phrase(peakHourStr, fontMetricValue));
        leftMetrics.addCell(new Phrase("Average Visitor Rating:", fontMetricLabel));
        leftMetrics.addCell(new Phrase(avgRatingStr, fontMetricValue));

        PdfPTable rightMetrics = new PdfPTable(2); 
        rightMetrics.setWidths(new float[] {1.5f, 1.0f});
        rightMetrics.getDefaultCell().setBorder(0); 

        rightMetrics.addCell(new Phrase("Total Portal Registrations:", fontMetricLabel));
        rightMetrics.addCell(new Phrase(String.valueOf(totalReg), fontMetricValue));
        rightMetrics.addCell(new Phrase("Total Admin Approvals:", fontMetricLabel));
        rightMetrics.addCell(new Phrase(String.valueOf(totalApp), fontMetricValue));
        rightMetrics.addCell(new Phrase("Total Base Check-Ins:", fontMetricLabel));
        rightMetrics.addCell(new Phrase(String.valueOf(totalCheck), fontMetricValue));
        
        PdfPCell leftContainer = new PdfPCell(leftMetrics); leftContainer.setBorder(0);
        PdfPCell rightContainer = new PdfPCell(rightMetrics); rightContainer.setBorder(0);
        metricsTable.addCell(leftContainer);
        metricsTable.addCell(rightContainer);
        document.add(metricsTable);

        Paragraph forecastTitle = new Paragraph("AI Predictive Forecast (Next 7 Days)", fontSection);
        forecastTitle.setSpacingAfter(5);
        document.add(forecastTitle);

        PdfPTable forecastTable = new PdfPTable(2);
        forecastTable.setWidthPercentage(40f); 
        forecastTable.setHorizontalAlignment(com.lowagie.text.Element.ALIGN_LEFT);
        
        Font fontForecastHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
        PdfPCell fCell = new PdfPCell(new Phrase("Date", fontForecastHeader));
        fCell.setBackgroundColor(new Color(220, 53, 69)); 
        fCell.setPadding(5);
        forecastTable.addCell(fCell);
        
        fCell = new PdfPCell(new Phrase("Predicted Arrivals", fontForecastHeader));
        fCell.setBackgroundColor(new Color(220, 53, 69));
        fCell.setPadding(5);
        forecastTable.addCell(fCell);
        
        Font fontData = FontFactory.getFont(FontFactory.HELVETICA, 11);
        Font fontForecastData = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        
        for (int i = 1; i <= 7; i++) {
            String fDate = LocalDate.now().plusDays(i).toString();
            int predictedValue = (int) Math.max(0, Math.round(regression.predict(dayIndex++)));
            
            PdfPCell dateCell = new PdfPCell(new Phrase(fDate, fontData)); dateCell.setPadding(4); forecastTable.addCell(dateCell);
            PdfPCell valCell = new PdfPCell(new Phrase(String.valueOf(predictedValue) + " Civilians", fontForecastData)); valCell.setPadding(4); forecastTable.addCell(valCell);
        }
        document.add(forecastTable);
        document.add(new Paragraph(" ")); 
        
        Paragraph rosterTitle = new Paragraph("Operational Roster", fontSection);
        rosterTitle.setSpacingAfter(5);
        document.add(rosterTitle);

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100f); 
        table.setWidths(new float[] {1.0f, 3.0f, 2.0f, 2.5f, 2.0f});
        
        PdfPCell cell = new PdfPCell(); 
        cell.setBackgroundColor(Color.DARK_GRAY); 
        cell.setPadding(5);
        Font fontHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
        
        cell.setPhrase(new Phrase("ID", fontHeader)); table.addCell(cell);
        cell.setPhrase(new Phrase("Name", fontHeader)); table.addCell(cell);
        cell.setPhrase(new Phrase("Date", fontHeader)); table.addCell(cell);
        cell.setPhrase(new Phrase("Purpose", fontHeader)); table.addCell(cell);
        cell.setPhrase(new Phrase("Status", fontHeader)); table.addCell(cell);
        
        for (Visitor visitor : listVisitors) {
            table.addCell(new Phrase(String.valueOf(visitor.getId()), fontData));
            String nameAddon = (visitor.getGroupSize() > 1) ? " (x" + visitor.getGroupSize() + ")" : "";
            table.addCell(new Phrase(visitor.getName() + nameAddon, fontData));
            table.addCell(new Phrase(visitor.getVisitDate() != null ? visitor.getVisitDate().toString() : "", fontData));
            table.addCell(new Phrase(visitor.getPurpose(), fontData));
            table.addCell(new Phrase(visitor.getStatus(), fontData));
        }
        document.add(table); 

        // Feedback Insights (Root Cause Matrix): all-time complaints, same figures as the Analytics page
        List<Visitor> ratedVisitors = new ArrayList<>();
        for (Visitor v : allTimeVisitors) {
            if (v.getRating() != null) ratedVisitors.add(v);
        }
        org.springframework.ui.ExtendedModelMap insights = new org.springframework.ui.ExtendedModelMap();
        addFeedbackInsights(ratedVisitors, insights);
        addFeedbackInsightsToPdf(document, insights, ratedVisitors.size(), fontSection, fontData);

        document.close();
    }

    // PDF section: complaint count, then the zone x topic matrix with a per-topic total row
    @SuppressWarnings("unchecked")
    private void addFeedbackInsightsToPdf(Document document, Map<String, Object> insights, int reviewTotal, Font fontSection, Font fontData) {
        document.add(new Paragraph(" "));
        Paragraph insightsTitle = new Paragraph("Feedback Insights - Root Cause Matrix (All Time)", fontSection);
        insightsTitle.setSpacingAfter(5);
        document.add(insightsTitle);

        int complaintCount = (Integer) insights.get("complaintCount");
        document.add(new Paragraph(complaintCount + " complaint(s) out of " + reviewTotal
                + " review(s). A complaint is a review the AI labelled NEGATIVE, or any 1-2 star rating.", fontData));
        if (complaintCount == 0) {
            return;
        }

        List<String> topics = (List<String>) insights.get("insightTopicLabels");
        List<Integer> topicTotals = (List<Integer>) insights.get("insightTopicData");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) insights.get("rootCauseRows");

        PdfPTable matrix = new PdfPTable(topics.size() + 2);
        matrix.setWidthPercentage(100f);
        matrix.setSpacingBefore(6);
        // Topic columns sized to their label so "Ticketing/Entry" stays on one line
        float[] widths = new float[topics.size() + 2];
        widths[0] = 2.4f;
        for (int i = 0; i < topics.size(); i++) widths[i + 1] = Math.max(1.0f, topics.get(i).length() / 10f);
        widths[topics.size() + 1] = 0.9f;
        matrix.setWidths(widths);

        Font fontHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        Font fontCell = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font fontTotal = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        List<String> headers = new ArrayList<>();
        headers.add("Zone");
        headers.addAll(topics);
        headers.add("Total");
        for (String h : headers) {
            PdfPCell head = new PdfPCell(new Phrase(h, fontHeader));
            head.setBackgroundColor(Color.DARK_GRAY);
            head.setPadding(4);
            matrix.addCell(head);
        }
        for (Map<String, Object> row : rows) {
            matrix.addCell(new Phrase(String.valueOf(row.get("zone")), fontCell));
            for (Integer c : (List<Integer>) row.get("counts")) {
                matrix.addCell(new Phrase(c == 0 ? "-" : String.valueOf(c), c == 0 ? fontCell : fontTotal));
            }
            matrix.addCell(new Phrase(String.valueOf(row.get("total")), fontTotal));
        }
        matrix.addCell(new Phrase("All zones", fontTotal));
        for (Integer c : topicTotals) {
            matrix.addCell(new Phrase(String.valueOf(c), fontTotal));
        }
        matrix.addCell(new Phrase(String.valueOf(complaintCount), fontTotal));
        document.add(matrix);
    }

    // Days the Excel export covers, today included (the same 30 days the analytics trend chart shows)
    static final int EXPORT_DAYS = 30;

    @GetMapping("/admin/export-excel")
    public void exportToExcel(@RequestParam(value = "range", required = false) String range, HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String currentDateTime = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String headerKey = "Content-Disposition";
        String headerValue = "attachment; filename=PAF_AeroVisit_Data_" + currentDateTime + ".xlsx";
        response.setHeader(headerKey, headerValue);

        // Last 30 days only, real bookings only (no mock / Historical Baseline rows), newest id first
        LocalDate today = LocalDate.now();
        // ?range=today|week|month (Today's Manifest range picker) narrows it to that range instead; no range = the last 30 days as before
        String sheetName = "Visitors (Last 30 Days)";
        LocalDate from = today.minusDays(EXPORT_DAYS - 1);
        LocalDate to = today;
        if (range != null) {
            range = manifestRange(range);
            from = rangeStart(range, today);
            to = rangeEnd(range, today);
            sheetName = "today".equals(range) ? "Visitors (Today)" : ("week".equals(range) ? "Visitors (This Week)" : "Visitors (This Month)");
        }
        List<Visitor> visitors = repo.findRealVisitorsForExport(from, to);

        String[] headers = { "Registry ID", "Civilian Name", "Group Size", "Email Contact", "Phone Number", "Visit Date", "Declared Purpose",
            "Live Status", "Time of Entry", "Assigned Guard", "Feedback Rating", "Time of Exit", "Feedback Zone", "Topic Category",
            "AI Sentiment", "Feedback Comment" };
        int commentColumn = headers.length - 1;

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);

            CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = workbook.createCellStyle();
            dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            CellStyle wrapStyle = workbook.createCellStyle();
            wrapStyle.setWrapText(true);
            wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);

            int rowIndex = 1;
            for (Visitor v : visitors) {
                String guard = (v.getCheckedInBy() != null) ? v.getCheckedInBy() : "N/A";
                String rating = (v.getRating() != null) ? v.getRating() + " Stars" : "No Review";
                // Root Cause Matrix columns: filled for reviewed visits only, with the same labels as the Analytics page
                boolean reviewed = v.getRating() != null;
                String zone = !reviewed ? "" : (v.getZoneName() == null || v.getZoneName().isBlank()) ? "Unspecified" : v.getZoneName();
                String topic = !reviewed ? "" : (v.getTopicCategory() == null || v.getTopicCategory().isBlank()) ? "Unclassified" : v.getTopicCategory();
                String sentiment = !reviewed ? "" : (v.getFeedbackSentiment() == null || v.getFeedbackSentiment().isBlank()) ? "Not Analysed" : v.getFeedbackSentiment();

                // Text is stored as plain string cells, which Excel never runs as a formula
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(v.getId());
                row.createCell(1).setCellValue(v.getName());
                row.createCell(2).setCellValue(v.getGroupSize());
                row.createCell(3).setCellValue(v.getEmail());
                row.createCell(4).setCellValue(v.getPhone());
                Cell visitDate = row.createCell(5);
                visitDate.setCellValue(v.getVisitDate());
                visitDate.setCellStyle(dateStyle);
                row.createCell(6).setCellValue(v.getPurpose());
                row.createCell(7).setCellValue(v.getStatus());
                timeCell(row.createCell(8), v.getCheckInTime(), dateTimeStyle);
                row.createCell(9).setCellValue(guard);
                row.createCell(10).setCellValue(rating);
                timeCell(row.createCell(11), v.getCheckOutTime(), dateTimeStyle);
                row.createCell(12).setCellValue(zone);
                row.createCell(13).setCellValue(topic);
                row.createCell(14).setCellValue(sentiment);
                Cell comment = row.createCell(commentColumn);
                comment.setCellValue(v.getFeedback());
                comment.setCellStyle(wrapStyle);
            }

            for (int i = 0; i < commentColumn; i++) {
                sheet.autoSizeColumn(i);
            }
            // Fixed width + wrap so long reviews stay inside their own column
            sheet.setColumnWidth(commentColumn, 10_000);

            workbook.write(response.getOutputStream());
        }
    }

    // Check-in / check-out time as a real Excel date-time, or "N/A" when it never happened
    private static void timeCell(Cell cell, java.time.LocalDateTime time, CellStyle dateTimeStyle) {
        if (time == null) {
            cell.setCellValue("N/A");
        } else {
            cell.setCellValue(time);
            cell.setCellStyle(dateTimeStyle);
        }
    }

    // 🧪 DEMO DATA: replaces all simulated visitor history with a fresh, realistic set (see MockVisitorGenerator).
    // Real bookings are never deleted. 'months' is how far back to go: 3 to 6, default 6.
    @GetMapping("/admin/inject-mock-data")
    public String injectMockData(@RequestParam(value = "months", defaultValue = "6") int months, RedirectAttributes redirectAttributes) {
        // 1. Clean slate: remove earlier mock rows only
        repo.deleteMockVisitors();

        // 2. Generate up to yesterday, so today's manifest stays free for live check-ins
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusMonths(Math.max(3, Math.min(6, months)));
        java.util.Set<String> usedReferenceIds = new java.util.HashSet<>();
        for (Visitor existing : repo.findAll()) {
            if (existing.getReferenceId() != null) usedReferenceIds.add(existing.getReferenceId());
        }
        List<Visitor> mockVisitors = MockVisitorGenerator.generate(start, today,
                new java.util.HashSet<>(repo.findClosureDates()), usedReferenceIds, new java.util.Random());
        repo.saveAll(mockVisitors);

        // 3. The AI forecast is trained on the visitor table, so retrain it on the new history right away
        boolean retrained = aiService.triggerAiRetrain();

        redirectAttributes.addFlashAttribute("success", "Realistic museum mock data successfully injected."
                + (retrained ? "" : " The AI service did not respond, so the forecast is unchanged until AeroVisit-AI is restarted."));
        return "redirect:/admin";
    }
    @GetMapping("/login")
    public String showLoginPage() { 
        return "login"; 
    }
}