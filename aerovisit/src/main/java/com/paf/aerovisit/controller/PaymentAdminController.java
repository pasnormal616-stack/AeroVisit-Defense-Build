package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.MailOutbox;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.UploadMirror;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 💳 The staff side of payment: view a receipt, "Mark as Paid" (then the QR is emailed exactly as at approval),
 * "Reject Receipt" with a reason, and the payment and walk-in settings.
 *
 * Who may do what is decided here, in canViewReceipt() / canVerify() (ONE rule): the Director, any booking; a Guard,
 * only a WALK-IN booking dated today (the guard checks it at the gate). SecurityConfig lets Guards reach only
 * /admin/payments/{id}/receipt, /verify and /reject; everything else here (settings) is DIRECTOR only, and anything a
 * Guard may not do gets 403 from the server, not only hidden buttons.
 *
 * Receipts are never served from /uploads (denied to everyone): only through receipt().
 */
@Controller
public class PaymentAdminController {

    // 💳 Pay first: receipts are reviewed in the one Pending Approval tab (the separate payment tab was removed)
    static final String RECEIPT_RETURN = "/admin?filter=pending";
    static final String GUARD_RETURN = "/guard/dashboard?tab=walkins";

    private final VisitorRepository repo;
    private final EmailService emailService;

    @Autowired(required = false) private PaymentWorkflow paymentWorkflow;
    @Autowired(required = false) private ReceiptStorage receiptStorage;
    @Autowired(required = false) private UploadMirror uploadMirror;
    @Autowired(required = false) private MailOutbox mailOutbox;
    @Autowired(required = false) private NotificationService notificationService;
    @Autowired(required = false) private com.paf.aerovisit.service.WalkInService walkInService;

    public PaymentAdminController(VisitorRepository repo, EmailService emailService) {
        this.repo = repo;
        this.emailService = emailService;
    }

    // 🔐 Who may see a booking's receipt: the Director, any booking; a Guard, any booking dated TODAY (walk-in or advance:
    // the gate scanner shows the receipt next to the ID photo). Everyone else gets nothing.
    static boolean canViewReceipt(Authentication auth, Visitor v, LocalDate today) {
        if (auth == null || v == null) return false;
        if (hasRole(auth, "ROLE_DIRECTOR")) return true;
        return hasRole(auth, "ROLE_GUARD") && today.equals(v.getVisitDate());
    }

    // 🔐 Who may Mark as Paid or Reject a receipt: the Director, any booking; a Guard, only a WALK-IN booking dated today
    // (an advance booking's payment stays the Director's, even though the guard can now see its receipt)
    static boolean canVerify(Authentication auth, Visitor v, LocalDate today) {
        if (auth == null || v == null) return false;
        if (hasRole(auth, "ROLE_DIRECTOR")) return true;
        return hasRole(auth, "ROLE_GUARD") && v.walkIn() && today.equals(v.getVisitDate());
    }

    // ⏱️ "Now" (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    private static boolean hasRole(Authentication auth, String role) {
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    private static boolean isDirector(Authentication auth) {
        return auth != null && hasRole(auth, "ROLE_DIRECTOR");
    }

    @GetMapping("/admin/payments/{id}/receipt")
    public ResponseEntity<?> receipt(@PathVariable Long id) {
        Visitor v = repo.findById(id).orElse(null);
        if (!canViewReceipt(SecurityContextHolder.getContext().getAuthentication(), v, now().toLocalDate())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Path file = receiptStorage == null || v.getReceiptImageUrl() == null ? null : receiptStorage.find(v.getReceiptImageUrl());
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ReceiptStorage.contentType(v.getReceiptImageUrl())))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(new FileSystemResource(file));
    }

    // ✅ Mark as Paid: from a submitted receipt, or (with a required note, e.g. "verified in Landbank report") from a
    // booking still awaiting payment or whose receipt was rejected. An approved booking then gets its QR by email.
    @PostMapping("/admin/payments/{id}/verify")
    public String markPaid(@PathVariable Long id,
                           @RequestParam(value = "note", required = false) String note,
                           @RequestParam(value = "matchConfirmed", required = false) String matchConfirmed,
                           @RequestParam(value = "returnTo", required = false) String returnTo,
                           RedirectAttributes redirectAttributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String back = backTo(auth, returnTo);
        Visitor v = repo.findById(id).orElse(null);
        forbidUnlessAllowed(auth, v);
        // 🚶 A guard verifies a walk-in only from its uploaded receipt, after ticking "Billing number and amount match the
        // receipt" (also comparing it with the LANDBANK confirmation on the visitor's phone); the note-only path is the Director's
        if (!isDirector(auth) && (!"true".equals(matchConfirmed) || !Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String error = verifyBlocker(v);
        String cleanNote = note == null ? "" : note.trim();
        if (error == null && !Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus()) && cleanNote.isEmpty()) {
            error = "No receipt was uploaded for this booking: add a note saying how the payment was verified (e.g. \"verified in Landbank report\").";
        }
        if (error == null && cleanNote.length() > 300) error = "The note is too long (max 300 characters).";
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return back;
        }

        PaymentWorkflow.recordPaid(v, currentUser(), now(), cleanNote);
        // 💳 Pay first: a booking still waiting for its receipt, verified another way (the Director's note), now waits for
        // approval in the Pending Approval tab
        if (v.awaitsReceipt()) v.setStatus("PENDING");
        String qrFileName = null;
        // 🚶 A walk-in is already at the gate: no QR and no approval email, the guard checks the group in
        if ("APPROVED".equals(v.getStatus()) && !v.walkIn()) {
            try {
                qrFileName = GuardOperationController.issuePass(v, uploadMirror);
            } catch (Exception e) {
                System.err.println("❌ QR for paid visitor #" + v.getId() + " failed: " + e);
                redirectAttributes.addFlashAttribute("error", "The payment was not recorded: the QR code could not be created. Please try again.");
                return back;
            }
        }
        repo.save(v);
        if (v.walkIn()) {
            redirectAttributes.addFlashAttribute("success", "Payment verified for walk-in billing " + v.getBillingNumber() + " ("
                    + v.amountDueText() + "). Check the ID, then check the group in.");
        } else if (qrFileName != null) {
            GuardOperationController.sendApprovalEmail(mailOutbox, emailService, notificationService, v, qrFileName);
            redirectAttributes.addFlashAttribute("success", "Payment verified for billing " + v.getBillingNumber() + " (" + v.amountDueText()
                    + "). The approval email with the QR code is on its way to " + v.getEmail() + ".");
        } else {
            redirectAttributes.addFlashAttribute("success", "Payment verified for billing " + v.getBillingNumber()
                    + ". The booking is in Pending Approval; the QR code is sent when it is approved.");
        }
        return back;
    }

    // ❌ Reject Receipt: the visitor is emailed the reason and at least 24 more hours to upload a new one
    @PostMapping("/admin/payments/{id}/reject")
    public String rejectReceipt(@PathVariable Long id,
                                @RequestParam(value = "reason", required = false) String reason,
                                @RequestParam(value = "returnTo", required = false) String returnTo,
                                RedirectAttributes redirectAttributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String back = backTo(auth, returnTo);
        GuardOperationController.Outcome outcome = rejectReceiptOutcome(auth, id, reason);
        redirectAttributes.addFlashAttribute(outcome.ok() ? "success" : "error", outcome.message());
        return back;
    }

    // 📷 The same Reject for the scanner's walk-in overlay (fetch with X-Requested-With: fetch): {status, message}
    @PostMapping(value = "/admin/payments/{id}/reject", headers = GuardOperationController.AJAX_HEADER)
    @ResponseBody
    public ResponseEntity<Map<String, String>> rejectReceiptAjax(@PathVariable Long id,
                                @RequestParam(value = "reason", required = false) String reason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(rejectReceiptOutcome(auth, id, reason).json());
    }

    private GuardOperationController.Outcome rejectReceiptOutcome(Authentication auth, Long id, String reason) {
        Visitor v = repo.findById(id).orElse(null);
        forbidUnlessAllowed(auth, v);
        String cleanReason = reason == null ? "" : reason.trim();
        String error = v == null ? "Booking not found." : !Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus())
                ? "Only a submitted receipt can be rejected (payment status: " + v.paymentStatusLabel() + ")." : null;
        if (error == null && (cleanReason.isEmpty() || cleanReason.length() > 500)) error = "Please give a reason (up to 500 characters).";
        if (error != null) return new GuardOperationController.Outcome(false, error);
        v.setPaymentStatus(Visitor.PAY_REJECTED);
        v.setPaymentRejectionReason(cleanReason);
        // 🚶 A walk-in gets the walk-in minutes again (never past its window's end); an advance booking at least 24 hours
        v.setPaymentDueAt(v.walkIn()
                ? com.paf.aerovisit.service.WalkInService.deadlineAfterRejection(v, now(), walkInMinutes())
                : PaymentWorkflow.deadlineAfterRejection(v, now()));
        repo.save(v);
        EmailService.PaymentNotice notice = EmailService.PaymentNotice.of(v);
        MailOutbox.send(mailOutbox, "receipt rejection email for visitor #" + v.getId(),
                () -> emailService.sendPaymentRejected(notice),
                () -> { if (notificationService != null) notificationService.rejectionEmailFailed(v); });
        return new GuardOperationController.Outcome(true, "Receipt rejected for billing " + v.getBillingNumber()
                + ". The visitor is being emailed the reason and may upload a new receipt until "
                + PaymentWorkflow.deadlineText(v.getPaymentDueAt()) + (v.walkIn() ? "." : "; the booking keeps its seats and"
                + " returns to Pending Approval when the new receipt arrives, or expires at that deadline."));
    }

    // ✉️ Resend payment link (Director, ⋮ menu of a row waiting for its receipt): the same email as at booking (billing
    // number, amount, deadline, payment link), at most once every RESEND_MINUTES per booking. The limit is a conditional
    // UPDATE, so two clicks or two tabs send one email.
    static final int RESEND_MINUTES = 10;
    static final String RESEND_TOO_SOON = "The payment link was sent less than " + RESEND_MINUTES
            + " minutes ago. Please wait before sending it again.";

    @PostMapping("/admin/payments/{id}/resend-link")
    public String resendPaymentLink(@PathVariable Long id,
                                    @RequestParam(value = "returnTo", required = false) String returnTo,
                                    RedirectAttributes redirectAttributes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!isDirector(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); // SecurityConfig already refuses a Guard
        String back = backTo(auth, returnTo);
        LocalDateTime now = now();
        Visitor v = repo.findById(id).orElse(null);
        String error = v == null ? "Booking not found."
                : !(v.waitingForReceipt() || v.approvedAwaitingPayment()) || v.getBillingNumber() == null
                        ? "This booking is not waiting for a payment receipt."
                : v.getPaymentDueAt() != null && !now.isBefore(v.getPaymentDueAt())
                        ? "The payment deadline has already passed; the booking is released at the next deadline check."
                : repo.claimPaymentLinkResend(id, now, now.minusMinutes(RESEND_MINUTES)) != 1 ? RESEND_TOO_SOON : null;
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return back;
        }
        EmailService.PaymentNotice notice = EmailService.PaymentNotice.of(v);
        MailOutbox.send(mailOutbox, "payment link resend for visitor #" + v.getId(),
                () -> emailService.sendPaymentRequest(notice),
                () -> { if (notificationService != null) notificationService.approvalEmailFailed(v); });
        redirectAttributes.addFlashAttribute("success", "Payment link sent again to " + v.getEmail() + " (billing "
                + v.getBillingNumber() + ", " + v.amountDueText() + ", due " + PaymentWorkflow.deadlineText(v.getPaymentDueAt()) + ").");
        return back;
    }

    // ⚙️ Payment settings: hours to pay after approval, and the Landbank payment page (empty = hide the button)
    @PostMapping("/admin/settings/payment")
    public String saveSettings(@RequestParam(value = "paymentWindowHours", required = false) String hours,
                               @RequestParam(value = "landbankUrl", required = false) String landbankUrl,
                               RedirectAttributes redirectAttributes) {
        int windowHours;
        try {
            windowHours = Integer.parseInt(hours == null ? "" : hours.trim());
        } catch (NumberFormatException e) {
            windowHours = -1;
        }
        String url = landbankUrl == null ? "" : landbankUrl.trim();
        String error = windowHours < 1 || windowHours > PaymentWorkflow.MAX_WINDOW_HOURS
                ? "The payment deadline must be a whole number of hours from 1 to " + PaymentWorkflow.MAX_WINDOW_HOURS + "."
                : PaymentWorkflow.landbankUrlError(url);
        if (error == null && paymentWorkflow == null) error = "Payment settings are unavailable right now. Please try again.";
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/settings#paymentSettings";
        }
        paymentWorkflow.saveSettings(windowHours, url);
        redirectAttributes.addFlashAttribute("success", "Payment settings saved: visitors have " + windowHours
                + " hour(s) to pay after approval" + (url.isEmpty() ? "; the LANDBANK button is hidden." : "."));
        return "redirect:/admin/settings#paymentSettings";
    }

    // 🚶 Walk-in settings: the largest walk-in group and the minutes a walk-in has to pay (or, when free, to check in)
    @PostMapping("/admin/settings/walk-in")
    public String saveWalkInSettings(@RequestParam(value = "walkInMaxPax", required = false) String maxPax,
                                     @RequestParam(value = "walkInPaymentMinutes", required = false) String minutes,
                                     RedirectAttributes redirectAttributes) {
        int pax = wholeNumber(maxPax), mins = wholeNumber(minutes);
        String error = com.paf.aerovisit.service.WalkInService.settingsError(pax, mins);
        if (error == null && walkInService == null) error = "Walk-in settings are unavailable right now. Please try again.";
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/settings#walkInSettings";
        }
        walkInService.saveSettings(pax, mins);
        redirectAttributes.addFlashAttribute("success", "Walk-in settings saved: at most " + pax + " persons per walk-in, "
                + mins + " minutes to pay.");
        return "redirect:/admin/settings#walkInSettings";
    }

    private static int wholeNumber(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int walkInMinutes() {
        return walkInService == null ? com.paf.aerovisit.model.PortalSettings.DEFAULT_WALK_IN_MINUTES : walkInService.minutes();
    }

    // 403 unless this user may verify this booking's payment (a missing booking falls through to "Booking not found.")
    private void forbidUnlessAllowed(Authentication auth, Visitor v) {
        if (v != null && !canVerify(auth, v, now().toLocalDate())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (v == null && !isDirector(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    // Back to where the officer acted: the given page, else the Director's payment tab or the Guard's Walk-ins tab
    private static String backTo(Authentication auth, String returnTo) {
        if (returnTo != null) return AdminReturnTo.redirect(returnTo);
        return "redirect:" + (isDirector(auth) ? RECEIPT_RETURN : GUARD_RETURN);
    }

    // Why this booking's payment can't be marked as paid, or null
    private static String verifyBlocker(Visitor v) {
        if (v == null) return "Booking not found.";
        if (!v.paymentRequired()) return "This booking has no admission total to pay.";
        String p = v.getPaymentStatus();
        if (Visitor.PAY_PAID.equals(p)) return "This payment was already verified.";
        if (!Visitor.PAY_PROOF_SUBMITTED.equals(p) && !Visitor.PAY_AWAITING.equals(p) && !Visitor.PAY_REJECTED.equals(p)) {
            return "This booking is not waiting for a payment.";
        }
        if (!"APPROVED".equals(v.getStatus()) && !v.awaitsApproval() && !v.awaitsReceipt()) {
            return "This booking is no longer active (status: " + v.getStatus() + "); its seats were released.";
        }
        return null;
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "unknown" : auth.getName();
    }
}
