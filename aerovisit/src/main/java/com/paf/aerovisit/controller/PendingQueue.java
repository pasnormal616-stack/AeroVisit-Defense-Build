package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.NotificationService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

// 🗂️ PENDING APPROVAL QUEUE, grouped for the Duty Officer (admin.html, Pending tab). Read-only: it only orders and labels
// requests the controller already loaded; approving, rejecting and saving are untouched.
// Groups: Overdue (visit date passed) first, then one group per visit date (today, tomorrow, later), then requests with
// no date. Each dated group carries its seats left per time window, counted like the Daily Capacity Tracker.
// Each row gets triage tags for the quick-filter chips.
final class PendingQueue {

    // One time window's seats on a group's date: name = the label before "(", e.g. "Morning"
    record SeatWindow(String window, String name, int maxCapacity, int remaining) {}

    // tone: overdue | today | tomorrow | later | nodate (CSS class and default open state)
    record Group(String key, String label, String tone, boolean open, int requests, int people,
                 boolean closed, List<SeatWindow> windows) {}

    // group = the Group key; tags = space-separated chip keys; search = lowercase text for the search box
    record Row(String group, String tags, String search, boolean collapsed) {}

    record Chip(String key, String label, int count) {}

    // rows = the requests in display order; groupStarts = the Group shown above each group's first request (by id)
    record View(List<Visitor> rows, Map<Long, Group> groupStarts, Map<Long, Row> rowInfo, List<Chip> chips) {}

    static final String OVERDUE = "overdue";
    static final String NO_DATE = "nodate";

    // Chip order on the page; the key is also the tag written on matching rows
    static final List<String[]> CHIPS = List.of(
            new String[] {"manual", "Manual review"},
            new String[] {"unverified", "Name unverified"},
            new String[] {"duplicate", "Possible duplicate"},
            new String[] {"conflict", "Seat conflict"},
            new String[] {"large", "Large group (" + (NotificationService.LARGE_GROUP_THRESHOLD + 1) + "+)"},
            new String[] {"master", "Master Booking"},
            new String[] {"special", "Special consideration"},
            new String[] {"soon", "Today / tomorrow"},
            new String[] {OVERDUE, "Overdue"});

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private PendingQueue() {}

    // requests: already sorted (FIFO or newest first), kept in that order inside each group.
    // seatsFor: seats per window for a date today or later (closed day = every window 0); closedDates: buyouts + closures.
    // singleDay: a visit date was picked, so its one group opens whatever its tone.
    static View build(List<Visitor> requests, LocalDate today, Function<LocalDate, List<SeatWindow>> seatsFor,
                      java.util.Set<LocalDate> closedDates, boolean singleDay) {
        Map<String, List<Visitor>> byKey = new LinkedHashMap<>();
        List<Visitor> overdue = new ArrayList<>();
        TreeMap<LocalDate, List<Visitor>> dated = new TreeMap<>();
        List<Visitor> undated = new ArrayList<>();
        for (Visitor v : requests) {
            LocalDate d = v.getVisitDate();
            if (d == null) undated.add(v);
            else if (d.isBefore(today)) overdue.add(v);
            else dated.computeIfAbsent(d, k -> new ArrayList<>()).add(v);
        }
        if (!overdue.isEmpty()) byKey.put(OVERDUE, overdue);
        dated.forEach((d, list) -> byKey.put(d.toString(), list));
        if (!undated.isEmpty()) byKey.put(NO_DATE, undated);

        List<Visitor> rows = new ArrayList<>();
        Map<Long, Group> starts = new HashMap<>();
        Map<Long, Row> info = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, List<Visitor>> e : byKey.entrySet()) {
            String key = e.getKey();
            List<Visitor> list = e.getValue();
            LocalDate date = OVERDUE.equals(key) || NO_DATE.equals(key) ? null : LocalDate.parse(key);
            String tone = date == null ? key : date.equals(today) ? "today" : date.equals(today.plusDays(1)) ? "tomorrow" : "later";
            String label = switch (tone) {
                case OVERDUE -> "Overdue: visit date has passed";
                case NO_DATE -> "No visit date";
                case "today" -> "Today · " + DAY.format(date);
                case "tomorrow" -> "Tomorrow · " + DAY.format(date);
                default -> DAY.format(date);
            };
            boolean open = singleDay || !"later".equals(tone);
            List<SeatWindow> windows = date == null ? List.of() : seatsFor.apply(date);
            boolean closed = date != null && closedDates.contains(date);

            int people = 0;
            for (Visitor v : list) {
                people += Math.max(1, v.getGroupSize());
                String tags = tags(v, tone, windows);
                for (String t : tags.split(" ")) {
                    if (!t.isEmpty()) counts.merge(t, 1, Integer::sum);
                }
                info.put(v.getId(), new Row(key, tags, search(v), !open));
            }
            starts.put(list.get(0).getId(), new Group(key, label, tone, open, list.size(), people, closed, windows));
            rows.addAll(list);
        }

        List<Chip> chips = new ArrayList<>();
        for (String[] c : CHIPS) {
            chips.add(new Chip(c[0], c[1], counts.getOrDefault(c[0], 0)));
        }
        return new View(rows, starts, info, chips);
    }

    // The chips a request belongs to. "conflict": a standard booking for today or later whose group is larger than the
    // seats left in its window, or whose window has no slot that day (Approve would refuse both).
    static String tags(Visitor v, String tone, List<SeatWindow> windows) {
        List<String> tags = new ArrayList<>();
        if ("PENDING_MANUAL_REVIEW".equals(v.getStatus())) tags.add("manual");
        if (!v.isOcrNameMatch()) tags.add("unverified");
        if (v.getPossibleDuplicateOf() != null) tags.add("duplicate");
        if (!v.isMasterBooking() && !OVERDUE.equals(tone) && !NO_DATE.equals(tone)) {
            SeatWindow w = windows.stream().filter(s -> s.window().equals(v.getTimeWindow())).findFirst().orElse(null);
            if (w == null || Math.max(1, v.getGroupSize()) > w.remaining()) tags.add("conflict");
        }
        if (v.getGroupSize() > NotificationService.LARGE_GROUP_THRESHOLD) tags.add("large");
        if (v.isMasterBooking()) tags.add("master");
        String special = v.getSpecialConsideration();
        if (special != null && !special.isBlank() && !"Not Applicable".equals(special)) tags.add("special");
        if ("today".equals(tone) || "tomorrow".equals(tone)) tags.add("soon");
        if (OVERDUE.equals(tone)) tags.add(OVERDUE);
        return String.join(" ", tags);
    }

    // What the search box matches: ID, name, email, phone, reference and affiliation
    private static String search(Visitor v) {
        StringBuilder s = new StringBuilder().append(v.getId());
        for (String part : new String[] {v.getName(), v.getEmail(), v.getContactNumber(), v.getReferenceId(), v.getAffiliation()}) {
            if (part != null) s.append(' ').append(part);
        }
        return s.toString().toLowerCase(Locale.ROOT);
    }

    // "Morning (08:00 AM - 12:00 PM)" -> "Morning"
    static String windowName(String window) {
        int paren = window.indexOf(" (");
        return paren > 0 ? window.substring(0, paren) : window;
    }
}
