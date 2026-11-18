package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.UploadMirror;
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
    private final EmailService emailService;
    private final BookingSlotRepository bookingRepo;

    // 🧬 Face-ban checks on approval and check-in. Optional (like zoneRepo in VisitorPortalController) so the
    // existing web-layer test slices without this mock still start; the real app always has it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BlacklistedFaceRepository faceBanRepo;

    // Race-safe capacity save for approvals. Optional for the same reason as faceBanRepo; the real app always has it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SeatReservationService seatReservation;

    // 🔒 Stale-write guard (security fix M4). Optional, so web-layer test slices without it save as before.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.VisitorRowGuard rowGuard;

    // 📝 The feedback email, once per booking (a check-out after the window-end sweep sends nothing again). Optional, so
    // test slices without it still start: then the booking's own timestamp guards the send.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.FeedbackEmails feedbackEmails;

    // ☁️ Copies each new QR code to S3 (off locally). Optional, so test slices without it still start.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.UploadMirror uploadMirror;

    // 💳 Payment at approval (billing number, deadline). Optional, so test slices without it still start; without it a
    // booking that needs payment can't be approved (it is never given a QR unpaid).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentWorkflow paymentWorkflow;

    // 🔔 Header bell (gate denials, failed approval emails, slots filling up). Optional, so test slices without it still start.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.NotificationService notificationService;

    // 📮 Approval and manual check-in/out emails go out in the background, so Approve and the manual
    // gate buttons answer at once instead of waiting for the mail server. Optional: without it (test slices) the email
    // is sent right away, with the same failure handling (MailOutbox.send).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.MailOutbox mailOutbox;

    // 🚦 Two gates pressing Accept (or checking out) on the same pass at once: only the first one goes through, so the
    // visitor is checked in once and gets one email. Optional: without it (test slices) the status is saved as before.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.paf.aerovisit.service.GateClaim gateClaim;

    static final String ALREADY_CHECKED_IN = "Visitor is already CHECKED-IN: another gate accepted this pass a moment ago.";
    static final String ALREADY_CHECKED_OUT = "Visitor has already CHECKED-OUT: another gate checked this pass out a moment ago.";

    // True if this request is the one that moves the visitor on (see GateClaim)
    private boolean claim(Visitor v, String to) {
        return com.paf.aerovisit.service.GateClaim.claim(gateClaim, v.getId(), v.getStatus(), to);
    }

    static final String APPROVED_MESSAGE = "Visitor approved. The approval email with the QR code is on its way.";

    // ⏱️ The gate's clock: the shared Clock bean (ClockConfig, real Manila time) in production; null = the system clock,
    // read on every call. Tests fix it so every time-window case runs the same at any hour
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    java.time.Clock clock;
    private java.time.Clock clock() { return clock != null ? clock : java.time.Clock.systemDefaultZone(); }

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

    public GuardOperationController(VisitorRepository repo, EmailService emailService, BookingSlotRepository bookingRepo) {
        this.repo = repo;
        this.emailService = emailService;
        this.bookingRepo = bookingRepo;
    }

    @GetMapping("/admin/scanner")
    public String showScannerPage() {
        return "scanner";
    }

    @PostMapping("/admin/api/scan")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> scanVisitorAjax(@RequestParam("qrData") String qrData) {
        Map<String, Object> response = new HashMap<>();
        try {
            // 🔥 NEW: Search by Secure Token instead of plain ID
            // 🔒 Only the unguessable secure token is accepted. The old "ID:x | Name:y" format was
            // removed because anyone could forge it with a free QR generator.
            // Looks up the one matching visitor directly instead of loading every visitor.
            Visitor v = repo.findBySecureToken(qrData).orElse(null);

            if (v != null) {
                // Ensure we have an image URL to send back for visual verification
                // 🪪 The ID photo (an older booking's selfie when it has one), labelled for the guard
                String imageUrl = v.gatePhoto() != null ? "/uploads/" + v.gatePhoto() : "";
                response.put("imageUrl", imageUrl);
                response.put("imageLabel", v.gatePhotoLabel());
                response.put("visitorName", v.getName());
                // 🪖 GROUP: the representative and the headcount. Companion names exist only on older bookings
                // (empty for new ones); the scanner lists them when present.
                response.put("leadName", (v.getFullName() != null && !v.getFullName().isBlank()) ? v.getFullName() : v.getName());
                response.put("headcount", String.valueOf(Math.max(1, v.getGroupSize())));
                response.put("companions", String.join("\n", Visitor.parseCompanionNames(v.getCompanionNames())));
                // 💱 Admission breakdown and total, e.g. "2 Discounted · 3 Regular · 1 Free · PHP 650.00" (empty for older bookings)
                response.put("admission", v.admissionCounted()
                        ? v.admissionSummary() + (v.getAdmissionTotalCentavos() != null ? " · " + v.admissionTotalText() : "") : "");
                // 💳 THE PAYMENT, shown next to the ID photo on every scan: billing number, amount due, status, the receipt
                // (only through the checked /admin/payments/{id}/receipt, and only when this user may see it) and who
                // verified it ("Free, no payment" when nothing is due)
                putPayment(response, v);

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
                        && Duration.between(v.getCheckInTime(), LocalDateTime.now(clock())).compareTo(CHECKOUT_COOLDOWN) < 0) {
                    // ⏳ DEBOUNCE: a re-scan within 2 minutes of check-in leaves the visitor CHECKED-IN
                    response.put("status", "error");
                    response.put("message", "⏳ Cooldown: Visitor checked in less than 2 minutes ago. Please wait before checking out.");
                } else if ("CHECKED-IN".equals(v.getStatus()) && !claim(v, "CHECKED-OUT")) {
                    response.put("status", "error");
                    response.put("message", ALREADY_CHECKED_OUT);
                } else if ("CHECKED-IN".equals(v.getStatus())) {
                    // If scanning a second time, check them out.
                    v.setStatus("CHECKED-OUT");
                    v.setCheckOutTime(LocalDateTime.now(clock()));
                    repo.save(v);
                    // 📝 The feedback email, unless the window-end sweep already sent it (once per booking)
                    sendInBackground(() -> com.paf.aerovisit.service.FeedbackEmails.sendOnce(feedbackEmails, emailService, v, LocalDateTime.now(clock())));
                    response.put("status", "success");
                    response.put("message", "👋 CHECKED-OUT: " + v.getName());
                } else if (v.walkIn()) {
                    // 🚶 SCAN TO OPEN: a walk-in QR only opens its Process panel (the same ticks and server rules as the
                    // dashboard's Process button), over the camera on the scanner page; "panel" carries what it shows
                    // (WalkInPanelData, the same values as the dashboard's data-px-*). Nothing is saved here.
                    String refusal = walkInScanRefusal(v);
                    if (refusal != null) {
                        response.put("status", "error");
                        response.put("message", refusal);
                    } else {
                        response.put("status", "process");
                        response.put("visitorId", String.valueOf(v.getId()));
                        response.put("processUrl", WALK_IN_PROCESS_URL + v.getId());
                        response.put("panel", WalkInPanelData.of(v, banOf(v), receiptVisible(v)));
                        response.put("message", "🚶 WALK-IN: " + v.getName());
                    }
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
                        // 🪪 Free / Discounted visitors: the card shows this tick and Accept waits for it (empty = none)
                        response.put("idsCheckText", v.eligibilityCheckText());
                        response.put("message", "🔎 VERIFY: " + v.getName());
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

    // 🚶 Where a walk-in scan sends the guard: the Walk-ins tab with that walk-in's Process panel opened (guard.html)
    static final String WALK_IN_PROCESS_URL = "/guard/dashboard?tab=walkins&process=";
    static final String WALK_IN_USE_PROCESS = "This is a walk-in: check the group in with its Process panel (scan its QR or use Process on the Walk-ins tab).";
    private static final java.time.format.DateTimeFormatter WALK_IN_DAY =
            java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.US);

    // Why a scanned walk-in QR can't open its Process panel now, or null. Checked-in / checked-out / denied / rejected
    // passes were already answered by the scan's earlier branches (a second scan of a group inside checks it out).
    String walkInScanRefusal(Visitor v) {
        if (!LocalDate.now(clock()).equals(v.getVisitDate())) {
            return "WRONG DAY: This walk-in QR was for " + (v.getVisitDate() == null ? "another day" : v.getVisitDate().format(WALK_IN_DAY))
                    + ". Walk-in passes work only on the day they were made.";
        }
        if ("NO_SHOW".equals(v.getStatus())) {
            return "EXPIRED: This walk-in pass was released because the group was not checked in in time. The visitor must make a new walk-in booking.";
        }
        if (Visitor.STATUS_PAYMENT_EXPIRED.equals(v.getStatus())) {
            return "EXPIRED: This walk-in pass was released because it was not paid in time. The visitor must make a new walk-in booking.";
        }
        if (!"APPROVED".equals(v.getStatus())) {
            return "ACCESS DENIED: This walk-in booking was cancelled (status: " + v.getStatus() + ").";
        }
        return null;
    }

    // 💳 The scan card's payment block (see scanVisitorAjax)
    private void putPayment(Map<String, Object> response, Visitor v) {
        boolean due = v.paymentRequired();
        response.put("billingNumber", due && v.getBillingNumber() != null ? v.getBillingNumber() : "");
        response.put("amountDue", due ? v.amountDueText() : "");
        response.put("paymentStatus", due ? v.paymentStatusLabel() : "");
        response.put("paymentNote", v.paymentNote());
        boolean receipt = v.getReceiptImageUrl() != null && receiptVisible(v);
        response.put("receiptUrl", receipt ? "/admin/payments/" + v.getId() + "/receipt" : "");
    }

    // May the signed-in user see this booking's receipt (the rule of /admin/payments/{id}/receipt)
    private boolean receiptVisible(Visitor v) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return PaymentAdminController.canViewReceipt(auth, v, LocalDate.now(clock()));
    }

    // The ban a flagged walk-in's name resembles (the same lookup as the Guard Dashboard), or null
    private com.paf.aerovisit.model.BlacklistedFace banOf(Visitor v) {
        if (faceBanRepo == null || v.getPossibleBanId() == null) return null;
        return faceBanRepo.findById(v.getPossibleBanId()).orElse(null);
    }

    // Manual check-in is a staff override: unlike the QR scan, it does not require an APPROVED booking.
    // It only enforces the scheduled date and time window, and refuses rejected or checked-out visitors.
    @PostMapping("/admin/manual-checkin")
    public String manualCheckIn(@RequestParam("referenceId") String referenceId,
                                @RequestParam(value = "returnTo", required = false) String returnTo,
                                @RequestParam(value = "banChecked", required = false) String banChecked,
                                @RequestParam(value = "idsChecked", required = false) String idsChecked,
                                RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // ↩️ the page the officer acted from, or /admin
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
            } else if (Visitor.STATUS_PAYMENT_EXPIRED.equals(v.getStatus())) {
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: This booking was released because it was not paid by the deadline.");
            } else if (Visitor.STATUS_RESCHEDULE_REQUIRED.equals(v.getStatus())) {
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: The museum closed on this booking's date; the visitor must choose a new date in Manage Booking.");
            } else if ("NO_SHOW".equals(v.getStatus())) {
                // 🚫 The visit window passed without a check-in; the visitor must book again
                redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: This booking was marked as a no-show. The visitor must book a new visit.");
            } else if ("CHECKED-IN".equals(v.getStatus()) && !claim(v, "CHECKED-OUT")) {
                redirectAttributes.addFlashAttribute("error", ALREADY_CHECKED_OUT);
            } else if ("CHECKED-IN".equals(v.getStatus())) {
                v.setStatus("CHECKED-OUT");
                v.setCheckOutTime(LocalDateTime.now(clock()));
                repo.save(v);
                // 📝 The feedback email, unless the window-end sweep already sent it (once per booking)
                com.paf.aerovisit.service.FeedbackEmails.sendOnce(feedbackEmails, emailService, v, LocalDateTime.now(clock()));
                redirectAttributes.addFlashAttribute("success", "👋 CHECKED-OUT via Manual Override: " + v.getName());
            } else {
                // 🛂 Admitting a booking nobody approved is the Director's override; a Guard can only check in an approved pass
                if (!"APPROVED".equals(v.getStatus()) && !com.paf.aerovisit.config.SecurityConfig.isDirector()) {
                    redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Booking is not approved (status: " + v.getStatus() + ").");
                    return back;
                }
                // 💳 ...and never for a booking whose payment is not verified
                if (!v.paymentCleared()) {
                    redirectAttributes.addFlashAttribute("error", PAYMENT_NOT_VERIFIED);
                    return back;
                }
                // 🚫 Manual check-in is a Director override for pending bookings, but never for a banned visitor
                if (isBanned(v)) {
                    redirectAttributes.addFlashAttribute("error", BANNED_ENTRY);
                    return back;
                }
                LocalDate today = LocalDate.now(clock());
                LocalTime now = LocalTime.now(clock());
                if (!today.equals(v.getVisitDate())) {
                    redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Scheduled for " + v.getVisitDate() + ".");
                    return back;
                }
                // 🕒 Same booked-time-window rule as the QR scan
                String windowError = timeWindowBlocker(v.getTimeWindow(), now);
                if (windowError != null) {
                    redirectAttributes.addFlashAttribute("error", windowError);
                    return back;
                }
                if (v.getVisitTime() != null) {
                    LocalTime allowedStart = v.getVisitTime().minusMinutes(30);
                    LocalTime allowedEnd = v.getVisitTime().plusHours(2);
                    if (now.isBefore(allowedStart) || now.isAfter(allowedEnd)) {
                        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US);
                        String formattedTime = v.getVisitTime().format(formatter);
                        redirectAttributes.addFlashAttribute("error", "ACCESS DENIED: Outside of approved visit window. Scheduled time is " + formattedTime + ".");
                        return back; 
                    }
                }

                // 🚶 A free walk-in late for its check-in deadline (security fix L9)
                String late = lateWalkInBlocker(v, LocalDateTime.now(clock()));
                if (late != null) {
                    redirectAttributes.addFlashAttribute("error", late);
                    return back;
                }
                // 🚶 A walk-in whose name resembles a ban: the guard compares the ID with the ban's photo first
                if (banCheckMissing(v, banChecked)) {
                    redirectAttributes.addFlashAttribute("error", BAN_CHECK_REQUIRED);
                    return back;
                }
                // 🪪 Free / Discounted visitors: only with the IDs tick (the header's quick box has none, so it points
                // the guard to Process or the scanner)
                if (eligibilityCheckMissing(v, idsChecked)) {
                    redirectAttributes.addFlashAttribute("error", IDS_CHECK_MANUAL);
                    return back;
                }
                if (!claim(v, "CHECKED-IN")) {
                    redirectAttributes.addFlashAttribute("error", ALREADY_CHECKED_IN);
                    return back;
                }
                recordBanCheck(v);
                recordEligibilityCheck(v);
                v.setStatus("CHECKED-IN");
                v.setCheckedInBy(signedInStaffName() + " (Manual)");
                v.setCheckInTime(LocalDateTime.now(clock()));
                repo.save(v);
                String to = v.getEmail(), name = v.getName();
                com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "check-in email for visitor #" + v.getId(),
                        () -> emailService.sendCheckInGuidelines(to, name), null);
                String groupAddon = (v.getGroupSize() > 1) ? " (Group of " + v.getGroupSize() + ")" : "";
                redirectAttributes.addFlashAttribute("success", "🟢 Checked-In via Manual Override: " + v.getName() + groupAddon);
            }
        } else {
            redirectAttributes.addFlashAttribute("error", "Reference ID not found in database.");
        }
        return back;
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

    // 🚶 Security fix L9: a FREE walk-in must check in within the walk-in minutes (checkInDueAt); the 15-minute sweep
    // releases it, and until then the gate refuses it too, so the setting is the real limit. A paid walk-in's deadline is
    // its window's end, which the window check already enforces.
    static String lateWalkInBlocker(Visitor v, LocalDateTime now) {
        if (!v.walkIn() || v.paymentRequired() || v.getCheckInDueAt() == null || !now.isAfter(v.getCheckInDueAt())) return null;
        return "ACCESS DENIED: This free walk-in was not checked in by its deadline ("
                + v.getCheckInDueAt().toLocalTime().format(GATE_TIME) + "), so its seats were released. The visitor must book again.";
    }

    // Why this visitor cannot be checked in right now, or null when they can (APPROVED and scheduled for today).
    // Shared by the scan (verify) and the confirm step, so a stale card can never admit someone the scan would refuse.
    static final String PAYMENT_NOT_VERIFIED = "ACCESS DENIED: Payment not verified.";
    static final String BAN_CHECK_REQUIRED = "Tick \"ID checked against the ban\" before checking in this walk-in.";

    // 🚶 True when this walk-in's name resembles a ban and the guard has not ticked "ID checked against the ban"
    static boolean banCheckMissing(Visitor v, String banChecked) {
        return v.walkIn() && v.banCheckPending() && !"true".equals(banChecked);
    }

    // Records which guard compared the ID with the ban's photo, and when (only for a flagged walk-in)
    private void recordBanCheck(Visitor v) {
        if (v.walkIn() && v.banCheckPending()) {
            v.setBanCheckedBy(signedInStaffName());
            v.setBanCheckedAt(LocalDateTime.now(clock()));
        }
    }

    // 🪪 FREE / DISCOUNTED IDs: a group with Free or Discounted visitors is checked in (walk-in Process panel, QR Accept,
    // manual check-in) only after the guard ticks that they checked those visitors' IDs. Recorded once, like the ban check.
    static final String IDS_CHECK_REQUIRED = "Tick \"Checked the IDs of the Free and Discounted visitors\" before checking in this group.";
    static final String IDS_CHECK_MANUAL = "This group has Free or Discounted visitors: check them in with Process (walk-ins) or the scanner (advance bookings), where you confirm their IDs.";

    static boolean eligibilityCheckMissing(Visitor v, String idsChecked) {
        return v.eligibilityCheckNeeded() && !"true".equals(idsChecked);
    }

    private void recordEligibilityCheck(Visitor v) {
        if (v.eligibilityCheckNeeded()) {
            v.setEligibilityCheckedBy(signedInStaffName());
            v.setEligibilityCheckedAt(LocalDateTime.now(clock()));
        }
    }

    // ✅ Checks in an APPROVED, cleared visitor (the checks are the caller's): claims the pass so two gates can't both admit
    // it, records the ticks, saves, and emails the check-in guidelines in the background. Null on success, else why not.
    private String admit(Visitor v, String checkedInBy) {
        if (!claim(v, "CHECKED-IN")) return ALREADY_CHECKED_IN;
        recordBanCheck(v);
        recordEligibilityCheck(v);
        v.setStatus("CHECKED-IN");
        v.setCheckedInBy(checkedInBy);
        v.setCheckInTime(LocalDateTime.now(clock()));
        repo.save(v);
        String to = v.getEmail(), name = v.getName();
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "check-in email for visitor #" + v.getId(),
                () -> emailService.sendCheckInGuidelines(to, name), null);
        return null;
    }

    private static String groupAddon(Visitor v) {
        return v.getGroupSize() > 1 ? " (Group of " + v.getGroupSize() + ")" : "";
    }

    // 🚶 PROCESS WALK-IN (Guard Dashboard, Walk-ins Today): every tick that applies is required here, on the server.
    // Verify & Check In = mark the receipt PAID (when there is one to verify), record the ticks, then check the group in
    // through the usual gate rules. A refused check-in (e.g. the window hasn't opened) keeps the payment verified.
    // Who may: the payment rule (PaymentAdminController.canVerify): a Guard only for today's walk-ins, else 403.
    static final String PROCESS_WALK_IN_ONLY = "Process is for walk-in bookings only.";

    // ☑️ ONE BOX for the receipt match and the Free/Discounted IDs: "I've verified the ID(s) and payment" (verifiedChecked).
    // It stands for both confirmations, each still required here only when it applies and recorded as before (who, when);
    // the ban tick stays separate. The label is the same on the panel (WalkInPanelData "verify-text").
    static final String VERIFY_BOTH = "I've verified the ID(s) and payment";
    static final String VERIFY_PAYMENT = "I've verified the payment";
    static final String VERIFY_IDS = "I've verified the ID(s)";

    // The single box's label for this walk-in, or "" when neither the receipt nor any ID needs confirming
    public static String verifyTickLabel(Visitor v) {
        boolean payment = "proof".equals(WalkInPanelData.state(v));
        boolean ids = v.eligibilityCheckNeeded();
        return payment && ids ? VERIFY_BOTH : payment ? VERIFY_PAYMENT : ids ? VERIFY_IDS : "";
    }

    @PostMapping("/admin/walk-ins/{id}/process")
    public String processWalkIn(@PathVariable Long id,
                                @RequestParam(value = "verifiedChecked", required = false) String verifiedChecked,
                                @RequestParam(value = "banChecked", required = false) String banChecked,
                                @RequestParam(value = "returnTo", required = false) String returnTo,
                                RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.isOurs(returnTo) ? "redirect:" + returnTo : "redirect:" + PaymentAdminController.GUARD_RETURN;
        Outcome outcome = processWalkInOutcome(id, verifiedChecked, banChecked);
        redirectAttributes.addFlashAttribute(outcome.ok() ? "success" : "error", outcome.message());
        return back;
    }

    // 📷 The same Process action for the scanner's overlay (fetch with X-Requested-With: fetch): {status, message} instead
    // of a redirect, so the camera page never reloads. Same URL, so the same security rule and the same checks.
    @PostMapping(value = "/admin/walk-ins/{id}/process", headers = AJAX_HEADER)
    @ResponseBody
    public ResponseEntity<Map<String, String>> processWalkInAjax(@PathVariable Long id,
                                @RequestParam(value = "verifiedChecked", required = false) String verifiedChecked,
                                @RequestParam(value = "banChecked", required = false) String banChecked) {
        return ResponseEntity.ok(processWalkInOutcome(id, verifiedChecked, banChecked).json());
    }

    // The answer of a panel action: ok (green) or not (red, nothing more happened), and what to tell the guard
    record Outcome(boolean ok, String message) {
        Map<String, String> json() {
            Map<String, String> body = new HashMap<>();
            body.put("status", ok ? "success" : "error");
            body.put("message", message);
            return body;
        }
    }

    // The scanner overlay's requests carry this header (the dashboard's normal form posts don't)
    static final String AJAX_HEADER = "X-Requested-With=fetch";

    private Outcome processWalkInOutcome(Long id, String verifiedChecked, String banChecked) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Visitor v = repo.findById(id).orElse(null);
        if (v == null) {
            if (!com.paf.aerovisit.config.SecurityConfig.isDirector()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            return new Outcome(false, "Booking not found.");
        }
        if (!PaymentAdminController.canVerify(auth, v, LocalDate.now(clock()))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        String refusal = !v.walkIn() ? PROCESS_WALK_IN_ONLY
                : "CHECKED-IN".equals(v.getStatus()) ? v.getName() + " is already inside."
                : !"APPROVED".equals(v.getStatus()) ? "This walk-in can't be checked in (status: " + v.getStatus() + ")." : null;
        boolean verifyPayment = v.paymentRequired() && !Visitor.PAY_PAID.equals(v.getPaymentStatus());
        if (refusal == null && verifyPayment && !Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus())) {
            refusal = "Waiting for the receipt: nothing to verify yet"
                    + (v.getPaymentDueAt() != null ? " (the visitor has until " + v.getPaymentDueAt().format(GATE_TIME) + ")." : ".");
        }
        if (refusal != null) return new Outcome(false, refusal);

        // No tick, no action: every missing tick is named, and nothing is saved
        List<String> missing = new ArrayList<>();
        // ☑️ The one box covers the receipt match (when there is a receipt to verify) and the Free/Discounted IDs
        if ((verifyPayment || eligibilityCheckMissing(v, verifiedChecked)) && !"true".equals(verifiedChecked)) {
            missing.add("\"" + verifyTickLabel(v) + "\"");
        }
        if (v.banCheckPending() && !"true".equals(banChecked)) missing.add("\"ID checked against the ban\"");
        if (!missing.isEmpty()) return new Outcome(false, "Nothing was saved. Please tick " + String.join(", ", missing) + ".");

        LocalDateTime now = LocalDateTime.now(clock());
        if (verifyPayment) PaymentWorkflow.recordPaid(v, auth == null ? "unknown" : auth.getName(), now, "");
        recordEligibilityCheck(v);
        recordBanCheck(v);
        repo.save(v);

        String paid = verifyPayment ? "Payment verified for billing " + v.getBillingNumber() + " (" + v.amountDueText() + ")" : null;
        String blocker = checkInBlocker(v);
        if (blocker == null) blocker = admit(v, signedInStaffName());
        if (blocker != null) {
            return new Outcome(false, (paid != null ? paid + ", but check-in was refused: " : "Not checked in: ") + blocker);
        }
        return new Outcome(true, "✅ " + (paid != null ? paid + " and checked in: " : "Checked in: ") + v.getName() + groupAddon(v));
    }

    private String checkInBlocker(Visitor v) {
        if ("CHECKED-IN".equals(v.getStatus())) return "Visitor is already CHECKED-IN.";
        // 🔒 Pending / unverified / denied bookings cannot enter, even with a valid token
        if (!"APPROVED".equals(v.getStatus())) return "ACCESS DENIED: Booking is not approved (status: " + v.getStatus() + ").";
        // 💳 A booking with an admission total must be verified as paid by the Director
        if (!v.paymentCleared()) return PAYMENT_NOT_VERIFIED;
        // 🚫 Backstop for a pass whose holder was banned after approval (the ban also revokes open bookings)
        if (isBanned(v)) return BANNED_ENTRY;
        // 🚶 A free walk-in late for its check-in deadline (security fix L9)
        String late = lateWalkInBlocker(v, LocalDateTime.now(clock()));
        if (late != null) return late;
        // 🔒 The QR is only valid on the scheduled date
        if (!LocalDate.now(clock()).equals(v.getVisitDate())) return "ACCESS DENIED: Scheduled for " + v.getVisitDate() + ".";
        // 🕒 ...and within the booked time window (opens 15 minutes early)
        return timeWindowBlocker(v.getTimeWindow(), LocalTime.now(clock()));
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

    // The time window for the approval email: only a real slot ("Morning (08:00 AM - 12:00 PM)"), never a label the
    // gate can't read (e.g. an older booking's "Master Booking Bypass"), so the email states exactly what the gate enforces
    static String bookedWindow(Visitor v) {
        return parseTimeWindow(v.getTimeWindow()) == null ? null : v.getTimeWindow();
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
                                                              @RequestParam(value = "banChecked", required = false) String banChecked,
                                                              @RequestParam(value = "idsChecked", required = false) String idsChecked) {
        Map<String, String> response = new HashMap<>();
        Visitor v = repo.findById(visitorId).orElse(null);
        // 🚶 A walk-in goes in only through its Process panel (receipt match, IDs and ban ticks), never through Accept
        String error = (v == null) ? "Visitor ID not found in database." : v.walkIn() ? WALK_IN_USE_PROCESS : checkInBlocker(v);
        if (error == null && banCheckMissing(v, banChecked)) error = BAN_CHECK_REQUIRED;
        if (error == null && eligibilityCheckMissing(v, idsChecked)) error = IDS_CHECK_REQUIRED;
        if (error != null) {
            response.put("status", "error");
            response.put("message", error);
            return ResponseEntity.ok(response);
        }

        // 🚦 Another gate may have pressed Accept on this pass at the same moment: only the first one checks in and emails
        if (!claim(v, "CHECKED-IN")) {
            response.put("status", "error");
            response.put("message", ALREADY_CHECKED_IN);
            return ResponseEntity.ok(response);
        }

        // --- SUCCESSFUL CHECK-IN PROTOCOL ---
        recordBanCheck(v);
        recordEligibilityCheck(v);
        v.setStatus("CHECKED-IN");
        v.setCheckedInBy(signedInStaffName());
        v.setCheckInTime(LocalDateTime.now(clock()));
        repo.save(v);
        sendInBackground(() -> emailService.sendCheckInGuidelines(v.getEmail(), v.getName()));

        String groupAddon = (v.getGroupSize() > 1) ? " (Group of " + v.getGroupSize() + ")" : "";
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
                                         @RequestParam(value = "returnTo", required = false) String returnTo,
                                         RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // ↩️ the page the officer acted from, or /admin
        Visitor v = repo.findById(visitorId).orElse(null);
        String error = denyEntry(v, reason, notes);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
        } else {
            redirectAttributes.addFlashAttribute("success", "⛔ ENTRY DENIED: " + v.getName() + ". Check-in voided and logged.");
        }
        return back;
    }

    // Turns a same-day APPROVED pass (scanner, before check-in) or CHECKED-IN visit (dashboard reversal) into DENIED_ENTRY,
    // recording who denied it, when and why; null on success, else the error. Any checkedInBy / checkInTime is kept,
    // so a reversed check-in shows both the check-in and its reversal.
    private String denyEntry(Visitor v, String reason, String notes) {
        if (v == null) return "Visitor ID not found in database.";
        if (!"APPROVED".equals(v.getStatus()) && !"CHECKED-IN".equals(v.getStatus())) {
            return "Only an approved or checked-in visitor can be denied entry (status: " + v.getStatus() + ").";
        }
        if (!LocalDate.now(clock()).equals(v.getVisitDate())) return "Entry can only be denied on the day of the visit.";
        if (reason == null || reason.isBlank()) return "Select a reason for denying entry.";
        String fullReason = reason.trim();
        if (notes != null && !notes.isBlank()) fullReason += " | Notes: " + notes.trim();
        if (fullReason.length() > 500) return "Reason is too long (max 500 characters).";

        v.setStatus("DENIED_ENTRY");
        v.setEntryDeniedBy(signedInStaffName());
        v.setEntryDeniedAt(LocalDateTime.now(clock()));
        v.setEntryDenialReason(fullReason);
        v.setQrCodeUrl(null);
        repo.save(v);
        if (notificationService != null) notificationService.gateDenied(v, reason.trim()); // never throws
        return null;
    }

    @PostMapping("/admin/approve/{id}")
    public String approveVisitor(@PathVariable Long id,
                                 @RequestParam(value = "returnTo", required = false) String returnTo,
                                 RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo); // the officer's Pending view, or /admin as before
        if (id == null) return back;
        Visitor v = repo.findById(id).orElse(null);
        if (v != null && "DENIED_ENTRY".equals(v.getStatus())) {
            // 🛑 Re-approving would issue a fresh QR and undo the gate's decision; the visitor must book again
            redirectAttributes.addFlashAttribute("error", "Approval refused: this visitor was denied entry at the gate. The denial is final for this booking.");
            return back;
        }
        if (v != null && v.awaitsReceipt()) {
            redirectAttributes.addFlashAttribute("error", AWAITING_RECEIPT_APPROVAL);
            return back;
        }
        if (v != null && !v.awaitsApproval()) {
            // 🔒 STATE MACHINE: only a pending request can be approved, so a checked-in, checked-out, rejected or
            // cancelled booking can never be moved back to APPROVED (which would re-activate its QR code)
            redirectAttributes.addFlashAttribute("error", "Invalid state transition: a booking with status "
                    + (v.getStatus() != null ? v.getStatus() : "UNKNOWN") + " cannot be approved. Only pending requests can be approved.");
            return back;
        }
        if (v != null && isBanned(v)) {
            redirectAttributes.addFlashAttribute("error", BANNED_APPROVAL);
            return back;
        }
        // ⌛ A visit whose time window is over can't be approved any more (security fix L1)
        if (v != null && windowEnded(v)) {
            redirectAttributes.addFlashAttribute("error", WINDOW_ENDED_APPROVAL);
            return back;
        }
        // 🔒 The booking as read now: saved only if still exactly this (e.g. not moved to a new date meanwhile; M4)
        com.paf.aerovisit.service.VisitorRowGuard.Snapshot asRead = v == null ? null : com.paf.aerovisit.service.VisitorRowGuard.Snapshot.of(v);
        if (v != null) {
            // 👻 GHOST SLOTS: every booking needs a real slot for its date and time window (never created, deleted,
            // or a made-up window from a hand-made request)
            if (slotCapacity(v) <= 0) {
                redirectAttributes.addFlashAttribute("error", INVALID_SLOT);
                return back;
            }
            // 🔒 Capacity check: a pending booking already holds its seats (pay first), and its own seats are left out of
            // the count, so this only fails when the slot was overbooked (e.g. a Force Approve)
            if (!hasSeatsForApproval(v)) {
                redirectAttributes.addFlashAttribute("error", "Approval failed: Not enough seats remaining in this time slot.");
                return back;
            }
            String receiptError = receiptBlocker(v);
            if (receiptError != null) {
                redirectAttributes.addFlashAttribute("error", receiptError);
                return back;
            }
            // 💳 PAYMENT: approving a booking with a submitted receipt also marks it paid, then the QR goes out; a total
            // of 0 gets its QR now, as before
            LocalDateTime now = LocalDateTime.now(clock());
            markReceiptPaid(v, now);
            PaymentWorkflow.Next next = paymentStep(v, now);
            if (next == null) {
                redirectAttributes.addFlashAttribute("error", PAYMENT_UNAVAILABLE);
                return back;
            }
            v.setStatus("APPROVED");
            boolean saved = false; // an email failure after this point leaves a real approval behind
            try {
                // 🔥 ANTI-FORGERY FIX: the QR carries the secure UUID token, never plain text
                String qrFileName = next == PaymentWorkflow.Next.ISSUE_QR ? issuePass(v, uploadMirror) : null;

                try {
                    saveWithinCapacity(v, asRead);
                } catch (com.paf.aerovisit.service.VisitorRowGuard.StaleBookingException changed) {
                    if (qrFileName != null) {
                        Files.deleteIfExists(Paths.get("uploads").resolve(qrFileName));
                        if (uploadMirror != null) uploadMirror.deleteUpload(qrFileName);
                    }
                    redirectAttributes.addFlashAttribute("error", com.paf.aerovisit.service.VisitorRowGuard.CHANGED_MESSAGE);
                    return back;
                } catch (SeatReservationService.CapacityFullException | org.springframework.dao.PessimisticLockingFailureException full) {
                    // 🔒 Another approval took the last seats after the check above: nothing was saved, the request stays pending
                    if (qrFileName != null) {
                        Files.deleteIfExists(Paths.get("uploads").resolve(qrFileName));
                        if (uploadMirror != null) uploadMirror.deleteUpload(qrFileName);
                    }
                    redirectAttributes.addFlashAttribute("error", full instanceof SeatReservationService.CapacityFullException
                            ? "Approval failed: Not enough seats remaining in this time slot."
                            : "Approval failed: this time slot is being updated by another approval right now. Please try again.");
                    return back;
                }
                saved = true;
                notifySlotFilling(v);
                redirectAttributes.addFlashAttribute("success", afterApproval(v, next, qrFileName, APPROVED_MESSAGE, now));
            } catch (Exception e) {
                System.err.println("❌ Approval QR failed for visitor #" + v.getId() + ": " + e);
                e.printStackTrace();
                if (saved && notificationService != null) notificationService.approvalEmailFailed(v);
                redirectAttributes.addFlashAttribute("error", "Approved, but the QR code could not be created: " + e.getMessage());
            }
        }
        return back;
    }

    // ⚠️ DIRECTOR OVERRIDE: approves a pending request even when its time slot is already full.
    // Same QR + email steps as approveVisitor above, without the capacity check. SecurityConfig limits this path to the Director.
    @PostMapping("/admin/approve-override/{id}")
    public String forceApproveVisitor(@PathVariable Long id, java.security.Principal principal,
                                      @RequestParam(value = "returnTo", required = false) String returnTo,
                                      RedirectAttributes redirectAttributes) {
        String back = AdminReturnTo.redirect(returnTo);
        Visitor v = repo.findById(id).orElse(null);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Visitor ID not found in database.");
            return back;
        }
        if (!v.awaitsApproval()) {
            // 🛑 Also keeps denied, cancelled and rejected bookings from being revived through the override. Any request
            // awaiting approval qualifies, including an older VERIFIED one (security fix L7), like normal Approve.
            redirectAttributes.addFlashAttribute("error", "Force Approve only applies to a pending request (status: " + v.getStatus() + ").");
            return back;
        }
        // The override skips the seat count only: a banned visitor or a slot that does not exist still blocks it
        if (isBanned(v)) {
            redirectAttributes.addFlashAttribute("error", BANNED_APPROVAL);
            return back;
        }
        if (slotCapacity(v) <= 0) {
            redirectAttributes.addFlashAttribute("error", INVALID_SLOT);
            return back;
        }
        if (windowEnded(v)) { // ⌛ L1
            redirectAttributes.addFlashAttribute("error", WINDOW_ENDED_APPROVAL);
            return back;
        }
        com.paf.aerovisit.service.VisitorRowGuard.Snapshot asRead = com.paf.aerovisit.service.VisitorRowGuard.Snapshot.of(v); // 🔒 M4

        // 💳 The override skips the capacity check only: the payment steps still apply
        String receiptError = receiptBlocker(v);
        if (receiptError != null) {
            redirectAttributes.addFlashAttribute("error", receiptError);
            return back;
        }
        LocalDateTime now = LocalDateTime.now(clock());
        markReceiptPaid(v, now);
        PaymentWorkflow.Next next = paymentStep(v, now);
        if (next == null) {
            redirectAttributes.addFlashAttribute("error", PAYMENT_UNAVAILABLE);
            return back;
        }
        String qrFileName = null;
        try {
            if (next == PaymentWorkflow.Next.ISSUE_QR) qrFileName = issuePass(v, uploadMirror);
            v.setStatus("APPROVED");
            boolean saved = true;
            if (rowGuard == null) repo.save(v); // web-layer test slices: as before
            else saved = rowGuard.saveIfUnchanged(v, asRead, () -> {});
            if (!saved) {
                if (qrFileName != null) {
                    Files.deleteIfExists(Paths.get("uploads").resolve(qrFileName));
                    if (uploadMirror != null) uploadMirror.deleteUpload(qrFileName);
                }
                redirectAttributes.addFlashAttribute("error", com.paf.aerovisit.service.VisitorRowGuard.CHANGED_MESSAGE);
                return back;
            }
            notifySlotFilling(v);
        } catch (Exception e) {
            System.err.println("❌ Force approval failed for visitor #" + v.getId() + ": " + e);
            redirectAttributes.addFlashAttribute("error", "Force approval failed, the booking is still pending: " + e.getMessage());
            return back;
        }
        System.out.println("⚠️ CAPACITY OVERRIDE: visitor #" + v.getId() + " force-approved by " + (principal != null ? principal.getName() : "unknown"));

        redirectAttributes.addFlashAttribute("success", afterApproval(v, next, qrFileName,
                "⚠️ Force-approved over capacity: " + v.getName() + ". The approval email is on its way.", now));
        return back;
    }

    static final String PAYMENT_UNAVAILABLE = "Approval failed: the payment service is unavailable, so no billing number could be issued. Please try again.";
    static final String PAYMENT_REQUESTED_MESSAGE = "Visitor approved. Their seats are held and the payment request email (billing number and amount) is on its way.";
    static final String AWAITING_VERIFICATION_MESSAGE = "Visitor approved. Their payment receipt is waiting in Pending Approval; the QR code is sent once you mark it as paid.";
    static final String AWAITING_RECEIPT_APPROVAL = "Approval refused: this booking is still waiting for the visitor's payment receipt. It appears in Pending Approval once the receipt is uploaded.";
    static final String RECEIPT_REJECTED_APPROVAL = "Approval refused: this booking's payment receipt was rejected. It returns to Pending Approval when the visitor uploads a new one (or use Mark as Paid with a note if you verified the payment another way).";

    // 💳 Why a pending booking can't be approved yet because of its payment, or null. A rejected receipt waits for the
    // visitor's new upload.
    static String receiptBlocker(Visitor v) {
        if (v.paymentRequired() && Visitor.PAY_REJECTED.equals(v.getPaymentStatus())) return RECEIPT_REJECTED_APPROVAL;
        // ⌛ Waiting for the receipt (shown in Pending Approval for review only): never approvable, Force Approve included
        if (v.waitingForReceipt()) return AWAITING_RECEIPT_APPROVAL;
        return null;
    }

    // ✅ Pay first: approving a booking whose receipt is in also marks the payment as verified (by whom, when), so the QR
    // goes out with the approval. Nothing else changes here; the caller saves.
    private static void markReceiptPaid(Visitor v, LocalDateTime now) {
        if (!v.paymentRequired() || !Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus())) return;
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        PaymentWorkflow.recordPaid(v, auth == null ? "unknown" : auth.getName(), now, "Verified at approval");
    }

    // 💳 What approval does next (PaymentWorkflow.onApproval), or null when payment is needed but the payment service is
    // missing (test slices only), so an unpaid booking never gets a QR by accident
    private PaymentWorkflow.Next paymentStep(Visitor v, LocalDateTime now) {
        if (paymentWorkflow == null) return v.paymentRequired() ? null : PaymentWorkflow.Next.ISSUE_QR;
        try {
            return paymentWorkflow.onApproval(v, now);
        } catch (RuntimeException e) {
            System.err.println("❌ Billing number could not be issued for visitor #" + v.getId() + ": " + e.getMessage());
            return null;
        }
    }

    // After a saved approval: the QR email, or the payment request email; returns the Director's success message
    private String afterApproval(Visitor v, PaymentWorkflow.Next next, String qrFileName, String qrMessage, LocalDateTime now) {
        if (next == PaymentWorkflow.Next.ISSUE_QR) {
            sendApprovalEmail(mailOutbox, emailService, notificationService, v, qrFileName);
            return qrMessage;
        }
        if (next == PaymentWorkflow.Next.AWAIT_VERIFICATION) return AWAITING_VERIFICATION_MESSAGE;
        EmailService.PaymentNotice notice = EmailService.PaymentNotice.of(v);
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "payment request email for visitor #" + v.getId(),
                () -> emailService.sendPaymentRequest(notice),
                () -> { if (notificationService != null) notificationService.approvalEmailFailed(v); });
        String message = PAYMENT_REQUESTED_MESSAGE + " Billing number " + v.getBillingNumber() + ", " + v.amountDueText()
                + ", due " + PaymentWorkflow.deadlineText(v.getPaymentDueAt()) + ".";
        // ⏱️ A same-day booking may leave very little time to pay
        long minutes = v.getPaymentDueAt() == null ? Long.MAX_VALUE : Math.max(0, Duration.between(now, v.getPaymentDueAt()).toMinutes());
        if (minutes < 120) message += " Note: the visitor has only " + minutes + " minutes to pay.";
        return message;
    }

    // 🎫 Creates the booking's entry pass: the QR (its unguessable secure token) saved under uploads/ and copied to S3.
    // Shared by approval, Force Approve and "Mark as Paid" (PaymentAdminController). Returns the QR file name.
    static String issuePass(Visitor v, UploadMirror uploadMirror) throws Exception {
        Path uploadPath = Paths.get("uploads");
        if (!Files.exists(uploadPath)) { Files.createDirectories(uploadPath); }
        String qrContent = v.getSecureToken() != null ? v.getSecureToken() : java.util.UUID.randomUUID().toString();
        v.setSecureToken(qrContent);
        String qrFileName = "QR_" + v.getId() + ".png";
        QRCodeGenerator.generateQRCode(qrContent, "uploads/" + qrFileName);
        v.setQrCodeUrl(qrFileName);
        if (uploadMirror != null) uploadMirror.pushUpload(qrFileName);
        return qrFileName;
    }

    // 📮 The approval email (with the QR code) in the background; a failed send goes to the Director's bell, since the
    // page has already answered. The values are read now, so the email doesn't depend on the entity later.
    static void sendApprovalEmail(com.paf.aerovisit.service.MailOutbox mailOutbox, EmailService emailService,
                                  com.paf.aerovisit.service.NotificationService notificationService, Visitor v, String qrFileName) {
        String to = v.getEmail(), name = v.getName(), date = String.valueOf(v.getVisitDate()), ref = v.getReferenceId(), window = bookedWindow(v);
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "approval email for visitor #" + v.getId(),
                () -> emailService.sendApprovalEmailWithQR(to, name, date, qrFileName, ref, window),
                () -> { if (notificationService != null) notificationService.approvalEmailFailed(v); });
    }

    // True when the visitor's group still fits its date and time window, counting seat-holding bookings
    // (Visitor.SEAT_HOLDING_STATUSES) and leaving out the visitor's own booking
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

    // 🔒 RACE-SAFE SAVE: every approval is saved under a row lock on its slot with the seat count re-read inside the
    // lock (SeatReservationService), so two approvals can't both take the last seats. Web-layer test slices without
    // SeatReservationService save directly, as before.
    private void saveWithinCapacity(Visitor v, com.paf.aerovisit.service.VisitorRowGuard.Snapshot asRead) {
        if (seatReservation == null) repo.save(v);
        else {
            v.expectRow(asRead); // 🔒 checked under the seat lock (M4)
            seatReservation.saveIfSeatsRemain(v);
        }
    }

    // ⌛ Security fix L1: approving after the visit's window ended would email a QR (or a payment request) for a visit the
    // gate already refuses. A paid request becomes Reschedule Needed overnight (expireUnreviewedBookings) instead.
    static final String WINDOW_ENDED_APPROVAL = "Approval refused: this visit's time window has already ended. A paid request "
            + "becomes Reschedule Needed overnight so the visitor can pick a new date; an unpaid one expires as not reviewed.";

    private boolean windowEnded(Visitor v) {
        if (v.getVisitDate() == null) return false;
        LocalDate today = LocalDate.now(clock());
        if (v.getVisitDate().isBefore(today)) return true;
        if (v.getVisitDate().isAfter(today)) return false;
        LocalTime[] window = parseTimeWindow(v.getTimeWindow());
        return window != null && LocalTime.now(clock()).isAfter(window[1].withSecond(59).withNano(999_999_999));
    }

    // 🔔 After an approval: tells the bell when the visit's time slot is almost full (or full). Never fails the approval.
    private void notifySlotFilling(Visitor v) {
        if (notificationService == null || v.getVisitDate() == null) return;
        try {
            int capacity = slotCapacity(v);
            if (capacity <= 0) return;
            int taken = 0;
            for (Visitor o : repo.findByVisitDate(v.getVisitDate())) {
                if (o.holdsSeat() && java.util.Objects.equals(o.getTimeWindow(), v.getTimeWindow())) taken += Math.max(1, o.getGroupSize());
            }
            notificationService.slotFilling(v.getVisitDate(), v.getTimeWindow(), capacity - taken, capacity);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Slot check skipped for visitor #" + v.getId() + ": " + e.getMessage());
        }
    }

    // Seats configured for the visit's date and time window; 0 when no such slot exists
    private int slotCapacity(Visitor v) {
        if (v.getVisitDate() == null || v.getTimeWindow() == null) return 0;
        return bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), v.getTimeWindow()).stream()
                .mapToInt(BookingSlot::getMaxCapacity)
                .sum();
    }

    // True when this booking matches an active ban by its email or phone number (IdentityBans).
    // A name that only resembles a banned name is not blocked here: it was flagged for staff at booking time.
    private boolean isBanned(Visitor v) {
        if (faceBanRepo == null) return false;
        return com.paf.aerovisit.service.IdentityBans.activeBan(v, faceBanRepo.findAll(), LocalDate.now()) != null;
    }

    @PostMapping("/admin/reject/{id}")
    public String rejectVisitor(@PathVariable Long id,
                                @RequestParam(value = "confirmPaid", required = false) String confirmPaid,
                                RedirectAttributes redirectAttributes) {
        if (id == null) return "redirect:/admin";
        Visitor v = repo.findById(id).orElse(null);
        // 💳 A booking with a receipt on file (paid, in review, or rejected since) is only rejected after the
        // non-refundable warning is confirmed
        if (v != null && v.receiptOnFile() && !"true".equals(confirmPaid)) {
            redirectAttributes.addFlashAttribute("error", Visitor.PAID_NON_REFUNDABLE + " Tick the confirmation to reject it.");
            return "redirect:/admin";
        }
        if (v != null && "DENIED_ENTRY".equals(v.getStatus())) {
            // 🛑 A gate denial is a final audit record and must not be overwritten
            redirectAttributes.addFlashAttribute("error", "This visitor was denied entry at the gate; that record cannot be changed.");
            return "redirect:/admin";
        }
        if (v != null && v.visitUnderwayOrOver()) {
            redirectAttributes.addFlashAttribute("error", Visitor.CANNOT_REJECT_MESSAGE);
            return "redirect:/admin";
        }
        if (v != null && !rejectable(v)) { // 🛑 security fix L3
            redirectAttributes.addFlashAttribute("error", alreadyClosedMessage(v));
            return "redirect:/admin";
        }
        if (v != null) {
            v.setStatus("REJECTED");
            v.setQrCodeUrl(null);
            repo.save(v);
        }
        return "redirect:/admin";
    }

    // 🛑 Security fix L3: only an open booking can be rejected: one awaiting its receipt or approval, approved, or waiting
    // for a new date. A cancelled, expired or already rejected booking keeps its outcome (and the visitor gets no
    // misleading "not approved" email). Shared with ManagementController's Reject (the form with a reason).
    static final java.util.Set<String> REJECTABLE_STATUSES = java.util.Set.of(Visitor.STATUS_AWAITING_RECEIPT, "PENDING",
            "PENDING_MANUAL_REVIEW", "VERIFIED", "APPROVED", Visitor.STATUS_RESCHEDULE_REQUIRED);

    static boolean rejectable(Visitor v) {
        return v.getStatus() != null && REJECTABLE_STATUSES.contains(v.getStatus());
    }

    static String alreadyClosedMessage(Visitor v) {
        return "This booking is already closed (status: " + v.getStatus() + "); there is nothing to reject.";
    }

    // 👤 Who did it: the signed-in staff account (e.g. "guard"), recorded on check-ins, ban and ID checks and denials.
    // "System Default" only when nobody is signed in.
    private String signedInStaffName() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return (auth != null) ? auth.getName() : "System Default";
    }
}
