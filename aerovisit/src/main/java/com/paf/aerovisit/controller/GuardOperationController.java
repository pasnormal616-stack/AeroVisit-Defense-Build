package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.util.QRCodeGenerator;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class GuardOperationController {

    private final VisitorRepository repo;
    private final StaffScheduleRepository staffRepo;
    private final EmailService emailService;
    private final BookingSlotRepository bookingRepo;

    // 🧬 Face-ban checks on approval and check-in. Optional (like zoneRepo in VisitorPortalController) so the
    // existing web-layer test slices without this mock still start; the real app always has it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BlacklistedFaceRepository faceBanRepo;

    // Race-safe capacity save for approvals. Optional for the same reason as faceBanRepo; the real app always has it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SeatReservationService seatReservation;

    static final String BANNED_APPROVAL = "Approval refused: this visitor is banned. Lift the ban on the Banned Visitors page first if this is a mistake.";
    static final String BANNED_ENTRY = "ACCESS DENIED: This visitor is banned.";
    static final String INVALID_SLOT = "Invalid or unavailable time slot: no open booking slot exists for this date and time window.";

    // ⏳ A QR scanned again this soon after check-in is treated as an accidental double-scan, not a check-out
    static final Duration CHECKOUT_COOLDOWN = Duration.ofMinutes(2);

    // 🕒 Crowd control: check-in opens this long before the booked time window starts
    static final int EARLY_CHECK_IN_MINUTES = 15;

    // Start and end times inside a time window label, e.g. "Morning (08:00 AM - 12:00 PM)"
    private static final java.util.regex.Pattern WINDOW_TIMES = java.util.regex.Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])");
    private static final java.time.format.DateTimeFormatter GATE_TIME =
            java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US);

    public GuardOperationController(VisitorRepository repo, StaffScheduleRepository staffRepo, EmailService emailService, BookingSlotRepository bookingRepo) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.emailService = emailService;
        this.bookingRepo = bookingRepo;
    }

    @GetMapping("/admin/scanner")
    public String showScannerPage() {
        return "scanner";
    }

    @PostMapping("/admin/api/scan")
    @ResponseBody
    public ResponseEntity<Map<String, String>> scanVisitorAjax(@RequestParam("qrData") String qrData) {
        Map<String, String> response = new HashMap<>();
        try {
            // 🔥 NEW: Search by Secure Token instead of plain ID
            // 🔒 Only the unguessable secure token is accepted. The old "ID:x | Name:y" format was
            // removed because anyone could forge it with a free QR generator.
            // Looks up the one matching visitor directly instead of loading every visitor.
            Visitor v = repo.findBySecureToken(qrData).orElse(null);

            if (v != null) {
                // Ensure we have an image URL to send back for visual verification
                String imageUrl = v.getIdImageUrl() != null ? "/uploads/" + v.getIdImageUrl() : "";
                response.put("imageUrl", imageUrl);
                response.put("visitorName", v.getName());
                // 🪖 GROUP MANIFEST: lead, headcount and companions so the guard can check every ID in the party
                response.put("leadName", (v.getFullName() != null && !v.getFullName().isBlank()) ? v.getFullName() : v.getName());
                response.put("headcount", String.valueOf(Math.max(1, v.getGroupSize())));
                response.put("companions", String.join("\n", Visitor.parseCompanionNames(v.getCompanionNames())));

                // 🔥 SINGLE-SCAN LOCKOUT LOGIC
                if ("CHECKED-OUT".equals(v.getStatus())) {
                    response.put("status", "error");
                    response.put("message", "Visitor has already CHECKED-OUT today.");
                } else if ("REJECTED".equals(v.getStatus())) {
                    response.put("status", "error");
                    response.put("message", "ACCESS DENIED: Visitor rejected.");
                } else if ("DENIED_ENTRY".equals(v.getStatus())) {
                    // 🛑 A voided check-in is final: the same QR can never be used to re-enter
                    response.put("status", "error");
                    response.put("message", "ACCESS DENIED: Entry was already denied at the gate (" + v.getEntryDenialReason() + ").");
                } else if ("CHECKED-IN".equals(v.getStatus()) && v.getCheckInTime() != null
                        && Duration.between(v.getCheckInTime(), LocalDateTime.now()).compareTo(CHECKOUT_COOLDOWN) < 0) {
                    // ⏳ DEBOUNCE: a re-scan within 2 minutes of check-in leaves the visitor CHECKED-IN
                    response.put("status", "error");
                    response.put("message", "⏳ Cooldown: Visitor checked in less than 2 minutes ago. Please wait before checking out.");
                } else if ("CHECKED-IN".equals(v.getStatus())) {
                    // If scanning a second time, check them out.
                    v.setStatus("CHECKED-OUT");
                    v.setCheckOutTime(LocalDateTime.now());
                    repo.save(v);
                    sendInBackground(() -> emailService.sendCheckOutFeedback(v.getEmail(), v.getName(), v.getSecureToken()));
                    response.put("status", "success");
                    response.put("message", "👋 CHECKED-OUT: " + v.getName());
                } else {
                    String blocker = checkInBlocker(v);
                    if (blocker != null) {
                        response.put("status", "error");
                        response.put("message", blocker);
                    } else {
                        // ✅ VERIFY, THEN CONFIRM: a valid pass is only shown to the guard. Nothing is saved until they
                        // press Accept (/admin/api/confirm-check-in) or Deny Entry (/admin/api/deny-entry).
                        response.put("status", "ready");
                        response.put("visitorId", String.valueOf(v.getId()));
                        response.put("masterBooking", String.valueOf(v.isMasterBooking()));
                        response.put("message", (v.isMasterBooking() ? "🚌 MASTER BOOKING: " : "🔎 VERIFY: ") + v.getName());
                    }
                }
            } else {
                response.put("status", "error");
                response.put("message", "SECURITY ALERT: Fake or unassigned QR Code.");
            }
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Invalid QR Code format.");
        }
        return ResponseEntity.ok(response);
    }

    // Manual check-in is a staff override: unlike the QR scan, it does not require an APPROVED booking.
    // It only enforces the scheduled date and time window, and refuses rejected or checked-out visitors.
    @PostMapping("/admin/manual-checkin")
    public String manualCheckIn(@RequestParam("referenceId") String referenceId, RedirectAttributes redirectAttributes) {
        // The guard types the Booking Reference ID from the visitor's email (e.g. PAF-A7X92K), not the database id
        Visitor v = referenceId.isBlank() ? null
                : repo.findByReferenceId(referenceId.trim().toUpperCase(java.util.Locale.ROOT)).orElse(null);
        if (v != null) {
            if ("CHECKED-OUT".equals(v.getStatus())) {
                redirectAttributes.addFlashAttribute("error", "Visitor has already CHECKED-OUT today.");
            } else if ("DENIED_ENTRY".equals(v.getStatus())) {
                // 🛑 Without this branch the manual override below would check a denied visitor straight back in
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Entry was already denied at the gate (" + v.getEntryDenialReason() + ").");
            } else if ("REJECTED".equals(v.getStatus()) || "CANCELLED_OVERRIDE".equals(v.getStatus()) || "CANCELLED_BY_VISITOR".equals(v.getStatus()) || "CANCELLED".equals(v.getStatus())) {
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Visitor rejected or booking cancelled.");
            } else if ("NO_SHOW".equals(v.getStatus())) {
                // 🚫 The visit window passed without a check-in; the visitor must book again
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: This booking was marked as a no-show. The visitor must book a new visit.");
            } else if ("CHECKED-IN".equals(v.getStatus())) {
                v.setStatus("CHECKED-OUT");
                v.setCheckOutTime(LocalDateTime.now());
                repo.save(v);
                emailService.sendCheckOutFeedback(v.getEmail(), v.getName(), v.getSecureToken());
                redirectAttributes.addFlashAttribute("success", "👋 CHECKED-OUT via Manual Override: " + v.getName());
            } else {
                // 🛂 Admitting a booking nobody approved is the Director's override; a Guard can only check in an approved pass
                if (!"APPROVED".equals(v.getStatus()) && !com.paf.aerovisit.config.SecurityConfig.isDirector()) {
                    redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Booking is not approved (status: " + v.getStatus() + ").");
                    return "redirect:/admin";
                }
                // 🚫 Manual check-in is a Director override for pending bookings, but never for a banned face
                if (isFaceBanned(v)) {
                    redirectAttributes.addFlashAttribute("error", BANNED_ENTRY);
                    return "redirect:/admin";
                }
                LocalDate today = LocalDate.now();
                LocalTime now = LocalTime.now();
                if (!today.equals(v.getVisitDate())) {
                    redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Scheduled for " + v.getVisitDate() + ".");
                    return "redirect:/admin";
                }
                // 🕒 Same booked-time-window rule as the QR scan
                String windowError = timeWindowBlocker(v.getTimeWindow(), now);
                if (windowError != null) {
                    redirectAttributes.addFlashAttribute("error", windowError);
                    return "redirect:/admin";
                }
                if (v.getVisitTime() != null) {
                    LocalTime allowedStart = v.getVisitTime().minusMinutes(30);
                    LocalTime allowedEnd = v.getVisitTime().plusHours(2);
                    if (now.isBefore(allowedStart) || now.isAfter(allowedEnd)) {
                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US);
                        String formattedTime = v.getVisitTime().format(formatter);
                        redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Outside of approved visit window. Scheduled time is " + formattedTime + ".");
                        return "redirect:/admin"; 
                    }
                }
                
                v.setStatus("CHECKED-IN");
                v.setCheckedInBy(getActiveGuardName() + " (Manual)");
                v.setCheckInTime(LocalDateTime.now());
                repo.save(v);
                emailService.sendCheckInGuidelines(v.getEmail(), v.getName(), getActiveTourGuideName());
                String groupAddon = (v.getGroupSize() > 1) ? " (Group of " + v.getGroupSize() + ")" : "";
                redirectAttributes.addFlashAttribute("success", "🟢 Checked-In via Manual Override: " + v.getName() + groupAddon);
            }
        } else {
            redirectAttributes.addFlashAttribute("error", "Reference ID not found in database.");
        }
        return "redirect:/admin";
    }

    // Runs on its own two small daemon threads ("gate-mail"), not the JVM-wide ForkJoin common pool, which is sized to
    // the CPU count and meant for short computations: a slow SMTP server can then only delay other gate emails.
    private static final java.util.concurrent.ExecutorService GATE_MAIL = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
        Thread t = new Thread(task, "gate-mail");
        t.setDaemon(true);
        return t;
    });

    // 📨 Sends a scanner-triggered email on a background thread so the gate gets its answer without waiting for SMTP.
    // The check-in / check-out is already saved; a failed send is logged here instead of disappearing silently.
    private static void sendInBackground(Runnable email) {
        java.util.concurrent.CompletableFuture.runAsync(email, GATE_MAIL).exceptionally(e -> {
            System.err.println("❌ Background gate email failed: " + e);
            return null;
        });
    }

    // Why this visitor cannot be checked in right now, or null when they can (APPROVED and scheduled for today).
    // Shared by the scan (verify) and the confirm step, so a stale card can never admit someone the scan would refuse.
    private String checkInBlocker(Visitor v) {
        if ("CHECKED-IN".equals(v.getStatus())) return "Visitor is already CHECKED-IN.";
        // 🔒 Pending / unverified / denied bookings cannot enter, even with a valid token
        if (!"APPROVED".equals(v.getStatus())) return "ACCESS DENIED: Booking is not approved (status: " + v.getStatus() + ").";
        // 🚫 Backstop for a pass whose face was banned after approval (the ban also revokes open bookings)
        if (isFaceBanned(v)) return BANNED_ENTRY;
        // 🔒 The QR is only valid on the scheduled date
        if (!LocalDate.now().equals(v.getVisitDate())) return "ACCESS DENIED: Scheduled for " + v.getVisitDate() + ".";
        // 🕒 ...and within the booked time window (opens 15 minutes early)
        return timeWindowBlocker(v.getTimeWindow(), LocalTime.now());
    }

    // Why check-in is closed at 'now' for this time window label, or null when it is open.
    // Opens EARLY_CHECK_IN_MINUTES before the start (never before midnight) and stays open until the end time, inclusive.
    // A missing or unreadable label (old bookings) is not blocked: the date check and the guard's ID check still apply.
    static String timeWindowBlocker(String timeWindow, LocalTime now) {
        LocalTime[] window = parseTimeWindow(timeWindow);
        if (window == null) return null;
        int opensAt = Math.max(0, window[0].toSecondOfDay() - EARLY_CHECK_IN_MINUTES * 60);
        if (now.toSecondOfDay() < opensAt) {
            return "ACCESS DENIED: Check-in opens at " + LocalTime.ofSecondOfDay(opensAt).format(GATE_TIME) + ".";
        }
        if (now.isAfter(window[1].withSecond(59).withNano(999_999_999))) {
            return "ACCESS DENIED: Time window ended at " + window[1].format(GATE_TIME) + ".";
        }
        return null;
    }

    // {start, end} from a label like "Morning (08:00 AM - 12:00 PM)"; null if it can't be read or the end isn't after the start
    public static LocalTime[] parseTimeWindow(String timeWindow) { // public: the 11:59 PM no-show sweep uses the same rule
        if (timeWindow == null) return null;
        java.util.regex.Matcher m = WINDOW_TIMES.matcher(timeWindow);
        if (!m.find()) return null;
        LocalTime start = clockTime(m.group(1), m.group(2), m.group(3));
        LocalTime end = clockTime(m.group(4), m.group(5), m.group(6));
        if (start == null || end == null || !end.isAfter(start)) return null;
        return new LocalTime[] { start, end };
    }

    // 12-hour clock parts to a LocalTime ("12:00 AM" = midnight, "12:00 PM" = noon); null if out of range
    private static LocalTime clockTime(String hour, String minute, String amPm) {
        int h = Integer.parseInt(hour);
        int min = Integer.parseInt(minute);
        if (h < 1 || h > 12 || min > 59) return null;
        boolean pm = amPm.equalsIgnoreCase("PM");
        return LocalTime.of(h % 12 + (pm ? 12 : 0), min);
    }

    // ✅ CONFIRM step of the gate scan: the guard has matched the ID(s) to the pass and presses Accept
    @PostMapping("/admin/api/confirm-check-in")
    @ResponseBody
    public ResponseEntity<Map<String, String>> confirmCheckIn(@RequestParam("visitorId") Long visitorId,
                                                              @RequestParam(value = "headcount", required = false) Integer headcount) {
        Map<String, String> response = new HashMap<>();
        Visitor v = repo.findById(visitorId).orElse(null);
        String error = (v == null) ? "Visitor ID not found in database." : checkInBlocker(v);
        // 🚌 Master Booking: the guard enters how many guests actually arrived
        if (error == null && v.isMasterBooking() && (headcount == null || headcount < 1 || headcount > v.getGroupSize())) {
            error = "Headcount must be between 1 and " + v.getGroupSize() + " (the approved group size).";
        }
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            return ResponseEntity.ok(response);
        }

        // --- SUCCESSFUL CHECK-IN PROTOCOL ---
        v.setStatus("CHECKED-IN");
        v.setCheckedInBy(getActiveGuardName());
        v.setCheckInTime(LocalDateTime.now());
        if (v.isMasterBooking()) {
            // Record who actually arrived; every dashboard count already reads groupSize
            v.setBookedGroupSize(v.getGroupSize());
            v.setGroupSize(headcount);
        }
        repo.save(v);
        String tourGuide = getActiveTourGuideName(); // looked up now, so the email names the guide on duty at check-in
        sendInBackground(() -> emailService.sendCheckInGuidelines(v.getEmail(), v.getName(), tourGuide));

        String groupAddon = (v.getBookedGroupSize() != null) ? " (" + v.getGroupSize() + " of " + v.getBookedGroupSize() + " arrived)"
                : (v.getGroupSize() > 1) ? " (Group of " + v.getGroupSize() + ")" : "";
        response.put("status", "success");
        response.put("message", "🟢 CHECKED-IN: " + v.getName() + groupAddon);
        return ResponseEntity.ok(response);
    }

    // 🛑 GATE DENIAL from the scanner result card: turns the visitor away when the IDs fail verification
    @PostMapping("/admin/api/deny-entry")
    @ResponseBody
    public ResponseEntity<Map<String, String>> denyEntryAjax(@RequestParam("visitorId") Long visitorId,
                                                             @RequestParam(value = "reason", required = false) String reason) {
        Map<String, String> response = new HashMap<>();
        Visitor v = repo.findById(visitorId).orElse(null);
        String error = denyEntry(v, reason, null);
        response.put("status", error == null ? "success" : "error");
        response.put("message", error == null ? "⛔ ENTRY DENIED: " + v.getName() + ". Denial logged." : error);
        return ResponseEntity.ok(response);
    }

    // 🛑 GATE DENIAL from the dashboard (Checked In rows of today's manifest)
    @PostMapping("/admin/deny-entry")
    public String denyEntryFromDashboard(@RequestParam("visitorId") Long visitorId,
                                         @RequestParam(value = "reason", required = false) String reason,
                                         @RequestParam(value = "notes", required = false) String notes,
                                         RedirectAttributes redirectAttributes) {
        Visitor v = repo.findById(visitorId).orElse(null);
        String error = denyEntry(v, reason, notes);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "⛔ ENTRY DENIED: " + v.getName() + ". Check-in voided and logged.");
        }
        return "redirect:/admin";
    }

    // Turns a same-day APPROVED pass (scanner, before check-in) or CHECKED-IN visit (dashboard reversal) into DENIED_ENTRY,
    // recording who denied it, when and why; null on success, else the error. Any checkedInBy / checkInTime is kept,
    // so a reversed check-in shows both the check-in and its reversal.
    private String denyEntry(Visitor v, String reason, String notes) {
        if (v == null) return "Visitor ID not found in database.";
        if (!"APPROVED".equals(v.getStatus()) && !"CHECKED-IN".equals(v.getStatus())) {
            return "Only an approved or checked-in visitor can be denied entry (status: " + v.getStatus() + ").";
        }
        if (!LocalDate.now().equals(v.getVisitDate())) return "Entry can only be denied on the day of the visit.";
        if (reason == null || reason.isBlank()) return "Select a reason for denying entry.";
        String fullReason = reason.trim();
        if (notes != null && !notes.isBlank()) fullReason += " | Notes: " + notes.trim();
        if (fullReason.length() > 500) return "Reason is too long (max 500 characters).";

        v.setStatus("DENIED_ENTRY");
        v.setEntryDeniedBy(getActiveGuardName());
        v.setEntryDeniedAt(LocalDateTime.now());
        v.setEntryDenialReason(fullReason);
        v.setQrCodeUrl(null);
        repo.save(v);
        return null;
    }

    @PostMapping("/admin/approve/{id}")
    public String approveVisitor(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id == null) return "redirect:/admin";
        Visitor v = repo.findById(id).orElse(null);
        if (v != null && "DENIED_ENTRY".equals(v.getStatus())) {
            // 🛑 Re-approving would issue a fresh QR and undo the gate's decision; the visitor must book again
            redirectAttributes.addFlashAttribute("error", "Approval refused: this visitor was denied entry at the gate. The denial is final for this booking.");
            return "redirect:/admin";
        }
        if (v != null && !v.awaitsApproval()) {
            // 🔒 STATE MACHINE: only a pending request can be approved, so a checked-in, checked-out, rejected or
            // cancelled booking can never be moved back to APPROVED (which would re-activate its QR code)
            redirectAttributes.addFlashAttribute("error", "Invalid state transition: a booking with status "
                    + (v.getStatus() != null ? v.getStatus() : "UNKNOWN") + " cannot be approved. Only pending requests can be approved.");
            return "redirect:/admin";
        }
        if (v != null && isFaceBanned(v)) {
            redirectAttributes.addFlashAttribute("error", BANNED_APPROVAL);
            return "redirect:/admin";
        }
        if (v != null) {
            // 👻 GHOST SLOTS: a standard booking needs a real slot for its date and time window (never created, deleted,
            // or a made-up window from a hand-made request). Master Bookings may take a day with no public slots.
            if (!v.isMasterBooking() && slotCapacity(v) <= 0) {
                redirectAttributes.addFlashAttribute("error", INVALID_SLOT);
                return "redirect:/admin";
            }
            // 🔒 Just-in-time capacity check: PENDING requests hold no seats, so the slot is re-counted at approval.
            // Master Bookings are exempt: the VIP buyout override below takes over the whole date.
            if (!v.isMasterBooking() && !hasSeatsForApproval(v)) {
                redirectAttributes.addFlashAttribute("error", "Approval failed: Not enough seats remaining in this time slot.");
                return "redirect:/admin";
            }
            v.setStatus("APPROVED");
            try {
                Path uploadPath = Paths.get("uploads");
                if (!Files.exists(uploadPath)) { Files.createDirectories(uploadPath); }
                
                // 🔥 ANTI-FORGERY FIX: Use the secure UUID token instead of plain text!
                String qrContent = v.getSecureToken() != null ? v.getSecureToken() : java.util.UUID.randomUUID().toString();
                v.setSecureToken(qrContent); // Ensure it's saved if it was generated here
                
                String qrFileName = "QR_" + v.getId() + ".png";
                String qrPath = "uploads/" + qrFileName;
                QRCodeGenerator.generateQRCode(qrContent, qrPath);
                v.setQrCodeUrl(qrFileName);

                try {
                    saveWithinCapacity(v);
                } catch (SeatReservationService.CapacityFullException | org.springframework.dao.PessimisticLockingFailureException full) {
                    // 🔒 Another approval took the last seats after the check above: nothing was saved, the request stays pending
                    Files.deleteIfExists(uploadPath.resolve(qrFileName));
                    redirectAttributes.addFlashAttribute("error", full instanceof SeatReservationService.CapacityFullException
                            ? "Approval failed: Not enough seats remaining in this time slot."
                            : "Approval failed: this time slot is being updated by another approval right now. Please try again.");
                    return "redirect:/admin";
                }
                emailService.sendApprovalEmailWithQR(v.getEmail(), v.getName(), v.getVisitDate().toString(), qrFileName, v.getReferenceId());
                redirectAttributes.addFlashAttribute("success", "Visitor approved and Email sent successfully!");
                if (v.isMasterBooking()) {
                    // 🔒 VIP buyout override: cancel standard visitors already APPROVED for that date and notify each one
                    int cancelled = 0;
                    try {
                        List<Visitor> displaced = repo.findApprovedVisitorsToOverride(v.getVisitDate(), v.getId());
                        for (Visitor d : displaced) {
                            d.setStatus("CANCELLED_OVERRIDE");
                            d.setQrCodeUrl(null);
                        }
                        repo.saveAll(displaced);
                        cancelled = displaced.size();
                        for (Visitor d : displaced) {
                            try {
                                emailService.sendOverrideCancellationEmail(d.getEmail(), d.getName(), d.getVisitDate().toString(), d.getReferenceId());
                            } catch (Exception mailError) {
                                System.err.println("❌ Override email failed for visitor #" + d.getId() + ": " + mailError.getMessage());
                            }
                        }
                    } catch (Exception overrideError) {
                        System.err.println("❌ Buyout override failed for " + v.getVisitDate() + ": " + overrideError);
                    }

                    // 🔒 VIP buyout: approval closes the date to public bookings; flag bookings already made for it
                    long others = repo.findByVisitDate(v.getVisitDate()).stream()
                            .filter(o -> !o.getId().equals(v.getId()) && !"REJECTED".equals(o.getStatus()) && !"CANCELLED_OVERRIDE".equals(o.getStatus()) && !"CANCELLED_BY_VISITOR".equals(o.getStatus()) && !"DENIED_ENTRY".equals(o.getStatus()))
                            .count();
                    redirectAttributes.addFlashAttribute("success", "🔒 VIP Buyout approved: " + v.getVisitDate() + " is now closed to public bookings."
                            + (cancelled > 0 ? " 📭 " + cancelled + " approved visitor(s) were cancelled and notified by email." : "")
                            + (others > 0 ? " ⚠️ " + others + " other booking(s) already exist for that date and need to be contacted or rescheduled." : ""));
                }
            } catch (Exception e) {
                System.err.println("❌ Approval QR/Email failed for visitor #" + v.getId() + ": " + e);
                e.printStackTrace();
                redirectAttributes.addFlashAttribute("error", "Approved, but failed to generate QR or send email: " + e.getMessage());
            }
        }
        return "redirect:/admin";
    }

    // ⚠️ DIRECTOR OVERRIDE: approves a pending request even when its time slot is already full.
    // Same QR + email steps as approveVisitor above, without the capacity check. SecurityConfig limits this path to the Director.
    @PostMapping("/admin/approve-override/{id}")
    public String forceApproveVisitor(@PathVariable Long id, java.security.Principal principal, RedirectAttributes redirectAttributes) {
        Visitor v = repo.findById(id).orElse(null);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Visitor ID not found in database.");
            return "redirect:/admin";
        }
        if (!"PENDING".equals(v.getStatus()) && !"PENDING_MANUAL_REVIEW".equals(v.getStatus())) {
            // 🛑 Also keeps denied, cancelled and rejected bookings from being revived through the override
            redirectAttributes.addFlashAttribute("error", "Force Approve only applies to a pending request (status: " + v.getStatus() + ").");
            return "redirect:/admin";
        }
        if (v.isMasterBooking()) {
            // Master Bookings already skip the capacity check, and only the standard approval runs the VIP buyout rules
            redirectAttributes.addFlashAttribute("error", "Master Bookings are exempt from the capacity check. Use the standard Approve button.");
            return "redirect:/admin";
        }
        // The override skips the seat count only: a banned face or a slot that does not exist still blocks it
        if (isFaceBanned(v)) {
            redirectAttributes.addFlashAttribute("error", BANNED_APPROVAL);
            return "redirect:/admin";
        }
        if (slotCapacity(v) <= 0) {
            redirectAttributes.addFlashAttribute("error", INVALID_SLOT);
            return "redirect:/admin";
        }

        String qrFileName = "QR_" + v.getId() + ".png";
        try {
            Path uploadPath = Paths.get("uploads");
            if (!Files.exists(uploadPath)) { Files.createDirectories(uploadPath); }

            String qrContent = v.getSecureToken() != null ? v.getSecureToken() : java.util.UUID.randomUUID().toString();
            QRCodeGenerator.generateQRCode(qrContent, "uploads/" + qrFileName);

            v.setSecureToken(qrContent);
            v.setQrCodeUrl(qrFileName);
            v.setStatus("APPROVED");
            repo.save(v);
        } catch (Exception e) {
            System.err.println("❌ Force approval failed for visitor #" + v.getId() + ": " + e);
            redirectAttributes.addFlashAttribute("error", "Force approval failed, the booking is still pending: " + e.getMessage());
            return "redirect:/admin";
        }
        System.out.println("⚠️ CAPACITY OVERRIDE: visitor #" + v.getId() + " force-approved by " + (principal != null ? principal.getName() : "unknown"));

        try {
            emailService.sendApprovalEmailWithQR(v.getEmail(), v.getName(), String.valueOf(v.getVisitDate()), qrFileName, v.getReferenceId());
            redirectAttributes.addFlashAttribute("success", "⚠️ Force-approved over capacity: " + v.getName() + ". Approval email sent.");
        } catch (Exception e) {
            System.err.println("❌ Force approval email failed for visitor #" + v.getId() + ": " + e);
            redirectAttributes.addFlashAttribute("error", "Force-approved, but the approval email failed to send: " + e.getMessage());
        }
        return "redirect:/admin";
    }

    // True when the visitor's group still fits its date and time window, counting only seat-holding
    // bookings (APPROVED / CHECKED-IN / CHECKED-OUT) and leaving out the visitor's own booking
    private boolean hasSeatsForApproval(Visitor v) {
        int capacity = slotCapacity(v); // no slot = 0 seats (there is no default capacity any more)
        int approvedSeats = 0;
        if (v.getVisitDate() != null) {
            for (Visitor o : repo.findByVisitDate(v.getVisitDate())) {
                if (!o.getId().equals(v.getId()) && o.holdsSeat() && java.util.Objects.equals(o.getTimeWindow(), v.getTimeWindow())) {
                    approvedSeats += Math.max(1, o.getGroupSize());
                }
            }
        }
        return approvedSeats + Math.max(1, v.getGroupSize()) <= capacity;
    }

    // 🔒 RACE-SAFE SAVE: a standard approval is saved under a row lock on its slot with the seat count re-read inside the
    // lock (SeatReservationService), so two approvals can't both take the last seats. Master Bookings are exempt (VIP
    // buyout). Web-layer test slices without SeatReservationService save directly, as before.
    private void saveWithinCapacity(Visitor v) {
        if (v.isMasterBooking() || seatReservation == null) repo.save(v);
        else seatReservation.saveIfSeatsRemain(v);
    }

    // Seats configured for the visit's date and time window; 0 when no such slot exists
    private int slotCapacity(Visitor v) {
        if (v.getVisitDate() == null || v.getTimeWindow() == null) return 0;
        return bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), v.getTimeWindow()).stream()
                .mapToInt(BookingSlot::getMaxCapacity)
                .sum();
    }

    // True when the face saved with this booking matches a banned face (whatever email the booking uses).
    // A booking without face data (made before face matching, or purged) can't be matched and is not blocked here.
    private boolean isFaceBanned(Visitor v) {
        if (faceBanRepo == null) return false;
        double[] face = com.paf.aerovisit.service.FaceEncodings.parse(v.getFaceEncoding());
        return face != null && com.paf.aerovisit.service.FaceEncodings.isBanned(face, faceBanRepo.findAll());
    }

    @PostMapping("/admin/reject/{id}")
    public String rejectVisitor(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (id == null) return "redirect:/admin";
        Visitor v = repo.findById(id).orElse(null);
        if (v != null && "DENIED_ENTRY".equals(v.getStatus())) {
            // 🛑 A gate denial is a final audit record and must not be overwritten
            redirectAttributes.addFlashAttribute("error", "This visitor was denied entry at the gate; that record cannot be changed.");
            return "redirect:/admin";
        }
        if (v != null && v.visitUnderwayOrOver()) {
            redirectAttributes.addFlashAttribute("error", Visitor.CANNOT_REJECT_MESSAGE);
            return "redirect:/admin";
        }
        if (v != null) {
            v.setStatus("REJECTED");
            v.setQrCodeUrl(null);
            repo.save(v);
        }
        return "redirect:/admin";
    }

    private String getActiveGuardName() {
        LocalTime now = LocalTime.now();
        LocalDate today = LocalDate.now();
        List<StaffSchedule> allSchedules = staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc();
        List<String> activeGuards = new ArrayList<>();
        for (StaffSchedule sched : allSchedules) {
            if ("Gate Guard".equalsIgnoreCase(sched.getRole()) && today.equals(sched.getDutyDate())
                    && shiftCoversNow(sched.getShiftTime(), now)) {
                activeGuards.add(sched.getStaffName());
            }
        }
        if (!activeGuards.isEmpty()) return String.join(" & ", activeGuards);
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return (auth != null) ? auth.getName() + " (Unscheduled)" : "System Default";
    }

    // Tour Guide(s) whose shift covers the current time, using the same shift windows as getActiveGuardName(); null if none
    private String getActiveTourGuideName() {
        LocalTime now = LocalTime.now();
        List<String> activeGuides = new ArrayList<>();
        for (StaffSchedule sched : staffRepo.findTourGuidesOnDuty(LocalDate.now())) {
            if (shiftCoversNow(sched.getShiftTime(), now)
                    && sched.getStaffName() != null && !sched.getStaffName().isBlank()) {
                activeGuides.add(sched.getStaffName());
            }
        }
        return activeGuides.isEmpty() ? null : String.join(" & ", activeGuides);
    }

    // Whether a duty shift is running at 'now'. A shift stored as a time window label, e.g. "Midday (11:00 AM - 02:00 PM)",
    // uses its own hours; the original roster strings ("Morning (0800H-1200H)" etc.) keep their Morning / Afternoon /
    // Full Day rule exactly as before.
    static boolean shiftCoversNow(String shift, LocalTime now) {
        if (shift == null) return false;
        LocalTime[] window = parseTimeWindow(shift);
        if (window != null) {
            return !now.isBefore(window[0]) && !now.isAfter(window[1].withSecond(59).withNano(999_999_999));
        }
        boolean isMorning = shift.contains("Morning") && now.isBefore(LocalTime.of(13, 0));
        boolean isAfternoon = shift.contains("Afternoon") && !now.isBefore(LocalTime.of(12, 0));
        return isMorning || isAfternoon || shift.contains("Full Day");
    }
}
