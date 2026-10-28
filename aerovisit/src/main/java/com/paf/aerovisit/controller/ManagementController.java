package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.MuseumClosure;
import com.paf.aerovisit.model.StaffLeave;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffLeaveRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

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
    private final PredictiveAdvisorService aiService;
    private final TimeWindowConfigRepository timeWindowRepo;
    private final MuseumClosureRepository closureRepo;

    // 🏖️ VACATION RULE: optional, so the ManagementController test slices that never touch leave need no extra mock
    @Autowired(required = false)
    private StaffLeaveRepository leaveRepo;

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

    public ManagementController(VisitorRepository repo, StaffScheduleRepository staffRepo, BookingSlotRepository bookingRepo, MuseumEmployeeRepository employeeRepo, EmailService emailService, PredictiveAdvisorService aiService, TimeWindowConfigRepository timeWindowRepo, MuseumClosureRepository closureRepo) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.bookingRepo = bookingRepo;
        this.employeeRepo = employeeRepo;
        this.emailService = emailService;
        this.aiService = aiService;
        this.timeWindowRepo = timeWindowRepo;
        this.closureRepo = closureRepo;
    }

    @PostMapping("/admin/delete/{id}")
    public String deleteVisitor(@PathVariable Long id, @RequestParam(value = "returnTo", required = false) String returnTo, RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // ↩️ the page the officer acted from, or /admin
        if (id == null) return back;
        Visitor v = repo.findById(id).orElse(null);
        if (v != null) {
            try {
                Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads");
                if (v.getIdImageUrl() != null) { Files.deleteIfExists(uploadDir.resolve(v.getIdImageUrl())); }
                if (v.getIdCardImageUrl() != null) { Files.deleteIfExists(uploadDir.resolve(v.getIdCardImageUrl())); }
                if (v.getQrCodeUrl() != null) { Files.deleteIfExists(uploadDir.resolve(v.getQrCodeUrl())); }
                if (uploadMirror != null) {
                    uploadMirror.deleteUpload(v.getIdImageUrl());
                    uploadMirror.deleteUpload(v.getIdCardImageUrl());
                    uploadMirror.deleteUpload(v.getQrCodeUrl());
                }
                repo.delete(v);
                if (notificationRepo != null) notificationRepo.deleteByVisitorId(id);
                redirectAttributes.addFlashAttribute("success", "Visitor deleted and storage space freed up!");
            } catch (IOException e) {
                redirectAttributes.addFlashAttribute("error", "Database record deleted, but files could not be removed.");
            }
        }
        return back;
    }

    @PostMapping("/admin/reject-visitor")
    public String rejectVisitor(
            @RequestParam("id") Long id, 
            @RequestParam("presetReason") String presetReason,
            @RequestParam(value = "additionalNotes", required = false) String additionalNotes,
            @RequestParam(value = "returnTo", required = false) String returnTo,
            RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // the officer's Pending view, or /admin as before
        
        Visitor visitor = repo.findById(id).orElse(null);
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

    // 🧬 BAN VISITOR (the only ban in the system; the old email "Block" was removed because a new email beats it):
    // copies the face vector saved with this booking (two-step KYC) into blacklisted_faces, so any future booking with
    // the same face is refused at /save whatever name or email it uses, and approval / check-in refuse it too
    // (GuardOperationController.isFaceBanned). It also revokes every open booking with that face (see revokeOpenBookings).
    // A ban is temporary (30 days, 6 months) or permanent; an expired ban is ignored everywhere (FaceEncodings.isBanned).
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

    // Where booking selfies and ban mugshots live (served to staff at /uploads/**); tests point it at a temp folder
    Path mugshotDir = Paths.get("uploads");

    // The ban keeps its OWN copy of the booking's liveness selfie (step 2), because the 30-day purge and "Delete"
    // remove the booking's file. Returns the copy's file name in dir, or null when there is no selfie or it can't be
    // copied (the ban is still made: the photo is only for the guards). Only a plain file name directly inside dir is read.
    static String copyMugshot(Path dir, String selfie) {
        Path source = plainUpload(dir, selfie);
        if (source == null || !Files.isRegularFile(source)) return null;
        String ext = selfie.substring(selfie.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String fileName = "ban-" + UUID.randomUUID() + (ext.matches("jpe?g|png|webp") ? "." + ext : ".jpg");
        try {
            Files.copy(source, dir.resolve(fileName));
            return fileName;
        } catch (IOException e) {
            System.err.println("Could not keep a mugshot for the ban: " + e.getMessage());
            return null;
        }
    }

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
        // 🪪 The face when the booking has one (AI on); always the email and phone number, so a booking made while AI
        // features were off (no face data) can still be banned (IdentityBans)
        double[] face = com.paf.aerovisit.service.FaceEncodings.parse(v.getFaceEncoding());
        String email = com.paf.aerovisit.service.IdentityBans.email(v.getEmail());
        String phone = com.paf.aerovisit.service.IdentityBans.phoneOf(v);
        if (face == null && email == null && phone == null) {
            redirectAttributes.addFlashAttribute("error", "Nothing to ban " + v.getName() + " by: this booking has no face data,"
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
        com.paf.aerovisit.model.BlacklistedFace ban = com.paf.aerovisit.service.FaceEncodings.activeBan(face, existingBans, today);
        if (ban == null) ban = com.paf.aerovisit.service.IdentityBans.contactBan(email, phone, existingBans, today);
        boolean alreadyBanned = ban != null;
        if (alreadyBanned) {
            // Same person banned again: keep the longer of the two bans (permanent wins) and the newest reason
            if (ban.getExpiresAt() != null && (expiresAt == null || expiresAt.isAfter(ban.getExpiresAt()))) ban.setExpiresAt(expiresAt);
            ban.setReason(why);
            // An identity ban made while AI was off gains the face once a booking of theirs has one
            if (ban.getFaceEncoding() == null && face != null) ban.setFaceEncoding(v.getFaceEncoding());
        } else {
            ban = new com.paf.aerovisit.model.BlacklistedFace();
            ban.setFaceEncoding(face != null ? v.getFaceEncoding() : null);
            ban.setReason(why);
            ban.setExpiresAt(expiresAt);
        }
        if (ban.getEmail() == null) ban.setEmail(email);
        if (ban.getPhone() == null) ban.setPhone(phone);
        // Name + mugshot for the guards (and the name flag on new bookings). A ban that already has them keeps its
        // first ones; one made before these columns existed gets them now.
        if (ban.getName() == null && v.getName() != null && !v.getName().isBlank()) {
            String name = v.getName().trim();
            ban.setName(name.length() > MAX_BAN_REASON ? name.substring(0, MAX_BAN_REASON) : name);
        }
        if (ban.getImageUrl() == null) {
            if (uploadMirror != null) uploadMirror.restoreUpload(v.getIdImageUrl()); // ☁️ after a redeploy the selfie may only be in S3
            ban.setImageUrl(copyMugshot(mugshotDir, v.getIdImageUrl()));
            if (uploadMirror != null && ban.getImageUrl() != null) uploadMirror.pushUpload(ban.getImageUrl());
        }
        faceBanRepo.save(ban);
        // Revoked either way: a booking made before the ban must not keep a live pass
        int revoked = revokeOpenBookings(ban, today, why);
        String revokedNote = revoked > 0 ? " " + revoked + " open booking(s) of this person were revoked." : "";
        String newBanNote = face != null
                ? "🧬 Visitor banned " + untilText(expiresAt) + ": " + v.getName() + ". New bookings with this face will be refused."
                : "🪪 Visitor banned " + untilText(expiresAt) + ": " + v.getName() + ". No face data on file (AI features were off),"
                  + " so new bookings with this email or phone number will be refused, and bookings under a similar name will be"
                  + " flagged for review.";
        redirectAttributes.addFlashAttribute("success", alreadyBanned
                ? "🧬 " + v.getName() + " was already banned; the ban now runs " + untilText(ban.getExpiresAt()) + "." + revokedNote
                : newBanNote + revokedNote);
        return back;
    }

    // 🔒 BAN INTEGRITY: every request still waiting for review, plus every approved visit from today on, that the ban
    // matches (same face, or same email / phone number) becomes REJECTED with its QR code cleared, so no live pass is left
    // behind. Past approvals and finished visits stay as history. Returns how many bookings were revoked.
    private int revokeOpenBookings(com.paf.aerovisit.model.BlacklistedFace ban, LocalDate today, String reason) {
        List<com.paf.aerovisit.model.BlacklistedFace> justThisBan = List.of(ban);
        int revoked = 0;
        for (Visitor o : repo.findAll()) {
            boolean upcomingApproval = "APPROVED".equals(o.getStatus()) && (o.getVisitDate() == null || !o.getVisitDate().isBefore(today));
            if (!(o.awaitsApproval() || upcomingApproval)) continue;
            if (com.paf.aerovisit.service.IdentityBans.activeBan(o, justThisBan, today) == null) continue;
            o.setStatus("REJECTED");
            o.setQrCodeUrl(null);
            o.setRejectionReason("Access revoked: visitor banned (" + reason + ")");
            repo.save(o);
            revoked++;
        }
        return revoked;
    }

    // 🧬 BANNED VISITORS (sidebar "Banned Visitors"): the face bans, newest first, each marked active or expired (expired
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
            // The mugshot goes with the ban (biometric photo, no longer needed once the person may book again)
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
    public String showStaffSchedule(Model model) {
        List<StaffSchedule> allShifts = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc();
        
        model.addAttribute("schedules", allShifts); 
        
        Map<LocalDate, List<StaffSchedule>> groupedSchedules = allShifts.stream()
            .filter(s -> s.getDutyDate() != null)
            .collect(Collectors.groupingBy(StaffSchedule::getDutyDate, TreeMap::new, Collectors.toList()));
        model.addAttribute("groupedSchedules", groupedSchedules);

        model.addAttribute("newSchedule", new StaffSchedule());
        List<BookingSlot> bookingSlots = bookingRepo.findAll();
        model.addAttribute("bookingSlots", bookingSlots);
        // 🟢 Master Duty Roster calendar: a date with at least one public booking slot is shaded as "museum open".
        // A date under an emergency closure still has its slots, so it is left out here.
        Set<LocalDate> closedDates = new HashSet<>(repo.findClosureDates());
        model.addAttribute("openDates", bookingSlots.stream()
                .map(BookingSlot::getSlotDate)
                .filter(d -> d != null && !closedDates.contains(d))
                .map(LocalDate::toString)
                .collect(Collectors.toCollection(TreeSet::new)));
        model.addAttribute("newSlot", new BookingSlot());
        List<MuseumEmployee> employees = employeeRepo.findAll();
        model.addAttribute("employees", employees);
        // 👻 GHOST RULE: the shift dropdowns only offer officers who can still be scheduled
        model.addAttribute("activeEmployees", employees.stream().filter(MuseumEmployee::isActive).collect(Collectors.toList()));
        model.addAttribute("timeWindows", timeWindowRepo.findAllByOrderByStartTimeAsc());
        model.addAttribute("shiftOptions", windowShiftOptions()); // AI roster preview: only real time windows
        model.addAttribute("upcomingClosures", closureRepo.findByClosureDateGreaterThanEqualOrderByClosureDateAsc(LocalDate.now()));
        model.addAttribute("upcomingLeaves", leaveRepo == null ? List.of() : leaveRepo.findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate.now()));
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

        // 2. Cancel every active booking on that date in one save
        List<Visitor> affected = new ArrayList<>();
        for (Visitor v : repo.findByVisitDate(closureDate)) {
            if (CLOSURE_CANCELLABLE_STATUSES.contains(v.getStatus())) {
                v.setStatus("CANCELLED");
                v.setQrCodeUrl(null);
                affected.add(v);
            }
        }
        repo.saveAll(affected);

        // 3. Email each visitor; one failed email must not stop the rest of the batch
        int failed = 0;
        for (Visitor v : affected) {
            try {
                emailService.sendEmergencyCancellation(v, closure.getReason());
            } catch (Exception mailError) {
                failed++;
                System.err.println("❌ Emergency closure email failed for visitor #" + v.getId() + ": " + mailError.getMessage());
            }
        }

        redirectAttributes.addFlashAttribute("success", "🚨 Emergency closure declared for " + closureDate + " (" + closure.getReason() + "). "
                + affected.size() + " booking(s) cancelled, " + (affected.size() - failed) + " visitor(s) notified by email.");
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

    @PostMapping("/admin/employee/add")
    public String addEmployee(@RequestParam("name") String name, @RequestParam("role") String role,
                              @RequestParam(value = "email", required = false) String email,
                              @RequestParam(value = "workingDays", required = false) List<String> workingDays,
                              @RequestParam(value = "defaultShift", required = false) String defaultShift,
                              RedirectAttributes redirectAttributes) {
        String newRole = role(role);
        if (newRole == null) {
            redirectAttributes.addFlashAttribute("error", "Add failed: the officer's primary role is required.");
            return "redirect:/admin/staff";
        }
        MuseumEmployee emp = new MuseumEmployee();
        emp.setName(name);
        emp.setRole(newRole);
        applyShiftTemplate(emp, email, workingDays, defaultShift);
        employeeRepo.save(emp);
        redirectAttributes.addFlashAttribute("success", "✅ Officer " + name + " added to the official museum roster.");
        return "redirect:/admin/staff";
    }

    // Primary role as typed: any text is accepted (Gate Guard / Tour Guide / Admin are only suggestions), trimmed and
    // capped at the column's 255 characters; null when blank. Only "Gate Guard" (gate duty), roles containing "guide"
    // (check-in emails) and exact role matches (AI roster templates) have automated behaviour, as before.
    static String role(String typed) {
        if (typed == null || typed.isBlank()) return null;
        String role = typed.trim();
        return role.length() > 255 ? role.substring(0, 255).trim() : role;
    }

    // 🗓️ SHIFT TEMPLATE (optional), shared by Add and Edit: only real day names and known shifts (a current time window) are stored
    private void applyShiftTemplate(MuseumEmployee emp, String email, List<String> workingDays, String defaultShift) {
        emp.setEmail(email == null || email.isBlank() ? null : email.trim());
        String days = workingDays == null ? "" : Arrays.stream(java.time.DayOfWeek.values()).map(Enum::name)
                .filter(day -> workingDays.stream().anyMatch(d -> d != null && day.equalsIgnoreCase(d.trim())))
                .collect(Collectors.joining(","));
        emp.setWorkingDays(days.isEmpty() ? null : days);
        emp.setDefaultShift(defaultShift != null && dutyShifts().containsKey(defaultShift) ? defaultShift : null);
    }

    @PostMapping("/admin/employee/edit/{id}")
    public String editEmployee(@PathVariable Long id,
                               @RequestParam("name") String name, @RequestParam("role") String role,
                               @RequestParam(value = "email", required = false) String email,
                               @RequestParam(value = "workingDays", required = false) List<String> workingDays,
                               @RequestParam(value = "defaultShift", required = false) String defaultShift,
                               RedirectAttributes redirectAttributes) {
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
        String newRole = role(role);
        if (newRole == null) {
            redirectAttributes.addFlashAttribute("error", "Update failed: the officer's primary role is required.");
            return "redirect:/admin/staff";
        }

        String oldName = emp.getName();
        emp.setName(newName);
        emp.setRole(newRole);
        applyShiftTemplate(emp, email, workingDays, defaultShift);
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

    // 🗓️ SHIFT TEMPLATE: the roster shift time behind each default shift an officer can have
    private static final Map<String, String> DUTY_SHIFTS = Map.of(
        "Morning", "Morning (0800H-1200H)",
        "Afternoon", "Afternoon (1300H-1700H)",
        "Full Day", "Full Day (0800H-1700H)");

    // Default shift (a time window's name) -> roster shift time. The three original windows keep the roster strings
    // above, so existing templates and AI plans behave as before; any other window uses its own label,
    // e.g. "Midday (11:00 AM - 02:00 PM)", which the gate's on-duty check reads by its hours.
    private Map<String, String> dutyShifts() {
        Map<String, String> shifts = new HashMap<>(DUTY_SHIFTS);
        for (com.paf.aerovisit.model.TimeWindowConfig w : timeWindowRepo.findAllByOrderByStartTimeAsc()) {
            if (w.getName() != null && !w.getName().isBlank() && w.getStartTime() != null && w.getEndTime() != null) {
                shifts.putIfAbsent(w.getName(), w.getLabel());
            }
        }
        return shifts;
    }

    // 🧭 GROUNDED AI ROSTER: the only shift times an AI-planned shift may use, one per configured time window (start
    // order). The three original windows keep their roster strings (DUTY_SHIFTS), like dutyShifts(). With no windows
    // configured at all, the three original shifts are offered as before.
    private List<String> windowShiftOptions() {
        List<String> options = new ArrayList<>();
        for (com.paf.aerovisit.model.TimeWindowConfig w : configuredWindows()) {
            options.add(DUTY_SHIFTS.getOrDefault(w.getName(), w.getLabel()));
        }
        if (options.isEmpty()) {
            options.addAll(List.of(DUTY_SHIFTS.get("Morning"), DUTY_SHIFTS.get("Afternoon"), DUTY_SHIFTS.get("Full Day")));
        }
        return options;
    }

    // The window every AI-planned shift starts in: the longest configured one (earliest on a tie), so a day staffed
    // only by the AI plan is covered for as many hours as possible. Morning when no window is configured (old default).
    private String defaultAiShift() {
        return configuredWindows().stream()
                .max(java.util.Comparator.comparing((com.paf.aerovisit.model.TimeWindowConfig w) -> java.time.Duration.between(w.getStartTime(), w.getEndTime()))
                        .thenComparing(com.paf.aerovisit.model.TimeWindowConfig::getStartTime, java.util.Comparator.reverseOrder()))
                .map(w -> DUTY_SHIFTS.getOrDefault(w.getName(), w.getLabel()))
                .orElse(DUTY_SHIFTS.get("Morning"));
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
    // and AI placeholders ("⚠️ OPEN SHIFT (AI)").
    static String officerKey(String staffName) {
        if (staffName == null) return null;
        String key = staffName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return key.isEmpty() || key.contains("open shift") ? null : key;
    }

    // The officer's shift on that date that the new one would overlap, or null. sameDay = that date's shifts (plus rows
    // planned earlier in the same request); ignoreId = the shift being edited. Archived shifts are history and never clash.
    static StaffSchedule overlappingShift(String staffName, LocalDate date, String shiftTime, Long ignoreId, List<StaffSchedule> sameDay) {
        String key = officerKey(staffName);
        if (key == null || date == null) return null;
        for (StaffSchedule s : sameDay) {
            if (s.isArchived() || (ignoreId != null && ignoreId.equals(s.getId()))) continue;
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

    private List<com.paf.aerovisit.model.TimeWindowConfig> configuredWindows() {
        return timeWindowRepo.findAllByOrderByStartTimeAsc().stream()
                .filter(w -> w.getName() != null && !w.getName().isBlank() && w.getStartTime() != null && w.getEndTime() != null)
                .collect(Collectors.toList());
    }

    // 🗓️ SHIFT TEMPLATES: pre-fills one day of AI-planned shifts with officers of the same role who work that weekday
    // and are not already on duty that date. Officers whose default shift is the planned shift time are placed first;
    // a shift still open after that takes any other matching officer and switches to that officer's own default shift.
    // Shifts nobody matches keep a blank name, which the preview shows as "Leave Unassigned (Open Shift)".
    private static void prefillFromShiftTemplates(List<StaffSchedule> dailyShifts, LocalDate date, List<MuseumEmployee> employees, Set<String> alreadyOnDuty,
                                                  Map<String, String> dutyShifts) {
        for (boolean sameShiftOnly : new boolean[] { true, false }) {
            for (StaffSchedule shift : dailyShifts) {
                if (shift.getStaffName() != null && !shift.getStaffName().isEmpty()) continue;
                for (MuseumEmployee emp : employees) {
                    String templateShift = emp.getDefaultShift() == null ? null : dutyShifts.get(emp.getDefaultShift());
                    if (templateShift != null && (!sameShiftOnly || templateShift.equals(shift.getShiftTime()))
                            && emp.getName() != null && !emp.getName().isBlank()
                            && emp.getRole() != null && emp.getRole().equalsIgnoreCase(shift.getRole())
                            && emp.worksOn(date.getDayOfWeek())
                            && alreadyOnDuty.add(emp.getName() + "|" + date)) {
                        shift.setStaffName(emp.getName());
                        shift.setShiftTime(templateShift);
                        break;
                    }
                }
            }
        }
    }

    @PostMapping("/admin/employee/delete/{id}")
    public String deleteEmployee(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id != null) { 
            employeeRepo.deleteById(id); 
            redirectAttributes.addFlashAttribute("success", "Officer removed from the official roster.");
        }
        return "redirect:/admin/staff";
    }

    // 👻 GHOST RULE: deactivating keeps the officer and every past shift, but takes them out of every scheduling list.
    // Their upcoming shifts (today on, not archived) become open shifts, so the roster never shows someone who won't come.
    @PostMapping("/admin/employee/deactivate/{id}")
    public String deactivateEmployee(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        MuseumEmployee emp = employeeRepo.findById(id).orElse(null);
        if (emp == null) {
            redirectAttributes.addFlashAttribute("error", "That officer is no longer on the roster.");
            return "redirect:/admin/staff";
        }
        emp.setActive(false);
        employeeRepo.save(emp);

        String key = officerKey(emp.getName());
        LocalDate today = LocalDate.now();
        List<StaffSchedule> upcoming = key == null ? List.of() : staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc().stream()
                .filter(s -> s.getDutyDate() != null && !s.getDutyDate().isBefore(today) && key.equals(officerKey(s.getStaffName())))
                .collect(Collectors.toList());
        upcoming.forEach(s -> { s.setStaffName("⚠️ OPEN SHIFT (AI)"); s.setRosterNotified(false); });
        staffRepo.saveAll(upcoming);

        redirectAttributes.addFlashAttribute("success", "👻 " + emp.getName() + " was deactivated and will not be scheduled again."
                + (upcoming.isEmpty() ? "" : " " + upcoming.size() + " upcoming shift(s) are now open shifts."));
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/employee/reactivate/{id}")
    public String reactivateEmployee(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        MuseumEmployee emp = employeeRepo.findById(id).orElse(null);
        if (emp == null) {
            redirectAttributes.addFlashAttribute("error", "That officer is no longer on the roster.");
            return "redirect:/admin/staff";
        }
        emp.setActive(true);
        employeeRepo.save(emp);
        redirectAttributes.addFlashAttribute("success", "✅ " + emp.getName() + " is active again. Shifts opened at deactivation stay open; assign them again if needed.");
        return "redirect:/admin/staff";
    }

    // 👻 GHOST RULE: the first name in the list that belongs to a deactivated officer, or null
    private String deactivatedOfficer(Collection<String> staffNames) {
        Set<String> wanted = staffNames.stream().map(ManagementController::officerKey).filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) return null;
        return employeeRepo.findAll().stream()
                .filter(e -> !e.isActive() && wanted.contains(officerKey(e.getName())))
                .map(MuseumEmployee::getName).findFirst().orElse(null);
    }

    private static String deactivatedError(String name) {
        return "⛔ " + name + " is deactivated and can't be scheduled. Reactivate them in the Staff Directory first. Nothing was saved.";
    }

    // 🏖️ VACATION RULE: record approved leave. Shifts the officer already holds in that range (today on) become open
    // shifts, like a deactivation, so the roster never shows someone who is away.
    @PostMapping("/admin/staff/leave")
    public String addLeave(@RequestParam("staffName") String staffName, @RequestParam("startDate") String startDateStr,
                           @RequestParam("endDate") String endDateStr, RedirectAttributes redirectAttributes) {
        if (leaveRepo == null) {
            redirectAttributes.addFlashAttribute("error", "Leave tracking is not available.");
            return "redirect:/admin/staff";
        }
        String key = officerKey(staffName);
        if (key == null) {
            redirectAttributes.addFlashAttribute("error", "Choose the officer going on leave.");
            return "redirect:/admin/staff";
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDateStr);
            end = LocalDate.parse(endDateStr);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Please enter valid leave dates.");
            return "redirect:/admin/staff";
        }
        if (start.isAfter(end)) {
            redirectAttributes.addFlashAttribute("error", "Time Paradox: the leave cannot end before it starts.");
            return "redirect:/admin/staff";
        }

        String name = staffName.trim();
        leaveRepo.save(new StaffLeave(name, start, end));
        LocalDate today = LocalDate.now();
        List<StaffSchedule> freed = end.isBefore(today) ? List.of()
                : staffRepo.findByArchivedFalseAndDutyDateBetween(start.isBefore(today) ? today : start, end).stream()
                    .filter(s -> key.equals(officerKey(s.getStaffName())))
                    .collect(Collectors.toList());
        freed.forEach(s -> { s.setStaffName("⚠️ OPEN SHIFT (AI)"); s.setRosterNotified(false); });
        staffRepo.saveAll(freed);

        redirectAttributes.addFlashAttribute("success", "🏖️ Leave recorded for " + name + " from " + start + " to " + end + "."
                + (freed.isEmpty() ? "" : " " + freed.size() + " shift(s) in that period are now open shifts."));
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/leave/delete/{id}")
    public String deleteLeave(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (leaveRepo != null && id != null) {
            leaveRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Leave cancelled. Shifts opened for it stay open; assign them again if needed.");
        }
        return "redirect:/admin/staff";
    }

    // Leave blocks touching [from, to]; none when leave tracking isn't wired (test slices)
    private List<StaffLeave> leavesBetween(LocalDate from, LocalDate to) {
        return leaveRepo == null || from == null || to == null ? List.of()
             : leaveRepo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(to, from);
    }

    // The officer's leave covering that date, or null (same name matching as the Cloning Rule)
    static StaffLeave leaveOn(String staffName, LocalDate date, List<StaffLeave> leaves) {
        String key = officerKey(staffName);
        if (key == null) return null;
        return leaves.stream().filter(l -> key.equals(officerKey(l.getStaffName())) && l.covers(date)).findFirst().orElse(null);
    }

    private static String leaveError(String staffName, LocalDate date, StaffLeave leave) {
        return "⛔ " + staffName.trim() + " is on approved leave from " + leave.getStartDate() + " to " + leave.getEndDate()
             + ", so they can't be scheduled on " + date + ". Nothing was saved.";
    }

    @PostMapping("/admin/staff/add")
    public String addStaffSchedule(
            @RequestParam("staffName") String staffName,
            @RequestParam("role") String role,
            @RequestParam("startDate") String startDateStr,
            @RequestParam("endDate") String endDateStr,
            @RequestParam("shiftTime") String shiftTime,
            @RequestParam(value = "excludeWeekends", required = false) boolean excludeWeekends,
            RedirectAttributes redirectAttributes) {

        if (!windowShiftOptions().contains(shiftTime)) {
            // 🧭 Same rule as the AI deploy: a manual shift uses one of the configured time windows
            redirectAttributes.addFlashAttribute("error", "Shift time \"" + shiftTime + "\" is not one of the configured time windows.");
            return "redirect:/admin/staff";
        }
        try {
            LocalDate startDate = LocalDate.parse(startDateStr);
            LocalDate endDate = LocalDate.parse(endDateStr);

            if (startDate.isAfter(endDate)) {
                redirectAttributes.addFlashAttribute("error", "Time Paradox: End Date cannot be before Start Date.");
                return "redirect:/admin/staff";
            }

            Set<LocalDate> openMuseumDates = bookingRepo.findAll().stream()
                    .map(BookingSlot::getSlotDate)
                    .filter(d -> d != null)
                    .collect(Collectors.toCollection(HashSet::new));
            openMuseumDates.addAll(repo.findBuyoutDates()); // VIP buyouts can fall on days with no public slots

            List<StaffSchedule> toSave = new ArrayList<>();
            int shiftsSkipped = 0;
            LocalDate currentDate = startDate;

            while (!currentDate.isAfter(endDate)) {
                boolean isWeekend = (currentDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ||
                                     currentDate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);

                if (!excludeWeekends || !isWeekend) {
                    if (openMuseumDates.contains(currentDate)) {
                        StaffSchedule sched = new StaffSchedule();
                        sched.setStaffName(staffName.trim());
                        sched.setRole(role);
                        sched.setDutyDate(currentDate);
                        sched.setShiftTime(shiftTime);
                        toSave.add(sched);
                    } else {
                        shiftsSkipped++;
                    }
                }
                currentDate = currentDate.plusDays(1);
            }

            String ghost = toSave.isEmpty() ? null : deactivatedOfficer(List.of(staffName));
            if (ghost != null) {
                redirectAttributes.addFlashAttribute("error", deactivatedError(ghost));
                return "redirect:/admin/staff";
            }
            // 🏖️ VACATION RULE: any date on leave refuses the whole range
            List<StaffLeave> leaves = toSave.isEmpty() ? List.of() : leavesBetween(startDate, endDate);
            for (StaffSchedule sched : toSave) {
                StaffLeave leave = leaveOn(staffName, sched.getDutyDate(), leaves);
                if (leave != null) {
                    redirectAttributes.addFlashAttribute("error", leaveError(staffName, sched.getDutyDate(), leave));
                    return "redirect:/admin/staff";
                }
            }
            // ⛔ CLONING RULE: any clash refuses the whole range, so nothing is half-saved
            for (StaffSchedule sched : toSave) {
                StaffSchedule clash = overlappingShift(staffName, sched.getDutyDate(), shiftTime, null,
                        staffRepo.findByDutyDateOrderByStaffNameAsc(sched.getDutyDate()));
                if (clash != null) {
                    redirectAttributes.addFlashAttribute("error", overlapError(staffName, sched.getDutyDate(), shiftTime, clash));
                    return "redirect:/admin/staff";
                }
            }
            toSave.forEach(staffRepo::save);
            int shiftsAssigned = toSave.size();

            if (shiftsAssigned > 0) {
                String msg = "✅ Scheduled " + shiftsAssigned + " shifts for " + staffName + ".";
                if (shiftsSkipped > 0) msg += " (" + shiftsSkipped + " dates skipped because there are no public booking slots open).";
                redirectAttributes.addFlashAttribute("success", msg);
            } else {
                redirectAttributes.addFlashAttribute("error", "❌ No shifts assigned. The selected dates do not have any open public booking slots.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "System Error: Please check your date formatting.");
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/delete/{id}")
    public String deleteStaffSchedule(@PathVariable Long id) {
        if (id != null) {
            staffRepo.findById(id).ifPresent(shift -> requeueRosterFor(List.of(shift)));
            staffRepo.deleteById(id);
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/ajax-delete/{id}")
    @ResponseBody
    public String ajaxDeleteStaffSchedule(@PathVariable Long id) {
        if (id != null) {
            staffRepo.findById(id).ifPresent(shift -> requeueRosterFor(List.of(shift)));
            staffRepo.deleteById(id);
        }
        return "OK";
    }

    @PostMapping("/admin/staff/ajax-bulk-delete")
    @ResponseBody
    public String ajaxBulkDeleteStaffSchedule(@RequestParam("ids") List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            requeueRosterFor(staffRepo.findAllById(ids));
            staffRepo.deleteAllById(ids);
        }
        return "OK";
    }

    // 📨 DELTA TRACKER: call this BEFORE deleting shifts. When an officer loses an upcoming shift they were already
    // emailed about, their remaining upcoming shifts are flagged un-notified again, so the next
    // "Publish Roster & Notify Staff" sends them a fresh, complete roster. Open shifts, past shifts and shifts
    // that were never emailed change nothing, because the officer's last email is still correct.
    private void requeueRosterFor(List<StaffSchedule> beingDeleted) {
        LocalDate today = LocalDate.now();
        Set<Long> deletedIds = new HashSet<>();
        Set<String> affectedStaff = new HashSet<>();
        for (StaffSchedule shift : beingDeleted) {
            deletedIds.add(shift.getId());
            String staffName = shift.getStaffName();
            if (shift.isRosterNotified() && !shift.isArchived()
                    && shift.getDutyDate() != null && !shift.getDutyDate().isBefore(today)
                    && staffName != null && !staffName.isBlank() && !staffName.contains("OPEN SHIFT")) {
                affectedStaff.add(staffName.trim());
            }
        }
        if (affectedStaff.isEmpty()) return;

        List<StaffSchedule> remaining = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc().stream()
                .filter(s -> !deletedIds.contains(s.getId()) && s.isRosterNotified())
                .filter(s -> s.getDutyDate() != null && !s.getDutyDate().isBefore(today))
                .filter(s -> s.getStaffName() != null && affectedStaff.contains(s.getStaffName().trim()))
                .collect(Collectors.toList());
        remaining.forEach(s -> s.setRosterNotified(false));
        staffRepo.saveAll(remaining);
    }

    @PostMapping("/admin/staff/update")
    public String updateStaffSchedule(
            @RequestParam("id") Long id,
            @RequestParam("staffName") String staffName,
            @RequestParam("role") String role,
            @RequestParam("dutyDate") String dutyDateStr,
            @RequestParam("shiftTime") String shiftTime,
            RedirectAttributes redirectAttributes) {
        
        try {
            LocalDate newDate = LocalDate.parse(dutyDateStr);

            boolean isMuseumOpen = bookingRepo.findAll().stream()
                    .anyMatch(slot -> newDate.equals(slot.getSlotDate()))
                    || repo.findBuyoutDates().contains(newDate);

            if (!isMuseumOpen) {
                redirectAttributes.addFlashAttribute("error", "❌ Update failed: The museum has no open booking slots on " + dutyDateStr + ".");
                return "redirect:/admin/staff";
            }

            StaffSchedule sched = staffRepo.findById(id).orElse(null);
            if (sched != null && !shiftTime.equals(sched.getShiftTime()) && !windowShiftOptions().contains(shiftTime)) {
                // 🧭 A new shift time must be a configured time window; the shift's current (older) value may be kept as is
                redirectAttributes.addFlashAttribute("error", "Shift time \"" + shiftTime + "\" is not one of the configured time windows.");
                return "redirect:/admin/staff";
            }
            if (sched != null) {
                // 👻 GHOST RULE: from today on only (correcting a past shift of a now-deactivated officer is still allowed)
                String ghost = newDate.isBefore(LocalDate.now()) ? null : deactivatedOfficer(List.of(staffName));
                if (ghost != null) {
                    redirectAttributes.addFlashAttribute("error", deactivatedError(ghost));
                    return "redirect:/admin/staff";
                }
                StaffLeave leave = leaveOn(staffName, newDate, leavesBetween(newDate, newDate)); // 🏖️ VACATION RULE
                if (leave != null) {
                    redirectAttributes.addFlashAttribute("error", leaveError(staffName, newDate, leave));
                    return "redirect:/admin/staff";
                }
                StaffSchedule clash = overlappingShift(staffName, newDate, shiftTime, sched.getId(),
                        staffRepo.findByDutyDateOrderByStaffNameAsc(newDate));
                if (clash != null) {
                    redirectAttributes.addFlashAttribute("error", overlapError(staffName, newDate, shiftTime, clash));
                    return "redirect:/admin/staff";
                }
                // 📨 A shift that moved to another officer, date or time must be emailed again on the next publish
                if (!staffName.trim().equals(sched.getStaffName()) || !newDate.equals(sched.getDutyDate()) || !shiftTime.equals(sched.getShiftTime())) {
                    sched.setRosterNotified(false);
                }
                sched.setStaffName(staffName.trim());
                sched.setRole(role);
                sched.setDutyDate(newDate);
                sched.setShiftTime(shiftTime);
                staffRepo.save(sched);
                redirectAttributes.addFlashAttribute("success", "✅ Shift assignment successfully updated for " + staffName + ".");
            } else {
                redirectAttributes.addFlashAttribute("error", "Error: Shift record could not be found.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "System Error: Failed to update shift details.");
        }
        return "redirect:/admin/staff";
    }

    // 🗓️ FILL FROM STAFF TEMPLATES (no AI): every active officer gets a shift on each open date that is one of their
    // Working Days, at their Default Shift, in their own role. An officer on leave or already on an overlapping shift that
    // date is skipped and listed, never refused as a whole. Shown in the same preview as the AI plan, then deployed with it.
    static final int TEMPLATE_MAX_DAYS = 92;
    static final int TEMPLATE_SKIPPED_SHOWN = 25;

    record TemplatePlan(Map<LocalDate, List<StaffSchedule>> shifts, List<String> skipped) {}

    static TemplatePlan planFromTemplates(LocalDate start, LocalDate end, Set<LocalDate> openDates, Set<LocalDate> skipDates,
                                          List<MuseumEmployee> employees, Map<String, String> dutyShifts, List<String> allowedShifts,
                                          List<StaffLeave> leaves, List<StaffSchedule> existing) {
        Map<LocalDate, List<StaffSchedule>> plan = new TreeMap<>();
        List<String> skipped = new ArrayList<>();
        List<MuseumEmployee> withTemplate = new ArrayList<>();
        List<String> noTemplate = new ArrayList<>();
        for (MuseumEmployee emp : employees) {
            if (!emp.isActive() || emp.getName() == null || emp.getName().isBlank()) continue; // 👻 GHOST RULE
            String shift = emp.getDefaultShift() == null ? null : dutyShifts.get(emp.getDefaultShift());
            if (shift == null || emp.getWorkingDaysLabel().isEmpty()) {
                noTemplate.add(emp.getName().trim());
            } else if (!allowedShifts.contains(shift)) {
                skipped.add(emp.getName().trim() + ": default shift " + shift + " is not one of the configured time windows");
            } else {
                withTemplate.add(emp);
            }
        }
        if (!noTemplate.isEmpty()) {
            skipped.add("No shift template (Working Days + Default Shift) for: " + String.join(", ", noTemplate));
        }
        Map<LocalDate, List<StaffSchedule>> rosterByDate = new HashMap<>();
        for (StaffSchedule s : existing) {
            if (s.getDutyDate() != null) rosterByDate.computeIfAbsent(s.getDutyDate(), d -> new ArrayList<>()).add(s);
        }
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!openDates.contains(date) || skipDates.contains(date)) continue; // closed or a skipped holiday
            for (MuseumEmployee emp : withTemplate) {
                if (!emp.worksOn(date.getDayOfWeek())) continue;
                String name = emp.getName().trim();
                String shift = dutyShifts.get(emp.getDefaultShift());
                StaffLeave leave = leaveOn(name, date, leaves); // 🏖️ VACATION RULE: skipped, not refused
                if (leave != null) {
                    skipped.add(name + " on " + date + ": on leave");
                    continue;
                }
                StaffSchedule clash = overlappingShift(name, date, shift, null, rosterByDate.getOrDefault(date, List.of()));
                if (clash != null) { // ⛔ CLONING RULE: skipped, not refused
                    skipped.add(name + " on " + date + ": already on duty " + clash.getShiftTime());
                    continue;
                }
                StaffSchedule s = new StaffSchedule();
                s.setStaffName(name);
                s.setRole(emp.getRole() == null || emp.getRole().isBlank() ? "Admin" : emp.getRole().trim());
                s.setDutyDate(date);
                s.setShiftTime(shift);
                plan.computeIfAbsent(date, d -> new ArrayList<>()).add(s);
            }
        }
        return new TemplatePlan(plan, skipped);
    }

    @PostMapping("/admin/staff/template-preview")
    public String previewTemplateStaff(
            @RequestParam("startDate") String startDateStr,
            @RequestParam("endDate") String endDateStr,
            @RequestParam(value = "excludeDates", required = false) String excludeDatesStr,
            RedirectAttributes redirectAttributes) {
        LocalDate startDate;
        LocalDate endDate;
        Set<LocalDate> skipDates = new HashSet<>();
        try {
            startDate = LocalDate.parse(startDateStr);
            endDate = LocalDate.parse(endDateStr);
            if (excludeDatesStr != null) {
                for (String d : excludeDatesStr.split(",")) {
                    if (!d.trim().isEmpty()) skipDates.add(LocalDate.parse(d.trim()));
                }
            }
        } catch (java.time.format.DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Please pick a valid start and end date.");
            return "redirect:/admin/staff";
        }
        if (startDate.isAfter(endDate)) {
            redirectAttributes.addFlashAttribute("error", "Time Paradox: End Date cannot be before Start Date.");
            return "redirect:/admin/staff";
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1 > TEMPLATE_MAX_DAYS) {
            redirectAttributes.addFlashAttribute("error", "Please fill at most " + TEMPLATE_MAX_DAYS + " days at a time.");
            return "redirect:/admin/staff";
        }

        Set<LocalDate> openMuseumDates = bookingRepo.findAll().stream()
                .map(BookingSlot::getSlotDate)
                .filter(d -> d != null)
                .collect(Collectors.toCollection(HashSet::new));
        openMuseumDates.addAll(repo.findBuyoutDates()); // VIP buyouts can fall on days with no public slots

        List<MuseumEmployee> employees = employeeRepo.findAll();
        TemplatePlan plan = planFromTemplates(startDate, endDate, openMuseumDates, skipDates, employees, dutyShifts(),
                windowShiftOptions(), leavesBetween(startDate, endDate), staffRepo.findByArchivedFalseAndDutyDateBetween(startDate, endDate));

        if (plan.shifts().isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "🗓️ No shifts to add from staff templates between " + startDate + " and " + endDate
                    + ". Check that those dates have open booking slots, and that officers have Working Days and a Default Shift in the Staff Directory."
                    + (plan.skipped().isEmpty() ? "" : " Skipped: " + String.join("; ", plan.skipped().subList(0, Math.min(5, plan.skipped().size()))) + "."));
            return "redirect:/admin/staff";
        }
        List<String> skippedShown = new ArrayList<>(plan.skipped().subList(0, Math.min(TEMPLATE_SKIPPED_SHOWN, plan.skipped().size())));
        if (plan.skipped().size() > TEMPLATE_SKIPPED_SHOWN) skippedShown.add("... and " + (plan.skipped().size() - TEMPLATE_SKIPPED_SHOWN) + " more");

        redirectAttributes.addFlashAttribute("groupedShifts", plan.shifts());
        redirectAttributes.addFlashAttribute("activeStaff", employees.stream().filter(MuseumEmployee::isActive).collect(Collectors.toList()));
        redirectAttributes.addFlashAttribute("previewMode", true);
        redirectAttributes.addFlashAttribute("previewSource", "templates");
        redirectAttributes.addFlashAttribute("templateSkipped", skippedShown);
        return "redirect:/admin/staff";
    }

    // 🤖 AI features switch (Settings): the AI roster needs the AI service; Fill from Staff Templates does not
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.paf.aerovisit.service.AiSwitch aiSwitch;

    @PostMapping("/admin/staff/ai-preview")
    public String previewAiStaff(
            @RequestParam("startDate") String startDateStr,
            @RequestParam("endDate") String endDateStr,
            @RequestParam(value = "excludeWeekends", required = false) boolean excludeWeekends,
            @RequestParam(value = "excludeDates", required = false) String excludeDatesStr,
            RedirectAttributes redirectAttributes) {
        if (aiSwitch != null && !aiSwitch.enabled()) {
            redirectAttributes.addFlashAttribute("error", "AI features are turned off in Settings. Use Fill from Staff Templates instead.");
            return "redirect:/admin/staff";
        }
        
        try {
            LocalDate startDate = LocalDate.parse(startDateStr);
            LocalDate endDate = LocalDate.parse(endDateStr);
            
            Set<LocalDate> blackoutDates = new HashSet<>();
            if (excludeDatesStr != null && !excludeDatesStr.trim().isEmpty()) {
                for (String d : excludeDatesStr.split(",")) {
                    if (!d.trim().isEmpty()) blackoutDates.add(LocalDate.parse(d.trim()));
                }
            }

            Set<LocalDate> openMuseumDates = bookingRepo.findAll().stream()
                    .map(BookingSlot::getSlotDate)
                    .filter(d -> d != null)
                    .collect(Collectors.toCollection(HashSet::new));
            openMuseumDates.addAll(repo.findBuyoutDates()); // VIP buyouts can fall on days with no public slots

            List<Visitor> allVisitors = repo.findAll();
            Map<LocalDate, List<StaffSchedule>> groupedShifts = new TreeMap<>();
            LocalDate currentDate = startDate;

            Pattern pattern = Pattern.compile("(\\d+)\\s*(tour guide|guide|gate guard|guard|admin)s?", Pattern.CASE_INSENSITIVE);
            String aiShift = defaultAiShift(); // 🧭 GROUNDED: picked from the configured time windows

            while (!currentDate.isAfter(endDate)) {
                boolean isWeekend = (currentDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || 
                                     currentDate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
                boolean isBlackout = blackoutDates.contains(currentDate);

                if ((!excludeWeekends || !isWeekend) && !isBlackout) {
                    
                    if (!openMuseumDates.contains(currentDate)) {
                        currentDate = currentDate.plusDays(1);
                        continue;
                    }

                    int dayOfWeek = currentDate.getDayOfWeek().getValue() - 1;
                    final LocalDate dateToMatch = currentDate;
                    
                    int preBookedCount = (int) allVisitors.stream()
                        .filter(v -> dateToMatch.equals(v.getVisitDate()))
                        .filter(v -> "APPROVED".equals(v.getStatus()) || "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus()))
                        .mapToInt(v -> v.getGroupSize() > 0 ? v.getGroupSize() : 1)
                        .sum();

                    com.paf.aerovisit.model.MuseumPrediction prediction = aiService.fetchTurnoutPrediction(dayOfWeek, "", preBookedCount);
                    if (prediction == null) {
                        // 🔌 The AI service didn't answer (or was paused after failing to): no forecast, no AI roster
                        redirectAttributes.addFlashAttribute("error", "The AI service isn't answering right now, so no AI roster could be made. Use Fill from Staff Templates instead, or try again in a minute.");
                        return "redirect:/admin/staff";
                    }
                    String personnelReq = prediction.getPersonnel();
                    List<StaffSchedule> dailyShifts = new ArrayList<>();

                    if (personnelReq != null && !personnelReq.contains("OFFLINE")) {
                        Matcher matcher = pattern.matcher(personnelReq);
                        while (matcher.find()) {
                            int count = Math.min(Integer.parseInt(matcher.group(1)), 20);
                            String rawTitle = matcher.group(2).toLowerCase();
                            String jobTitle = "Admin";
                            if (rawTitle.contains("guard")) jobTitle = "Gate Guard";
                            else if (rawTitle.contains("guide")) jobTitle = "Tour Guide";

                            for (int i = 0; i < count; i++) {
                                StaffSchedule s = new StaffSchedule();
                                s.setStaffName(""); 
                                s.setRole(jobTitle);
                                s.setDutyDate(currentDate);
                                s.setShiftTime(aiShift); // 🧭 a real time window, never a hard-coded one
                                dailyShifts.add(s);
                            }
                        }
                    }
                    groupedShifts.put(currentDate, dailyShifts);
                }
                currentDate = currentDate.plusDays(1);
            }

            if (groupedShifts.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "❌ AI did not generate any shifts. The selected date range does not contain any active public booking slots.");
                return "redirect:/admin/staff";
            }

            List<MuseumEmployee> activeStaff = employeeRepo.findAll().stream().filter(MuseumEmployee::isActive).collect(Collectors.toList()); // 👻 GHOST RULE

            // 🗓️ SHIFT TEMPLATES: suggest officers in the preview, so the Director reviews the names before deploying
            Set<String> alreadyOnDuty = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc().stream()
                    .map(s -> s.getStaffName() + "|" + s.getDutyDate())
                    .collect(Collectors.toCollection(HashSet::new));
            // 🏖️ VACATION RULE: an officer on leave that day is never suggested (treated as already on duty)
            List<StaffLeave> leaves = leavesBetween(startDate, endDate);
            for (MuseumEmployee emp : activeStaff) {
                for (LocalDate date : groupedShifts.keySet()) {
                    if (emp.getName() != null && leaveOn(emp.getName(), date, leaves) != null) alreadyOnDuty.add(emp.getName() + "|" + date);
                }
            }
            Map<String, String> dutyShifts = dutyShifts();
            dutyShifts.values().retainAll(windowShiftOptions()); // a template pointing at a deleted window suggests nobody
            groupedShifts.forEach((date, dailyShifts) -> prefillFromShiftTemplates(dailyShifts, date, activeStaff, alreadyOnDuty, dutyShifts));
            redirectAttributes.addFlashAttribute("groupedShifts", groupedShifts);
            redirectAttributes.addFlashAttribute("activeStaff", activeStaff);
            redirectAttributes.addFlashAttribute("previewMode", true);

        } catch (Exception e) {
            System.out.println("AI Preview Error: " + e.getMessage());
            redirectAttributes.addFlashAttribute("error", "System Error: Failed to generate dynamic AI preview.");
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/ai-deploy")
    public String deployAiStaff(
            @RequestParam(value="staffNames", required=false) List<String> staffNames,
            @RequestParam(value="roles", required=false) List<String> roles,
            @RequestParam(value="dutyDates", required=false) List<String> dutyDates,
            @RequestParam(value="shiftTimes", required=false) List<String> shiftTimes,
            @RequestParam(value="source", required=false) String source,
            RedirectAttributes redirectAttributes) {
        
        if(staffNames == null || staffNames.isEmpty()) {
            return "redirect:/admin/staff";
        }
        // The four columns must line up row by row (a mismatched hand-made form used to crash with a 500)
        if (roles == null || dutyDates == null || shiftTimes == null
                || roles.size() != staffNames.size() || dutyDates.size() != staffNames.size() || shiftTimes.size() != staffNames.size()) {
            redirectAttributes.addFlashAttribute("error", "The roster form was incomplete. Please generate the AI preview again.");
            return "redirect:/admin/staff";
        }
        // 🧭 GROUNDED: every deployed shift must use a configured time window, nothing is saved otherwise
        List<String> allowedShifts = windowShiftOptions();
        for (String shiftTime : shiftTimes) {
            if (!allowedShifts.contains(shiftTime)) {
                redirectAttributes.addFlashAttribute("error", "Shift time \"" + shiftTime + "\" is not one of the configured time windows. "
                        + "Please pick a window from the list and deploy again.");
                return "redirect:/admin/staff";
            }
        }

        String ghost = deactivatedOfficer(staffNames);
        if (ghost != null) {
            redirectAttributes.addFlashAttribute("error", deactivatedError(ghost));
            return "redirect:/admin/staff";
        }
        // ⛔ CLONING RULE / 🏖️ VACATION RULE: checked for every row first, so a clash or a leave saves nothing
        List<LocalDate> rowDates = dutyDates.stream().map(LocalDate::parse).collect(Collectors.toList());
        List<StaffLeave> leaves = leavesBetween(Collections.min(rowDates), Collections.max(rowDates));
        Map<LocalDate, List<StaffSchedule>> rosterByDate = new HashMap<>();
        for (int i = 0; i < staffNames.size(); i++) {
            LocalDate date = rowDates.get(i);
            StaffLeave leave = leaveOn(staffNames.get(i), date, leaves);
            if (leave != null) {
                redirectAttributes.addFlashAttribute("error", "Row " + (i + 1) + ": " + leaveError(staffNames.get(i), date, leave));
                return "redirect:/admin/staff";
            }
            List<StaffSchedule> sameDay = rosterByDate.computeIfAbsent(date,
                    d -> new ArrayList<>(staffRepo.findByDutyDateOrderByStaffNameAsc(d)));
            StaffSchedule clash = overlappingShift(staffNames.get(i), date, shiftTimes.get(i), null, sameDay);
            if (clash != null) {
                redirectAttributes.addFlashAttribute("error", "Row " + (i + 1) + ": "
                        + overlapError(staffNames.get(i), date, shiftTimes.get(i), clash));
                return "redirect:/admin/staff";
            }
            StaffSchedule planned = new StaffSchedule();
            planned.setStaffName(staffNames.get(i));
            planned.setDutyDate(date);
            planned.setShiftTime(shiftTimes.get(i));
            sameDay.add(planned);                                       // later rows are checked against this one too
        }

        int count = 0;
        for (int i = 0; i < staffNames.size(); i++) {
            String name = staffNames.get(i);
            if (name == null || name.trim().isEmpty()) {
                name = "⚠️ OPEN SHIFT (AI)";
            }

            StaffSchedule s = new StaffSchedule();
            s.setStaffName(name.trim());
            s.setRole(roles.get(i));
            s.setDutyDate(LocalDate.parse(dutyDates.get(i)));
            s.setShiftTime(shiftTimes.get(i));
            staffRepo.save(s);
            count++;
        }
        
        redirectAttributes.addFlashAttribute("success", "templates".equals(source)
                ? "🗓️ Deployed " + count + " shifts from staff templates to the Master Duty Roster."
                : "🤖 Successfully deployed " + count + " AI-planned shifts to the Master Duty Roster.");
        return "redirect:/admin/staff";
    }

    // 🗑️ CLEAR DATE RANGE: deletes every active shift from startDate to endDate (both included), e.g. after an AI roster
    // was deployed to the wrong week. Archived shifts are history and are never touched.
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

        // Officers already emailed about these shifts are re-queued, so the next Publish sends them their remaining roster.
        // Anyone left with no upcoming shift gets no email, so the Director is reminded to tell them.
        long alreadyEmailed = inRange.stream()
                .filter(s -> s.isRosterNotified() && s.getStaffName() != null && !s.getStaffName().contains("OPEN SHIFT"))
                .count();
        requeueRosterFor(inRange);
        staffRepo.deleteAllInBatch(inRange);

        redirectAttributes.addFlashAttribute("success", "🗑️ Successfully cleared " + inRange.size() + (inRange.size() == 1 ? " shift" : " shifts")
                + " from the roster (" + startDate + " to " + endDate + ")."
                + (alreadyEmailed > 0 ? " " + alreadyEmailed + " of them had already been emailed to staff: publish the roster to send the updated schedules, and tell anyone left with no shifts directly." : ""));
        return "redirect:/admin/staff";
    }

    // 📨 PUBLISH & NOTIFY: emails each officer one roster listing their upcoming duty shifts (today onwards)
    // that have not been emailed yet. A shift is flagged rosterNotified once its email is sent, so pressing the button
    // again only sends what is new. Shifts are matched to the Staff Directory by name; open shifts and officers
    // with no email on file are skipped (and stay unflagged, so they go out once an email is added).
    @PostMapping("/admin/staff/publish-roster")
    public String publishRoster(RedirectAttributes redirectAttributes) {
        LocalDate today = LocalDate.now();
        List<StaffSchedule> upcoming = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc().stream()
                .filter(s -> s.getDutyDate() != null && !s.getDutyDate().isBefore(today))
                .filter(s -> s.getStaffName() != null && !s.getStaffName().isBlank() && !s.getStaffName().contains("OPEN SHIFT"))
                .collect(Collectors.toList());

        if (upcoming.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Roster not published: there are no upcoming assigned shifts to send.");
            return "redirect:/admin/staff";
        }

        Map<String, List<StaffSchedule>> shiftsByStaff = upcoming.stream()
                .filter(s -> !s.isRosterNotified())
                .collect(Collectors.groupingBy(s -> s.getStaffName().trim(), TreeMap::new, Collectors.toList()));

        if (shiftsByStaff.isEmpty()) {
            redirectAttributes.addFlashAttribute("info", "All staff are already up-to-date. No new emails sent.");
            return "redirect:/admin/staff";
        }

        Map<String, String> emailByName = new HashMap<>();
        for (MuseumEmployee emp : employeeRepo.findAll()) {
            if (emp.getName() != null && emp.getEmail() != null && !emp.getEmail().isBlank()) {
                emailByName.putIfAbsent(emp.getName().trim().toLowerCase(), emp.getEmail().trim());
            }
        }

        // One failed email must not stop the rest of the batch
        int sent = 0;
        List<String> noEmail = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, List<StaffSchedule>> entry : shiftsByStaff.entrySet()) {
            String staffName = entry.getKey();
            String email = emailByName.get(staffName.toLowerCase());
            if (email == null) {
                noEmail.add(staffName);
                continue;
            }
            try {
                emailService.sendStaffScheduleEmail(email, staffName, entry.getValue());
                sent++;
                entry.getValue().forEach(s -> s.setRosterNotified(true));
                staffRepo.saveAll(entry.getValue());
            } catch (Exception mailError) {
                failed.add(staffName);
                System.err.println("❌ Duty roster email failed for " + staffName + ": " + mailError.getMessage());
            }
        }

        if (sent > 0) {
            redirectAttributes.addFlashAttribute("success", "📨 Roster published and " + sent + (sent == 1 ? " email" : " emails") + " sent!");
        }
        if (!noEmail.isEmpty() || !failed.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "⚠️ Not notified."
                    + (noEmail.isEmpty() ? "" : " No email on file in the Staff Directory for: " + String.join(", ", noEmail) + ".")
                    + (failed.isEmpty() ? "" : " Email could not be sent to: " + String.join(", ", failed) + "."));
        }
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/slots/add")
    public String addBookingSlot(
            @RequestParam("startDate") String startDateStr,
            @RequestParam("endDate") String endDateStr,
            @RequestParam("timeWindow") String timeWindow,
            @RequestParam("maxCapacity") int maxCapacity,
            @RequestParam(value = "excludeWeekends", required = false) boolean excludeWeekends,
            @RequestParam(value = "excludeDates", required = false) String excludeDatesStr,
            RedirectAttributes redirectAttributes) {
        
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

            int slotsCreated = 0;
            int holidaysSkipped = 0;
            LocalDate currentDate = startDate;

            while (!currentDate.isAfter(endDate)) {
                boolean isWeekend = (currentDate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || 
                                     currentDate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
                boolean isBlackout = blackoutDates.contains(currentDate);
                
                if ((!excludeWeekends || !isWeekend) && !isBlackout) {
                    BookingSlot slot = new BookingSlot();
                    slot.setSlotDate(currentDate);
                    slot.setTimeWindow(timeWindow);
                    slot.setMaxCapacity(maxCapacity);
                    bookingRepo.save(slot);
                    slotsCreated++;
                } else if (isBlackout && !(excludeWeekends && isWeekend)) {
                    holidaysSkipped++;
                }
                currentDate = currentDate.plusDays(1);
            }
            redirectAttributes.addFlashAttribute("success", "✅ Bulk Scheduling Complete: " + slotsCreated + " days successfully opened for " + timeWindow + "." + (holidaysSkipped > 0 ? " Skipped " + holidaysSkipped + " holiday(s)." : ""));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "System Error: Please check your date formatting.");
        }
        return "redirect:/admin/staff";
    }
    
    @PostMapping("/admin/slots/delete/{id}")
    public String deleteBookingSlot(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id != null) {
            bookingRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Booking slot deleted successfully.");
        }
        return "redirect:/admin/staff";
    }

    // 🔥 RESTORED: This is the missing endpoint for your Duty Roster tab!
    @GetMapping("/admin/calendar")
    public String showCalendar(Model model) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) { model.addAttribute("loggedInUser", auth.getName()); }
        List<StaffSchedule> allShifts = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc();
        allShifts.sort((s1, s2) -> {
            if(s1.getDutyDate() == null || s2.getDutyDate() == null) return 0;
            return s1.getDutyDate().compareTo(s2.getDutyDate());
        });
        model.addAttribute("shifts", allShifts);
        return "calendar"; 
    }
}