package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.MuseumClosure;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
public class ManagementController {

    private final VisitorRepository repo;
    private final StaffScheduleRepository staffRepo;
    private final BookingSlotRepository bookingRepo;
    private final MuseumEmployeeRepository employeeRepo;
    private final EmailService emailService;
    private final TimeWindowConfigRepository timeWindowRepo;
    private final MuseumClosureRepository closureRepo;

    // ⏱️ "Today" for the Duty Roster's past-date rule: the shared Clock bean (ClockConfig); null in test slices = system clock
    @Autowired(required = false)
    java.time.Clock clock;

    private LocalDate today() {
        return LocalDate.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    // 🧬 Face ban list (the only ban). Optional, so web-layer test slices without it still start (a ban then reports an error).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.repository.BlacklistedFaceRepository faceBanRepo;

    // ☁️ Keeps the S3 copies of photos in step with uploads/ (off locally). Optional, so test slices without it still start.
    @Autowired(required = false)
    private com.paf.aerovisit.service.UploadMirror uploadMirror;

    // 📮 The rejection email goes out in the background, so Reject answers at once; a failed send goes to the bell.
    // Optional: without them (test slices) the email is sent right away, with the same handling (MailOutbox.send).
    @Autowired(required = false)
    private com.paf.aerovisit.service.MailOutbox mailOutbox;

    @Autowired(required = false)
    private com.paf.aerovisit.service.NotificationService notificationService;

    // 🔔 A deleted booking takes its bell notifications with it. Optional, so test slices without it still start.
    @Autowired(required = false)
    private com.paf.aerovisit.repository.AdminNotificationRepository notificationRepo;

    // 🎌 Holidays & Closed Days: the Bulk Slot Generator always skips them. Optional, so test slices without it still start
    // (then no holidays are listed).
    @Autowired(required = false)
    com.paf.aerovisit.service.Holidays holidays;

    public ManagementController(VisitorRepository repo, StaffScheduleRepository staffRepo, BookingSlotRepository bookingRepo, MuseumEmployeeRepository employeeRepo, EmailService emailService, TimeWindowConfigRepository timeWindowRepo, MuseumClosureRepository closureRepo) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.bookingRepo = bookingRepo;
        this.employeeRepo = employeeRepo;
        this.emailService = emailService;
        this.timeWindowRepo = timeWindowRepo;
        this.closureRepo = closureRepo;
    }

    static final String RECEIPT_ON_FILE_DELETE = "This booking has a payment receipt on file (proof of payment), so it can't be "
            + "deleted. It is cleared automatically 30 days after the visit.";

    @PostMapping("/admin/delete/{id}")
    public String deleteVisitor(@PathVariable Long id, @RequestParam(value = "returnTo", required = false) String returnTo, RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // ↩️ the page the officer acted from, or /admin
        if (id == null) return back;
        Visitor v = repo.findById(id).orElse(null);
        if (v != null) {
            // 💳 A receipt is proof of payment and is never deleted by hand (security fix M6 keeps that rule): a booking
            // with one is refused here and cleared by the 30-day retention purge with its other files instead
            if (v.receiptOnFile()) {
                redirectAttributes.addFlashAttribute("error", RECEIPT_ON_FILE_DELETE);
                return back;
            }
            // 🗑️ Security fix M6: every file of the booking goes with it (local + S3): the old selfie, the ID photo, the
            // legacy face crop, a Foreign visitor's passport PDF and the QR. Once the row is gone nothing could ever find
            // them again, so the row is deleted only after every file is gone.
            Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads");
            List<String> files = new ArrayList<>();
            for (String f : new String[] {v.getIdImageUrl(), v.getIdCardImageUrl(), v.getIdFaceCropUrl(), v.getPassportFileUrl(), v.getQrCodeUrl()}) {
                if (f != null && !f.isBlank()) files.add(f);
            }
            boolean allGone = true;
            for (String f : files) {
                try {
                    Path file = uploadDir.resolve(f).normalize();
                    if (file.startsWith(uploadDir.normalize())) Files.deleteIfExists(file);
                    if (uploadMirror != null && !uploadMirror.deleteUpload(f)) allGone = false;
                } catch (IOException | RuntimeException e) {
                    System.err.println("⚠️ Delete: file " + f + " of visitor #" + id + " could not be removed: " + e.getMessage());
                    allGone = false;
                }
            }
            if (!allGone) {
                redirectAttributes.addFlashAttribute("error", "The booking was not deleted: some of its files could not be removed. Please try again.");
                return back;
            }
            repo.delete(v);
            if (notificationRepo != null) notificationRepo.deleteByVisitorId(id);
            redirectAttributes.addFlashAttribute("success", "Visitor deleted and storage space freed up!");
        }
        return back;
    }

    @PostMapping("/admin/reject-visitor")
    public String rejectVisitor(
            @RequestParam("id") Long id, 
            @RequestParam("presetReason") String presetReason,
            @RequestParam(value = "additionalNotes", required = false) String additionalNotes,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            @RequestParam(value = "confirmPaid", required = false) String confirmPaid,
            RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // the officer's Pending view, or /admin as before

        Visitor visitor = repo.findById(id).orElse(null);
        // 💳 A booking with a receipt on file (paid, in review, or rejected since) is only rejected after the
        // non-refundable warning is confirmed
        if (visitor != null && visitor.receiptOnFile() && !"true".equals(confirmPaid)) {
            redirectAttributes.addFlashAttribute("error", Visitor.PAID_NON_REFUNDABLE + " Tick the confirmation to reject it.");
            return back;
        }
        if (visitor != null && "DENIED_ENTRY".equals(visitor.getStatus())) {
            // 🛑 A gate denial is a final audit record and must not be overwritten
            redirectAttributes.addFlashAttribute("error", "This visitor was denied entry at the gate; that record cannot be changed.");
            return back;
        }
        if (visitor != null && visitor.visitUnderwayOrOver()) {
            // 🛑 Rejecting someone inside would drop them from live occupancy (and the 5:30 PM sweep), and a finished
            // or missed visit is history
            redirectAttributes.addFlashAttribute("error", Visitor.CANNOT_REJECT_MESSAGE);
            return back;
        }
        if (visitor != null && !GuardOperationController.rejectable(visitor)) {
            // 🛑 Security fix L3: a cancelled, expired or already rejected booking keeps its outcome, and no email goes out
            redirectAttributes.addFlashAttribute("error", GuardOperationController.alreadyClosedMessage(visitor));
            return back;
        }
        if (visitor != null) {
            visitor.setStatus("REJECTED");
            
            String finalDatabaseReason = presetReason;
            if (additionalNotes != null && !additionalNotes.trim().isEmpty()) {
                finalDatabaseReason += " | Notes: " + additionalNotes;
            }
            visitor.setRejectionReason(finalDatabaseReason);
            repo.save(visitor);
            
            String to = visitor.getEmail(), firstName = visitor.getFirstName();
            Visitor rejected = visitor;
            com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "rejection email for visitor #" + visitor.getId(),
                    () -> emailService.sendRejectionEmail(to, firstName, presetReason, additionalNotes),
                    () -> { if (notificationService != null) notificationService.rejectionEmailFailed(rejected); });
            redirectAttributes.addFlashAttribute("success", "⚠️ Visitor rejected. The rejection email to " + to + " is on its way.");
        }
        return back;
    }

    // 🪪 BAN VISITOR (the only ban in the system): copies the booking's email, phone number and name into
    // blacklisted_faces (the table keeps its old name). The email and phone number are refused at /save, approval and the
    // gate; a similar name is only FLAGGED for a person to compare. No photo is stored (never a copy of the ID); older bans
    // keep the mugshot they already have. It also revokes every open booking of this person (see revokeOpenBookings). A
    // ban is temporary (30 days, 6 months) or permanent; an expired ban is ignored everywhere.
    static final int MAX_BAN_REASON = 255;
    // Choices in the Ban Visitor dialog (admin.html); a request without one (not from the dialog) is permanent
    static final Set<String> BAN_DURATIONS = Set.of("30_DAYS", "6_MONTHS", "PERMANENT");

    // Last day of a ban starting today, or null for a permanent ban; IllegalArgumentException for an unknown choice
    static LocalDate banExpiry(String duration, LocalDate today) {
        return switch (duration) {
            case "30_DAYS" -> today.plusDays(30);
            case "6_MONTHS" -> today.plusMonths(6);
            case "PERMANENT" -> null;
            default -> throw new IllegalArgumentException(duration);
        };
    }

    // Where older bans' mugshots live (served to staff at /uploads/**); lifting such a ban deletes its file. Tests point
    // it at a temp folder.
    Path mugshotDir = Paths.get("uploads");

    // dir/fileName when fileName is a plain file name directly inside dir, else null (so "../x" never escapes uploads/)
    private static Path plainUpload(Path dir, String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        try {
            Path p = dir.resolve(fileName).normalize();
            return dir.normalize().equals(p.getParent()) && fileName.equals(String.valueOf(p.getFileName())) ? p : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private static String untilText(LocalDate expiresAt) {
        return expiresAt == null ? "permanently" : "until " + expiresAt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
    }

    @PostMapping("/admin/biometric-ban/{visitorId}")
    public String biometricBan(@PathVariable Long visitorId,
                               @RequestParam(value = "reason", required = false) String reason,
                               @RequestParam(value = "duration", defaultValue = "PERMANENT") String duration,
                               @RequestParam(value = "returnTo", required = false) String returnTo,
                               RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // ↩️ the page the officer acted from, or /admin
        if (!BAN_DURATIONS.contains(duration)) {
            redirectAttributes.addFlashAttribute("error", "Choose a ban duration: 30 days, 6 months or permanent.");
            return back;
        }
        Visitor v = visitorId == null ? null : repo.findById(visitorId).orElse(null);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Visitor ID not found in database.");
            return back;
        }
        // 🪪 The email and phone number (refused) and the name (flagged), IdentityBans
        String email = com.paf.aerovisit.service.IdentityBans.email(v.getEmail());
        String phone = com.paf.aerovisit.service.IdentityBans.phoneOf(v);
        if (email == null && phone == null) {
            redirectAttributes.addFlashAttribute("error", "Nothing to ban " + v.getName() + " by: this booking has no"
                    + " email or phone number on file (it may have been anonymized by the 30-day purge). Reject it instead.");
            return back;
        }
        if (faceBanRepo == null) {
            redirectAttributes.addFlashAttribute("error", "The ban list is not available right now.");
            return back;
        }
        String why = (reason == null || reason.isBlank()) ? "Banned (booking " + (v.getReferenceId() != null ? v.getReferenceId() : "#" + v.getId()) + ")" : reason.trim();
        if (why.length() > MAX_BAN_REASON) why = why.substring(0, MAX_BAN_REASON);

        LocalDate today = LocalDate.now();
        LocalDate expiresAt = banExpiry(duration, today);
        List<com.paf.aerovisit.model.BlacklistedFace> existingBans = faceBanRepo.findAll();
        com.paf.aerovisit.model.BlacklistedFace ban = com.paf.aerovisit.service.IdentityBans.contactBan(email, phone, existingBans, today);
        boolean alreadyBanned = ban != null;
        if (alreadyBanned) {
            // Same person banned again: keep the longer of the two bans (permanent wins) and the newest reason
            if (ban.getExpiresAt() != null && (expiresAt == null || expiresAt.isAfter(ban.getExpiresAt()))) ban.setExpiresAt(expiresAt);
            ban.setReason(why);
        } else {
            ban = new com.paf.aerovisit.model.BlacklistedFace();
            ban.setReason(why);
            ban.setExpiresAt(expiresAt);
        }
        if (ban.getEmail() == null) ban.setEmail(email);
        if (ban.getPhone() == null) ban.setPhone(phone);
        // The name for the guards and the name flag on new bookings. A ban that already has one keeps it. No photo.
        if (ban.getName() == null && v.getName() != null && !v.getName().isBlank()) {
            String name = v.getName().trim();
            ban.setName(name.length() > MAX_BAN_REASON ? name.substring(0, MAX_BAN_REASON) : name);
        }
        faceBanRepo.save(ban);
        // Revoked either way: a booking made before the ban must not keep a live pass
        int revoked = revokeOpenBookings(ban, today, why);
        String revokedNote = revoked > 0 ? " " + revoked + " open booking(s) of this person were revoked." : "";
        String newBanNote = "🪪 Visitor banned " + untilText(expiresAt) + ": " + v.getName() + ". New bookings with this email or"
                + " phone number will be refused, and bookings under a similar name will be flagged for review.";
        redirectAttributes.addFlashAttribute("success", alreadyBanned
                ? "🪪 " + v.getName() + " was already banned; the ban now runs " + untilText(ban.getExpiresAt()) + "." + revokedNote
                : newBanNote + revokedNote);
        return back;
    }

    // 🔒 BAN INTEGRITY: every request still waiting for review, plus every approved visit from today on, that the ban
    // matches (same email or phone number) becomes REJECTED with its QR code cleared, so no live pass is left
    // behind. Past approvals and finished visits stay as history. Returns how many bookings were revoked.
    private int revokeOpenBookings(com.paf.aerovisit.model.BlacklistedFace ban, LocalDate today, String reason) {
        List<com.paf.aerovisit.model.BlacklistedFace> justThisBan = List.of(ban);
        int revoked = 0;
        for (Visitor o : repo.findAll()) {
            boolean upcomingApproval = "APPROVED".equals(o.getStatus()) && (o.getVisitDate() == null || !o.getVisitDate().isBefore(today));
            if (!(o.awaitsApproval() || o.awaitsReceipt() || upcomingApproval)) continue;
            if (com.paf.aerovisit.service.IdentityBans.activeBan(o, justThisBan, today) == null) continue;
            o.setStatus("REJECTED");
            o.setQrCodeUrl(null);
            o.setRejectionReason("Access revoked: visitor banned (" + reason + ")");
            repo.save(o);
            revoked++;
        }
        return revoked;
    }

    // 🪪 BANNED VISITORS (sidebar "Banned Visitors"): the bans, newest first, each marked active or expired (expired
    // ones are kept as a record and ignored by every check); lifting one is Director-only
    @GetMapping("/admin/blacklist")
    public String showBannedVisitors(Model model) {
        List<com.paf.aerovisit.model.BlacklistedFace> bans = faceBanRepo == null ? new ArrayList<>() : new ArrayList<>(faceBanRepo.findAll());
        bans.sort(Comparator.comparing(com.paf.aerovisit.model.BlacklistedFace::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        LocalDate today = LocalDate.now();
        model.addAttribute("bans", bans);
        model.addAttribute("activeBanIds", bans.stream().filter(b -> b.activeOn(today)).map(com.paf.aerovisit.model.BlacklistedFace::getId).collect(Collectors.toSet()));
        return "blacklist";
    }

    @PostMapping("/admin/bans/{id}/lift")
    public String liftBan(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id != null && faceBanRepo != null && faceBanRepo.existsById(id)) {
            // An older ban's mugshot goes with the ban (no longer needed once the person may book again)
            Path mugshot = plainUpload(mugshotDir, faceBanRepo.findById(id).map(com.paf.aerovisit.model.BlacklistedFace::getImageUrl).orElse(null));
            faceBanRepo.deleteById(id);
            if (mugshot != null) {
                try {
                    Files.deleteIfExists(mugshot);
                } catch (IOException e) {
                    System.err.println("Could not remove the lifted ban's mugshot: " + e.getMessage());
                }
                if (uploadMirror != null) uploadMirror.deleteUpload(String.valueOf(mugshot.getFileName()));
            }
            redirectAttributes.addFlashAttribute("success", "Ban #" + id + " lifted. This person can book visits again; revoked bookings stay rejected.");
        }
        return "redirect:/admin/blacklist";
    }

    @GetMapping("/admin/staff")
    public String showStaffSchedule(@RequestParam(value = "past", required = false) String past,
                                    @RequestParam(value = "day", required = false) String day, Model model) {
        // 🕰️ "Show past dates": archived entries (dated before today) are listed too, so they can be corrected
        boolean showPast = "1".equals(past) || "true".equalsIgnoreCase(past);
        List<StaffSchedule> allShifts = new ArrayList<>(showPast ? staffRepo.findAllByOrderByDutyDateAscStaffNameAsc()
                : staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc());
        allShifts.sort(ROSTER_ORDER);

        model.addAttribute("schedules", allShifts);
        model.addAttribute("rosterEntries", rosterEntries(allShifts));
        model.addAttribute("groupedSchedules", byDateThenWindow(allShifts));
        model.addAttribute("showPast", showPast);
        LocalDate today = today();
        model.addAttribute("today", today.toString());

        model.addAttribute("newSchedule", new StaffSchedule());
        List<BookingSlot> bookingSlots = bookingRepo.findAll();
        model.addAttribute("bookingSlots", bookingSlots);

        // 🕘 Active Museum Schedule: every slot with its exact time and capacity ("9:30 AM – 10:30 AM · 50 seats (12 booked)")
        Map<String, Integer> booked = seatsBooked(bookingSlots);
        model.addAttribute("slotEntries", slotEntries(bookingSlots, booked));

        // 👮 Duty Roster day view: today unless another day is picked; its windows are exactly that date's booking slots
        LocalDate rosterDay = parseDayOr(day, today);
        List<BookingSlot> daySlots = slotsOn(rosterDay, bookingSlots);
        List<Map<String, Object>> rosterSlots = new ArrayList<>();
        for (BookingSlot s : daySlots) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("window", s.getTimeWindow());
            slot.put("times", slotTimes(s.getTimeWindow()));
            slot.put("summary", slotSummary(s.getTimeWindow(), capacityOf(rosterDay, s.getTimeWindow(), bookingSlots),
                    booked.getOrDefault(rosterDay + "|" + s.getTimeWindow(), 0)));
            rosterSlots.add(slot);
        }
        List<StaffSchedule> dayEntries = new ArrayList<>(staffRepo.findByDutyDateOrderByStaffNameAsc(rosterDay));
        dayEntries.sort(ROSTER_ORDER);
        Map<String, List<Map<String, Object>>> entriesByWindow = new LinkedHashMap<>();
        for (BookingSlot s : daySlots) entriesByWindow.put(s.getTimeWindow(), new ArrayList<>());
        List<Map<String, Object>> otherTimes = new ArrayList<>();
        for (Map<String, Object> e : rosterEntries(dayEntries)) {
            List<Map<String, Object>> bucket = entriesByWindow.get((String) e.get("shiftTime"));
            (bucket != null ? bucket : otherTimes).add(e);
        }
        model.addAttribute("rosterDay", rosterDay.toString());
        model.addAttribute("rosterDayLabel", rosterDay.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)));
        model.addAttribute("rosterDayPast", rosterDay.isBefore(today));
        model.addAttribute("rosterSlots", rosterSlots);
        model.addAttribute("rosterDayEntries", entriesByWindow);
        model.addAttribute("rosterOtherTimes", otherTimes);
        // 🧲 The drag-and-drop day panel draws itself from this (the same JSON as /admin/staff/roster/day), so the day
        // shows at once without a request
        model.addAttribute("rosterDayData", rosterDay(rosterDay, bookingSlots, booked, dayEntries));
        // The Edit window's choices for whichever date is picked in it: date -> [{value: slot label, text: exact times}]
        Map<String, List<Map<String, String>>> slotWindows = new TreeMap<>();
        for (BookingSlot s : bookingSlots) {
            if (s.getSlotDate() == null || s.getTimeWindow() == null || slotWindows.containsKey(s.getSlotDate().toString())) continue;
            List<Map<String, String>> options = new ArrayList<>();
            for (BookingSlot w : slotsOn(s.getSlotDate(), bookingSlots)) options.add(Map.of("value", w.getTimeWindow(), "text", slotTimes(w.getTimeWindow())));
            slotWindows.put(s.getSlotDate().toString(), options);
        }
        model.addAttribute("slotWindows", slotWindows);

        // 🎌 Holidays & Closed Days: the list (upcoming first) and their dates for the Active Museum Schedule
        List<com.paf.aerovisit.model.Holiday> holidayList = holidays == null ? List.of() : holidays.listed(today);
        model.addAttribute("holidays", holidayList);
        List<Map<String, String>> holidayDays = new ArrayList<>();
        if (holidays != null) {
            LocalDate last = bookingSlots.stream().map(BookingSlot::getSlotDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(today);
            LocalDate from = today.minusMonths(1), to = (last.isAfter(today) ? last : today).plusYears(1);
            holidays.between(from, to).forEach((d, h) -> holidayDays.add(Map.of("date", d.toString(), "title", com.paf.aerovisit.service.Holidays.closedReason(h))));
        }
        model.addAttribute("holidayDays", holidayDays);
        // 🟢 Duty Roster calendar: a date with at least one public booking slot is shaded as "museum open" and can be
        // clicked. A date under an emergency closure or a listed holiday still has its slots, so it is left out here.
        Set<LocalDate> closedDates = new HashSet<>(repo.findClosureDates());
        holidayDays.forEach(h -> closedDates.add(LocalDate.parse(h.get("date"))));
        model.addAttribute("openDates", bookingSlots.stream()
                .map(BookingSlot::getSlotDate)
                .filter(d -> d != null && !closedDates.contains(d))
                .map(LocalDate::toString)
                .collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("newSlot", new BookingSlot());
        model.addAttribute("employees", employeeRepo.findAll());
        model.addAttribute("timeWindows", timeWindowRepo.findAllByOrderByStartTimeAsc());
        model.addAttribute("upcomingClosures", closureRepo.findByClosureDateGreaterThanEqualOrderByClosureDateAsc(LocalDate.now()));
        return "staff";
    }

    // Statuses an emergency closure cancels: approved visits and every request still waiting for review
    private static final Set<String> CLOSURE_CANCELLABLE_STATUSES = Set.of("APPROVED", "PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED");

    // 🚨 Emergency "red button": closes a date to all bookings, cancels its active bookings and emails each visitor.
    // Director-only (it lives under /admin/slots/**). Declaring an already-closed date again keeps the first reason
    // and re-runs the sweep, so a closure that was interrupted can be finished.
    @PostMapping("/admin/slots/closure/execute")
    public String executeEmergencyClosure(@RequestParam("closureDate") String closureDateStr,
                                          @RequestParam("reason") String reason,
                                          RedirectAttributes redirectAttributes) {
        LocalDate closureDate;
        try {
            closureDate = LocalDate.parse(closureDateStr);
        } catch (java.time.format.DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Emergency closure not declared: please pick a valid date.");
            return "redirect:/admin/staff";
        }
        if (closureDate.isBefore(LocalDate.now())) {
            redirectAttributes.addFlashAttribute("error", "Emergency closure not declared: " + closureDate + " has already passed.");
            return "redirect:/admin/staff";
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 255) {
            redirectAttributes.addFlashAttribute("error", "Emergency closure not declared: the reason is required (up to 255 characters).");
            return "redirect:/admin/staff";
        }

        // 1. Lock the date first, so no new booking can slip in while visitors are being cancelled
        MuseumClosure closure = closureRepo.findByClosureDate(closureDate)
                .orElseGet(() -> closureRepo.save(new MuseumClosure(closureDate, cleanReason)));

        // 2. Cancel every active booking on that date in one save. 💳 A booking already paid (or whose receipt is being
        // checked) keeps its payment and is rescheduled instead: the visitor picks a new date in Manage Booking.
        List<Visitor> affected = new ArrayList<>();
        int rescheduled = 0;
        for (Visitor v : repo.findByVisitDate(closureDate)) {
            if (CLOSURE_CANCELLABLE_STATUSES.contains(v.getStatus())) {
                if (v.paymentRequired() && v.paymentInHand()) {
                    v.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
                    v.setReminderSent(false); // 📅 now tracks the one "pick a new date" reminder
                    rescheduled++;
                } else {
                    v.setStatus("CANCELLED");
                }
                v.setQrCodeUrl(null);
                affected.add(v);
            }
        }
        repo.saveAll(affected);

        // 3. Email each visitor; one failed email must not stop the rest of the batch
        int failed = 0;
        for (Visitor v : affected) {
            try {
                if (Visitor.STATUS_RESCHEDULE_REQUIRED.equals(v.getStatus())) emailService.sendClosureReschedule(v, closure.getReason());
                else emailService.sendEmergencyCancellation(v, closure.getReason());
            } catch (Exception mailError) {
                failed++;
                System.err.println("❌ Emergency closure email failed for visitor #" + v.getId() + ": " + mailError.getMessage());
            }
        }

        redirectAttributes.addFlashAttribute("success", "🚨 Emergency closure declared for " + closureDate + " (" + closure.getReason() + "). "
                + (affected.size() - rescheduled) + " booking(s) cancelled"
                + (rescheduled > 0 ? ", " + rescheduled + " paid booking(s) asked to reschedule" : "")
                + ", " + (affected.size() - failed) + " visitor(s) notified by email.");
        if (failed > 0) {
            redirectAttributes.addFlashAttribute("error", "⚠️ " + failed + " cancellation email(s) could not be sent. Contact those visitors directly (their bookings show as Cancelled (Closure) in the Audit Log).");
        }
        return "redirect:/admin/staff";
    }

    // Reopen a closed date (e.g. the red button was pressed by mistake): only unlocks public booking.
    // Bookings the closure cancelled stay cancelled, because those visitors were already emailed; they may book again.
    @PostMapping("/admin/slots/closure/{id}/delete")
    public String reopenClosedDate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        MuseumClosure closure = closureRepo.findById(id).orElse(null);
        if (closure == null) {
            redirectAttributes.addFlashAttribute("error", "That closure no longer exists.");
            return "redirect:/admin/staff";
        }
        closureRepo.delete(closure);
        redirectAttributes.addFlashAttribute("success", "✅ " + closure.getClosureDate() + " is open for public booking again. "
                + "Bookings cancelled by the closure stay cancelled; those visitors were already emailed and can book again.");
        return "redirect:/admin/staff";
    }

    // 👤 STAFF DIRECTORY (names only): officers are added, renamed and removed by full name / rank. Removing an officer
    // never touches the Duty Roster: their entries keep showing the name.
    static final int NAME_MAX = 255;

    @PostMapping("/admin/employee/add")
    public String addEmployee(@RequestParam("name") String name, RedirectAttributes redirectAttributes) {
        String newName = name.trim();
        if (newName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Add failed: the officer's name is required.");
            return "redirect:/admin/staff";
        }
        if (newName.length() > NAME_MAX) {
            redirectAttributes.addFlashAttribute("error", "Add failed: the name can be at most " + NAME_MAX + " characters.");
            return "redirect:/admin/staff";
        }
        MuseumEmployee emp = new MuseumEmployee();
        emp.setName(newName);
        employeeRepo.save(emp);
        redirectAttributes.addFlashAttribute("success", "✅ Officer " + newName + " added to the official museum roster.");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/employee/edit/{id}")
    public String editEmployee(@PathVariable Long id, @RequestParam("name") String name, RedirectAttributes redirectAttributes) {
        MuseumEmployee emp = employeeRepo.findById(id).orElse(null);
        if (emp == null) {
            redirectAttributes.addFlashAttribute("error", "Update failed: that officer is no longer on the roster.");
            return "redirect:/admin/staff";
        }
        String newName = name.trim();
        if (newName.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Update failed: the officer's name is required.");
            return "redirect:/admin/staff";
        }
        if (newName.length() > NAME_MAX) {
            redirectAttributes.addFlashAttribute("error", "Update failed: the name can be at most " + NAME_MAX + " characters.");
            return "redirect:/admin/staff";
        }

        String oldName = emp.getName();
        emp.setName(newName);
        employeeRepo.save(emp);

        // Duty shifts point to an officer by name, so a rename is carried over to their active shifts.
        // Archived shifts keep the name they were worked under.
        if (oldName != null && !oldName.equals(newName)) {
            List<StaffSchedule> theirShifts = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc().stream()
                    .filter(s -> oldName.equals(s.getStaffName()))
                    .collect(Collectors.toList());
            theirShifts.forEach(s -> s.setStaffName(newName));
            staffRepo.saveAll(theirShifts);
        }

        redirectAttributes.addFlashAttribute("success", "✅ Officer " + newName + " updated.");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/employee/delete/{id}")
    public String deleteEmployee(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id != null) {
            employeeRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Officer removed from the official roster.");
        }
        return "redirect:/admin/staff";
    }

    // 🕘 SLOT TIMES. A slot's window label ("Morning (09:30 AM - 10:30 AM)") as its exact times, "9:30 AM – 10:30 AM";
    // a label whose times can't be read is shown as it is.
    private static final java.time.format.DateTimeFormatter SLOT_TIME = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static String slotTimes(String label) {
        LocalTime[] hours = shiftHours(label);
        if (hours == null) return label == null || label.isBlank() ? "No time window" : label.trim();
        return hours[0].format(SLOT_TIME) + " – " + hours[1].format(SLOT_TIME);
    }

    // "9:30 AM – 10:30 AM · 50 seats (12 booked)": how Slots & Staff lists a slot
    static String slotSummary(String label, int capacity, int booked) {
        return slotTimes(label) + " · " + capacity + (capacity == 1 ? " seat" : " seats") + " (" + booked + " booked)";
    }

    // Seats held per "date|window" (pending, approved and checked-in bookings), counted by the database
    private Map<String, Integer> seatsBooked(List<BookingSlot> slots) {
        Map<String, Integer> booked = new HashMap<>();
        LocalDate first = slots.stream().map(BookingSlot::getSlotDate).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        if (first == null) return booked;
        List<Object[]> rows = repo.sumSeatsHeldFrom(first, Visitor.SEAT_HOLDING_STATUSES);
        if (rows == null) return booked;
        for (Object[] row : rows) {
            if (row[0] != null && row[1] != null && row[2] != null) booked.merge(row[0] + "|" + row[1], ((Number) row[2]).intValue(), Integer::sum);
        }
        return booked;
    }

    // The Active Museum Schedule's blocks: one per slot, ordered within a day by start time
    static List<Map<String, Object>> slotEntries(List<BookingSlot> slots, Map<String, Integer> booked) {
        List<BookingSlot> sorted = new ArrayList<>(slots);
        sorted.sort(SLOT_ORDER);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (BookingSlot s : sorted) {
            if (s.getSlotDate() == null) continue;
            LocalTime start = windowStart(s.getTimeWindow());
            int seatsBooked = booked.getOrDefault(s.getSlotDate() + "|" + s.getTimeWindow(), 0);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", s.getId());
            e.put("date", s.getSlotDate().toString());
            e.put("timeWindow", s.getTimeWindow() == null ? "" : s.getTimeWindow());
            e.put("maxCapacity", s.getMaxCapacity());
            e.put("booked", seatsBooked);
            e.put("order", start == null ? 24 * 60 : start.getHour() * 60 + start.getMinute());
            e.put("times", slotTimes(s.getTimeWindow()));
            e.put("summary", slotSummary(s.getTimeWindow(), s.getMaxCapacity(), seatsBooked));
            entries.add(e);
        }
        return entries;
    }

    // Date, then the window's start time (unreadable labels last), then the label
    static final Comparator<BookingSlot> SLOT_ORDER = Comparator
            .comparing(BookingSlot::getSlotDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing((BookingSlot s) -> windowStart(s.getTimeWindow()), Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()))
            .thenComparing((BookingSlot s) -> s.getTimeWindow() == null ? "" : s.getTimeWindow());

    // That date's booking slots, one per window label, in time order: the Duty Roster's windows for the date
    static List<BookingSlot> slotsOn(LocalDate date, List<BookingSlot> all) {
        Map<String, BookingSlot> byWindow = new LinkedHashMap<>();
        List<BookingSlot> sorted = new ArrayList<>(all);
        sorted.sort(SLOT_ORDER);
        for (BookingSlot s : sorted) {
            if (date.equals(s.getSlotDate()) && s.getTimeWindow() != null && !s.getTimeWindow().isBlank()) byWindow.putIfAbsent(s.getTimeWindow(), s);
        }
        return new ArrayList<>(byWindow.values());
    }

    // Seats of one date + window (two slots with the same label add up, as on the booking page)
    private static int capacityOf(LocalDate date, String window, List<BookingSlot> all) {
        return all.stream().filter(s -> date.equals(s.getSlotDate()) && window.equals(s.getTimeWindow())).mapToInt(BookingSlot::getMaxCapacity).sum();
    }

    private static LocalDate parseDayOr(String day, LocalDate fallback) {
        if (day == null || day.isBlank()) return fallback;
        try {
            return LocalDate.parse(day.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    // Back to the Duty Roster on the given day (or today when the date couldn't be read)
    private static String rosterRedirect(String dutyDateStr) {
        LocalDate day = parseDayOr(dutyDateStr, null);
        return day == null ? "redirect:/admin/staff#roster" : "redirect:/admin/staff?day=" + day + "#roster";
    }

    // ⛔ CLONING RULE: one officer can't hold two overlapping shifts on the same date.
    private static final Pattern MILITARY_WINDOW = Pattern.compile("(\\d{2})(\\d{2})H\\s*-\\s*(\\d{2})(\\d{2})H");

    // {start, end} of a shift label: "Midday (11:00 AM - 02:00 PM)" (configured windows, read by the gate's own parser)
    // or "Morning (0800H-1200H)" (the three original shifts). Null when the label can't be read or the end isn't after the start.
    static LocalTime[] shiftHours(String shiftTime) {
        if (shiftTime == null) return null;
        LocalTime[] clock = GuardOperationController.parseTimeWindow(shiftTime);
        if (clock != null) return clock;
        Matcher m = MILITARY_WINDOW.matcher(shiftTime);
        if (!m.find()) return null;
        int sh = Integer.parseInt(m.group(1)), sm = Integer.parseInt(m.group(2));
        int eh = Integer.parseInt(m.group(3)), em = Integer.parseInt(m.group(4));
        if (sh > 23 || eh > 23 || sm > 59 || em > 59) return null;
        LocalTime start = LocalTime.of(sh, sm), end = LocalTime.of(eh, em);
        return end.isAfter(start) ? new LocalTime[] { start, end } : null;
    }

    // startA < endB && startB < endA: a shift ending at 12:00 and one starting at 12:00 do not overlap.
    // If either label can't be read, only an identical label counts as a clash (never a guess).
    static boolean shiftsOverlap(String a, String b) {
        LocalTime[] x = shiftHours(a), y = shiftHours(b);
        if (x == null || y == null) return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
        return x[0].isBefore(y[1]) && y[0].isBefore(x[1]);
    }

    // Officer identity for matching: trimmed, inner spaces collapsed, case ignored. Null (= never checked) for blank names
    // and the open-shift placeholders older entries may still carry ("⚠️ OPEN SHIFT (AI)").
    static String officerKey(String staffName) {
        if (staffName == null) return null;
        String key = staffName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return key.isEmpty() || key.contains("open shift") ? null : key;
    }

    // The officer's shift on that date that the new one would overlap, or null. sameDay = that date's shifts;
    // ignoreId = the shift being edited. Archived entries count too: they only exist for past dates, so they matter
    // only when a past entry is corrected, and then that day's other entries must not be doubled up either.
    static StaffSchedule overlappingShift(String staffName, LocalDate date, String shiftTime, Long ignoreId, List<StaffSchedule> sameDay) {
        String key = officerKey(staffName);
        if (key == null || date == null) return null;
        for (StaffSchedule s : sameDay) {
            if (ignoreId != null && ignoreId.equals(s.getId())) continue;
            if (date.equals(s.getDutyDate()) && key.equals(officerKey(s.getStaffName())) && shiftsOverlap(shiftTime, s.getShiftTime())) {
                return s;
            }
        }
        return null;
    }

    private static String overlapError(String staffName, LocalDate date, String shiftTime, StaffSchedule clash) {
        return "⛔ " + staffName.trim() + " is already on duty " + clash.getShiftTime() + " on " + date
             + ", which overlaps " + shiftTime + ". Nothing was saved.";
    }

    // 🗓️ Roster order everywhere: date, then the time window's start time, then name (case ignored)
    static final Comparator<StaffSchedule> ROSTER_ORDER = Comparator
            .comparing(StaffSchedule::getDutyDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
            .thenComparing((StaffSchedule s) -> windowStart(s.getShiftTime()), Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()))
            .thenComparing((StaffSchedule s) -> s.getShiftTime() == null ? "" : s.getShiftTime())
            .thenComparing((StaffSchedule s) -> s.getStaffName() == null ? "" : s.getStaffName().toLowerCase(Locale.ROOT));

    private static LocalTime windowStart(String shiftTime) {
        LocalTime[] hours = shiftHours(shiftTime);
        return hours == null ? null : hours[0];
    }

    // Date -> time window -> entries, in the order given (ROSTER_ORDER): the Duty Roster list
    static Map<LocalDate, Map<String, List<StaffSchedule>>> byDateThenWindow(List<StaffSchedule> sorted) {
        Map<LocalDate, Map<String, List<StaffSchedule>>> grouped = new LinkedHashMap<>();
        for (StaffSchedule s : sorted) {
            if (s.getDutyDate() == null) continue;
            grouped.computeIfAbsent(s.getDutyDate(), d -> new LinkedHashMap<>())
                   .computeIfAbsent(s.getShiftTime() == null ? "" : s.getShiftTime(), w -> new ArrayList<>())
                   .add(s);
        }
        return grouped;
    }

    // What the roster pages' scripts get for each entry: id, name, date ('YYYY-MM-DD') and time window only, so the
    // unused old fields (role, roster email flag) never reach the page
    static List<Map<String, Object>> rosterEntries(List<StaffSchedule> shifts) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (StaffSchedule s : shifts) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", s.getId());
            entry.put("staffName", s.getStaffName() == null ? "" : s.getStaffName());
            entry.put("dutyDate", s.getDutyDate() == null ? null : s.getDutyDate().toString());
            entry.put("shiftTime", s.getShiftTime() == null ? "" : s.getShiftTime());
            entries.add(entry);
        }
        return entries;
    }

    // A date the museum is open for visits: it has at least one public booking slot
    private boolean museumOpenOn(LocalDate date) {
        return bookingRepo.findAll().stream().anyMatch(slot -> date.equals(slot.getSlotDate()));
    }

    private static String pastDateError(LocalDate date) {
        return "⛔ " + date + " is in the past. Past dates can be corrected, but not newly filled. Nothing was saved.";
    }

    private static String notASlotError(String shiftTime, LocalDate date) {
        return "⛔ \"" + shiftTime + "\" is not one of the booking slots on " + date + ". Nothing was saved.";
    }

    // That date's slot window labels (the windows a roster entry on that date may use)
    private Set<String> slotWindowsOn(LocalDate date) {
        Set<String> windows = new LinkedHashSet<>();
        for (BookingSlot s : slotsOn(date, bookingRepo.findAll())) windows.add(s.getTimeWindow());
        return windows;
    }

    // 👮 DUTY ROSTER: one staff member, one date, and one or more of that date's booking slots per Add ("Whole day" ticks
    // them all). Only today or later, only a date with public booking slots, and never overlapping the same person's other
    // shift that day; back-to-back windows (one ends as the next starts) don't overlap. Any real clash saves nothing.
    @PostMapping("/admin/staff/add")
    public String addStaffSchedule(
            @RequestParam("staffName") String staffName,
            @RequestParam("dutyDate") String dutyDateStr,
            @RequestParam(value = "shiftTime", required = false) List<String> shiftTimes,
            RedirectAttributes redirectAttributes) {

        String back = rosterRedirect(dutyDateStr);
        String name = staffName.trim();
        if (officerKey(name) == null) {
            redirectAttributes.addFlashAttribute("error", "Choose a staff member from the Staff Directory. Nothing was saved.");
            return back;
        }
        List<String> windows = new ArrayList<>(new LinkedHashSet<>(shiftTimes == null ? List.<String>of() : shiftTimes));
        windows.removeIf(w -> w == null || w.isBlank());
        if (windows.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Tick at least one time window. Nothing was saved.");
            return back;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(dutyDateStr.trim());
        } catch (java.time.format.DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Please pick a valid date. Nothing was saved.");
            return back;
        }
        if (date.isBefore(today())) {
            redirectAttributes.addFlashAttribute("error", pastDateError(date));
            return back;
        }
        Set<String> daySlots = slotWindowsOn(date);
        if (daySlots.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "❌ No shift assigned: the museum has no open public booking slots on " + date + ".");
            return back;
        }
        for (String w : windows) {
            if (!daySlots.contains(w)) {
                redirectAttributes.addFlashAttribute("error", notASlotError(w, date));
                return back;
            }
        }
        // Each ticked window against the person's other shifts that day and against the windows ticked before it
        List<StaffSchedule> sameDay = new ArrayList<>(staffRepo.findByDutyDateOrderByStaffNameAsc(date));
        List<StaffSchedule> toSave = new ArrayList<>();
        for (String w : windows) {
            StaffSchedule clash = overlappingShift(name, date, w, null, sameDay);
            if (clash != null) {
                redirectAttributes.addFlashAttribute("error", overlapError(name, date, w, clash));
                return back;
            }
            StaffSchedule sched = new StaffSchedule();
            sched.setStaffName(name);
            sched.setDutyDate(date);
            sched.setShiftTime(w);
            sameDay.add(sched);
            toSave.add(sched);
        }

        toSave.forEach(staffRepo::save);
        if (windows.size() == 1) {
            redirectAttributes.addFlashAttribute("success", "✅ " + name + " is on duty " + windows.get(0) + " on " + date + ".");
        } else {
            redirectAttributes.addFlashAttribute("success", "✅ " + name + " is on duty on " + date + ": "
                    + windows.stream().map(ManagementController::slotTimes).collect(Collectors.joining(", ")) + ".");
        }
        return back;
    }

    @PostMapping("/admin/staff/delete/{id}")
    public String deleteStaffSchedule(@PathVariable Long id) {
        if (id != null) {
            staffRepo.deleteById(id);
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/ajax-delete/{id}")
    @ResponseBody
    public String ajaxDeleteStaffSchedule(@PathVariable Long id) {
        if (id != null) {
            staffRepo.deleteById(id);
        }
        return "OK";
    }

    @PostMapping("/admin/staff/ajax-bulk-delete")
    @ResponseBody
    public String ajaxBulkDeleteStaffSchedule(@RequestParam("ids") List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            staffRepo.deleteAllById(ids);
        }
        return "OK";
    }

    // 🧲 DUTY ROSTER, DRAG AND DROP (Director only: everything here is under /admin/staff/**). The day panel shows one
    // date's booking-slot windows and the Staff Directory as a pool of names; each drop is saved at once in the background
    // (assign, move), and a name is removed with the existing /admin/staff/ajax-delete/{id}. The rules are the ones of
    // /admin/staff/add: today or later, only that date's slot windows, no overlapping shift for the same person. Several
    // names may share a window, and one name may cover several windows the same day. A listed holiday or an emergency
    // closure is closed: nothing can be added there.

    // Why the date can't take names (holiday, emergency closure, no booking slots), or null when it is open
    private String rosterClosedReason(LocalDate date, List<BookingSlot> daySlots) {
        com.paf.aerovisit.model.Holiday holiday = holidays == null ? null : holidays.on(date).orElse(null);
        if (holiday != null) return com.paf.aerovisit.service.Holidays.closedReason(holiday);
        if (repo.findClosureDates().contains(date)) return "Closed: emergency closure";
        if (daySlots.isEmpty()) return "No booking slots on this date";
        return null;
    }

    // One date for the day panel: {date, label, past, open, editable, closedReason, windows:[{window, times, summary}],
    // entries:[{id, staffName, dutyDate, shiftTime}], staff:[names]}. A past open date can only have names removed, so it
    // gets no pool; neither does a closed date.
    Map<String, Object> rosterDay(LocalDate date, List<BookingSlot> allSlots, Map<String, Integer> booked, List<StaffSchedule> dayEntries) {
        List<BookingSlot> daySlots = slotsOn(date, allSlots);
        String closedReason = rosterClosedReason(date, daySlots);
        boolean past = date.isBefore(today());
        boolean editable = closedReason == null && !past;
        List<Map<String, Object>> windows = new ArrayList<>();
        for (BookingSlot s : daySlots) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("window", s.getTimeWindow());
            w.put("times", slotTimes(s.getTimeWindow()));
            w.put("summary", slotSummary(s.getTimeWindow(), capacityOf(date, s.getTimeWindow(), allSlots), booked.getOrDefault(date + "|" + s.getTimeWindow(), 0)));
            windows.add(w);
        }
        List<StaffSchedule> sorted = new ArrayList<>(dayEntries);
        sorted.sort(ROSTER_ORDER);
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", date.toString());
        day.put("label", date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)));
        day.put("past", past);
        day.put("open", closedReason == null);
        day.put("editable", editable);
        day.put("closedReason", closedReason);
        day.put("windows", windows);
        day.put("entries", rosterEntries(sorted));
        day.put("staff", editable ? directoryNames() : List.of());
        return day;
    }

    // Staff Directory names, trimmed, each once, A to Z (case ignored): the drag-and-drop pool
    private List<String> directoryNames() {
        Map<String, String> byKey = new TreeMap<>();
        for (MuseumEmployee e : employeeRepo.findAll()) {
            String key = officerKey(e.getName());
            if (key != null) byKey.putIfAbsent(key, e.getName().trim());
        }
        return new ArrayList<>(byKey.values());
    }

    private static org.springframework.http.ResponseEntity<Map<String, Object>> rosterRefusal(org.springframework.http.HttpStatus status, String error) {
        return org.springframework.http.ResponseEntity.status(status).body(Map.of("error", error));
    }

    // GET one date for the day panel (a calendar click, the date picker, Today)
    @GetMapping("/admin/staff/roster/day")
    @ResponseBody
    public Map<String, Object> rosterDayJson(@RequestParam(value = "date", required = false) String date) {
        LocalDate day = parseDayOr(date, today());
        List<BookingSlot> all = bookingRepo.findAll();
        return rosterDay(day, all, seatsBooked(slotsOn(day, all)), staffRepo.findByDutyDateOrderByStaffNameAsc(day));
    }

    // Drop a Staff Directory name onto a window: 200 with the new entry, or 4xx with {error} and nothing saved
    @PostMapping("/admin/staff/roster/assign")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> rosterAssign(
            @RequestParam("staffName") String staffName,
            @RequestParam("dutyDate") String dutyDateStr,
            @RequestParam("shiftTime") String shiftTime) {
        String name = staffName.trim();
        if (officerKey(name) == null || directoryNames().stream().noneMatch(n -> officerKey(n).equals(officerKey(name)))) {
            return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "Choose a staff member from the Staff Directory. Nothing was saved.");
        }
        LocalDate date = parseDayOr(dutyDateStr, null);
        if (date == null) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "Please pick a valid date. Nothing was saved.");
        if (date.isBefore(today())) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, pastDateError(date));
        String closed = rosterClosedReason(date, slotsOn(date, bookingRepo.findAll()));
        if (closed != null) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "⛔ " + date + ": " + closed + ". Nothing was saved.");
        if (!slotWindowsOn(date).contains(shiftTime)) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, notASlotError(shiftTime, date));
        StaffSchedule clash = overlappingShift(name, date, shiftTime, null, staffRepo.findByDutyDateOrderByStaffNameAsc(date));
        if (clash != null) return rosterRefusal(org.springframework.http.HttpStatus.CONFLICT, overlapError(name, date, shiftTime, clash));

        StaffSchedule sched = new StaffSchedule();
        sched.setStaffName(name);
        sched.setDutyDate(date);
        sched.setShiftTime(shiftTime);
        return org.springframework.http.ResponseEntity.ok(rosterEntries(List.of(staffRepo.save(sched))).get(0));
    }

    // Drag a placed name onto another window of the same date: 200 with the entry, or 4xx with {error} and nothing changed
    @PostMapping("/admin/staff/roster/move")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> rosterMove(@RequestParam("id") Long id, @RequestParam("shiftTime") String shiftTime) {
        StaffSchedule sched = staffRepo.findById(id).orElse(null);
        if (sched == null) return rosterRefusal(org.springframework.http.HttpStatus.NOT_FOUND, "That assignment no longer exists. Please refresh the page.");
        LocalDate date = sched.getDutyDate();
        if (date == null || date.isBefore(today())) {
            return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "⛔ A past entry can only be corrected from the Shift Directory. Nothing was saved.");
        }
        String closed = rosterClosedReason(date, slotsOn(date, bookingRepo.findAll()));
        if (closed != null) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, "⛔ " + date + ": " + closed + ". Nothing was saved.");
        if (!slotWindowsOn(date).contains(shiftTime)) return rosterRefusal(org.springframework.http.HttpStatus.BAD_REQUEST, notASlotError(shiftTime, date));
        StaffSchedule clash = overlappingShift(sched.getStaffName(), date, shiftTime, sched.getId(), staffRepo.findByDutyDateOrderByStaffNameAsc(date));
        if (clash != null) return rosterRefusal(org.springframework.http.HttpStatus.CONFLICT, overlapError(sched.getStaffName(), date, shiftTime, clash));

        sched.setShiftTime(shiftTime);
        return org.springframework.http.ResponseEntity.ok(rosterEntries(List.of(staffRepo.save(sched))).get(0));
    }

    // ✏️ Edit one assignment: name and time window, and the date for an entry dated today or later. An entry dated before
    // today (shown with "Show past dates") can be corrected but stays on its own date, so a past date is never newly filled.
    @PostMapping("/admin/staff/update")
    public String updateStaffSchedule(
            @RequestParam("id") Long id,
            @RequestParam("staffName") String staffName,
            @RequestParam("dutyDate") String dutyDateStr,
            @RequestParam("shiftTime") String shiftTime,
            RedirectAttributes redirectAttributes) {

        StaffSchedule sched = staffRepo.findById(id).orElse(null);
        if (sched == null) {
            redirectAttributes.addFlashAttribute("error", "Error: Shift record could not be found.");
            return rosterRedirect(dutyDateStr);
        }
        String back = rosterRedirect(dutyDateStr);
        String name = staffName.trim();
        if (officerKey(name) == null) {
            redirectAttributes.addFlashAttribute("error", "Choose a staff member from the Staff Directory. Nothing was saved.");
            return back;
        }
        LocalDate newDate;
        try {
            newDate = LocalDate.parse(dutyDateStr.trim());
        } catch (java.time.format.DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Please pick a valid date. Nothing was saved.");
            return back;
        }

        LocalDate today = today();
        if (sched.getDutyDate() != null && sched.getDutyDate().isBefore(today)) {
            if (!newDate.equals(sched.getDutyDate())) {
                redirectAttributes.addFlashAttribute("error", "⛔ A past entry can be corrected, but not moved to another date. Nothing was saved.");
                return back;
            }
        } else {
            if (newDate.isBefore(today)) {
                redirectAttributes.addFlashAttribute("error", pastDateError(newDate));
                return back;
            }
            if (!museumOpenOn(newDate)) {
                redirectAttributes.addFlashAttribute("error", "❌ Update failed: The museum has no open booking slots on " + newDate + ".");
                return back;
            }
        }
        // 🧭 The window must be one of that date's booking slots; an entry may keep its own (older) window on its own date
        boolean keepsItsWindow = shiftTime.equals(sched.getShiftTime()) && newDate.equals(sched.getDutyDate());
        if (!keepsItsWindow && !slotWindowsOn(newDate).contains(shiftTime)) {
            redirectAttributes.addFlashAttribute("error", notASlotError(shiftTime, newDate));
            return back;
        }

        StaffSchedule clash = overlappingShift(name, newDate, shiftTime, sched.getId(), staffRepo.findByDutyDateOrderByStaffNameAsc(newDate));
        if (clash != null) {
            redirectAttributes.addFlashAttribute("error", overlapError(name, newDate, shiftTime, clash));
            return back;
        }

        sched.setStaffName(name);
        sched.setDutyDate(newDate);
        sched.setShiftTime(shiftTime);
        staffRepo.save(sched);
        redirectAttributes.addFlashAttribute("success", "✅ Shift assignment successfully updated for " + name + ".");
        return back;
    }

    // 🗑️ CLEAR DATE RANGE: deletes every active shift from startDate to endDate (both included), e.g. after a week was
    // filled by mistake. Archived shifts are history and are never touched.
    @PostMapping("/admin/staff/clear-roster")
    public String clearRoster(@RequestParam("startDate") String startDateStr,
                              @RequestParam("endDate") String endDateStr,
                              RedirectAttributes redirectAttributes) {
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
        } catch (java.time.format.DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Roster not cleared: please pick a valid start and end date.");
            return "redirect:/admin/staff";
        }
        if (startDate.isAfter(endDate)) {
            redirectAttributes.addFlashAttribute("error", "Roster not cleared: the end date cannot be before the start date.");
            return "redirect:/admin/staff";
        }

        List<StaffSchedule> inRange = staffRepo.findByArchivedFalseAndDutyDateBetween(startDate, endDate);
        if (inRange.isEmpty()) {
            redirectAttributes.addFlashAttribute("info", "No shifts found from " + startDate + " to " + endDate + ". Nothing was cleared.");
            return "redirect:/admin/staff";
        }
        staffRepo.deleteAllInBatch(inRange);

        redirectAttributes.addFlashAttribute("success", "🗑️ Successfully cleared " + inRange.size() + (inRange.size() == 1 ? " shift" : " shifts")
                + " from the roster (" + startDate + " to " + endDate + ").");
        return "redirect:/admin/staff";
    }

    // 🗓️ BULK SLOT GENERATOR: one slot for every ticked time window on every included day (maxCapacity is per window).
    // Skips the ticked weekdays, the listed holidays (always) and the one-off excluded dates; a date + window that already
    // has a slot is left as it is and counted as "already existed".
    @PostMapping("/admin/slots/add")
    public String addBookingSlot(
            @RequestParam("startDate") String startDateStr,
            @RequestParam("endDate") String endDateStr,
            @RequestParam(value = "timeWindow", required = false) List<String> timeWindowValues,
            @RequestParam("maxCapacity") int maxCapacity,
            @RequestParam(value = "skipDays", required = false) List<String> skipDayNames,
            @RequestParam(value = "excludeDates", required = false) String excludeDatesStr,
            RedirectAttributes redirectAttributes) {

        // 🗓️ "Skip these days": any days of the week (the form ticks Sunday and Monday, the days the museum is closed)
        Set<java.time.DayOfWeek> skipDays = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        if (skipDayNames != null) {
            for (String day : skipDayNames) {
                try {
                    skipDays.add(java.time.DayOfWeek.valueOf(day.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException | NullPointerException e) {
                    redirectAttributes.addFlashAttribute("error", "Unknown day of the week: " + day + ".");
                    return "redirect:/admin/staff";
                }
            }
        }

        try {
            LocalDate startDate = LocalDate.parse(startDateStr);
            LocalDate endDate = LocalDate.parse(endDateStr);
            
            Set<LocalDate> blackoutDates = new HashSet<>();
            if (excludeDatesStr != null && !excludeDatesStr.trim().isEmpty()) {
                String[] dates = excludeDatesStr.split(",");
                for (String d : dates) {
                    if (!d.trim().isEmpty()) {
                        blackoutDates.add(LocalDate.parse(d.trim()));
                    }
                }
            }

            if (startDate.isAfter(endDate)) {
                redirectAttributes.addFlashAttribute("error", "Time Paradox: End Date cannot be before Start Date.");
                return "redirect:/admin/staff";
            }

            List<String> windows = new ArrayList<>(new LinkedHashSet<>(timeWindowValues == null ? List.<String>of() : timeWindowValues));
            windows.removeIf(w -> w == null || w.isBlank());
            if (windows.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Tick at least one time window. No slots were created.");
                return "redirect:/admin/staff";
            }

            // 🎌 Listed holidays are always skipped; a one-day run on a holiday is refused with its name
            Map<LocalDate, com.paf.aerovisit.model.Holiday> holidayDates = holidays == null ? Map.of() : holidays.between(startDate, endDate);
            if (startDate.equals(endDate) && holidayDates.containsKey(startDate)) {
                redirectAttributes.addFlashAttribute("error", "⛔ No slot created: " + startDate.format(com.paf.aerovisit.service.Holidays.LONG)
                        + " is a holiday (" + holidayDates.get(startDate).getLabel() + ").");
                return "redirect:/admin/staff";
            }

            // A date + window that already has a slot gets no second one (also within this run)
            Set<String> existing = new HashSet<>();
            for (BookingSlot s : bookingRepo.findBySlotDateBetween(startDate, endDate)) existing.add(s.getSlotDate() + "|" + s.getTimeWindow());

            int slotsCreated = 0;
            int alreadyExisted = 0;
            int skippedByWeekday = 0;
            int skippedByDate = 0;
            List<String> skippedHolidays = new ArrayList<>();
            LocalDate currentDate = startDate;

            while (!currentDate.isAfter(endDate)) {
                // A skipped weekday that is also a holiday or an excluded date counts once, as a weekday
                if (skipDays.contains(currentDate.getDayOfWeek())) {
                    skippedByWeekday++;
                } else if (holidayDates.containsKey(currentDate)) {
                    skippedHolidays.add(currentDate.format(com.paf.aerovisit.service.Holidays.SHORT) + " (" + holidayDates.get(currentDate).getLabel() + ")");
                } else if (blackoutDates.contains(currentDate)) {
                    skippedByDate++;
                } else {
                    for (String timeWindow : windows) {
                        if (!existing.add(currentDate + "|" + timeWindow)) {
                            alreadyExisted++;
                            continue;
                        }
                        BookingSlot slot = new BookingSlot();
                        slot.setSlotDate(currentDate);
                        slot.setTimeWindow(timeWindow);
                        slot.setMaxCapacity(maxCapacity);
                        bookingRepo.save(slot);
                        slotsCreated++;
                    }
                }
                currentDate = currentDate.plusDays(1);
            }
            String opened = windows.size() == 1
                    ? slotsCreated + " days successfully opened for " + windows.get(0) + "."
                    : slotsCreated + (slotsCreated == 1 ? " slot" : " slots") + " opened for " + windows.size() + " time windows.";
            redirectAttributes.addFlashAttribute("success", "✅ Bulk Scheduling Complete: " + opened
                    + (alreadyExisted > 0 ? " " + alreadyExisted + " already existed." : "")
                    + (skippedByWeekday > 0 ? " Skipped " + skippedByWeekday + " by weekday (" + dayList(skipDays) + ")." : "")
                    + (skippedByDate > 0 ? " Skipped " + skippedByDate + " by date." : "")
                    + (skippedHolidays.isEmpty() ? "" : " Skipped " + skippedHolidays.size() + (skippedHolidays.size() == 1 ? " holiday: " : " holidays: ")
                            + String.join(", ", skippedHolidays) + "."));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "System Error: Please check your date formatting.");
        }
        return "redirect:/admin/staff";
    }
    
    // "Sun, Mon" in calendar order (Sunday first, as on the form)
    static String dayList(Set<java.time.DayOfWeek> days) {
        List<String> names = new ArrayList<>();
        for (java.time.DayOfWeek day : List.of(java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY)) {
            if (days.contains(day)) names.add(day.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US));
        }
        return String.join(", ", names);
    }

    @PostMapping("/admin/slots/delete/{id}")
    public String deleteBookingSlot(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id != null) {
            bookingRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Booking slot deleted successfully.");
        }
        return "redirect:/admin/staff";
    }

    // 👮 Duty Roster (read-only, guards): the active entries as a calendar and as a list grouped by date, then time window.
    // The Director manages the roster in Slots & Staff -> Duty Roster, so an old Director link is sent there.
    @GetMapping("/admin/calendar")
    public String showCalendar(Model model) {
        if (com.paf.aerovisit.config.SecurityConfig.isDirector()) {
            return "redirect:/admin/staff#roster";
        }
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) { model.addAttribute("loggedInUser", auth.getName()); }
        List<StaffSchedule> allShifts = new ArrayList<>(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc());
        allShifts.sort(ROSTER_ORDER);
        model.addAttribute("shifts", allShifts);
        model.addAttribute("rosterEntries", rosterEntries(allShifts));
        model.addAttribute("groupedShifts", byDateThenWindow(allShifts));
        return "calendar";
    }

    // 🔄 The guards' Duty Roster page re-reads this every 30 s, so a name the Director drops in shows without a refresh.
    // Read-only, the same entries as /admin/calendar (active ones, roster order). Guard and Director (/admin/**).
    @GetMapping("/admin/calendar/entries")
    @ResponseBody
    public List<Map<String, Object>> calendarEntries() {
        List<StaffSchedule> allShifts = new ArrayList<>(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc());
        allShifts.sort(ROSTER_ORDER);
        return rosterEntries(allShifts);
    }
}