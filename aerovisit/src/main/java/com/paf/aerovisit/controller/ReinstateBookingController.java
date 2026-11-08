package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BookingReinstatement;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.IdentityBans;
import com.paf.aerovisit.service.MailOutbox;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.UploadMirror;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ♻️ "Reinstate booking" (Director only): a PAYMENT_EXPIRED booking the visitor had paid for comes back as PAID +
 * APPROVED, in its original slot or in another open one the Director picks. SecurityConfig limits both paths to the
 * Director and this class checks again (a Guard gets 403). The dialog on the admin page reads options() first.
 */
@Controller
public class ReinstateBookingController {

    static final String NOT_EXPIRED = "Only a booking whose payment expired can be reinstated.";
    static final String NOTE_REQUIRED = "Please add a note saying how the payment was confirmed (e.g. \"Paid; confirmed in Landbank records, receipt sent by email\").";
    static final String NOTE_TOO_LONG = "The note is too long (max " + BookingReinstatement.MAX_NOTE + " characters).";
    static final String SLOT_NOT_OPEN = "That date and time window is not open for this group (full, closed or already passed). Please choose another one.";
    static final String ALREADY_DONE = "This booking was already reinstated or changed by someone else. Nothing was changed.";
    static final String BANNED = "Reinstatement refused: this visitor is banned. Lift the ban on the Banned Visitors page first if this is a mistake.";

    private final VisitorRepository repo;
    private final BookingReinstatement reinstatement;
    private final SeatReservationService seatReservation;
    private final EmailService emailService;

    @Autowired(required = false) private ReceiptStorage receiptStorage;
    @Autowired(required = false) private UploadMirror uploadMirror;
    @Autowired(required = false) private MailOutbox mailOutbox;
    @Autowired(required = false) private NotificationService notificationService;
    @Autowired(required = false) private BlacklistedFaceRepository faceBanRepo;

    public ReinstateBookingController(VisitorRepository repo, BookingReinstatement reinstatement,
                                      SeatReservationService seatReservation, EmailService emailService) {
        this.repo = repo;
        this.reinstatement = reinstatement;
        this.seatReservation = seatReservation;
        this.emailService = emailService;
    }

    // What the dialog shows: the booking, whether its own slot can take it back, the open slots, and the dates on which
    // the same email has another active booking
    @GetMapping("/admin/bookings/{id}/reinstate-options")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> options(@PathVariable Long id) {
        requireDirector();
        Visitor v = repo.findById(id).orElse(null);
        if (v == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Booking not found."));
        if (!Visitor.STATUS_PAYMENT_EXPIRED.equals(v.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", NOT_EXPIRED));
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", v.getId());
        body.put("name", v.getName());
        body.put("email", v.getEmail());
        body.put("date", String.valueOf(v.getVisitDate()));
        body.put("timeWindow", v.getTimeWindow());
        body.put("breakdown", v.admissionSummary());
        body.put("amount", v.amountDueText());
        body.put("billingNumber", v.getBillingNumber());
        body.put("groupSize", Math.max(1, v.getGroupSize()));
        body.put("walkIn", v.walkIn());
        body.put("today", String.valueOf(now.toLocalDate()));
        String problem = reinstatement.originalProblem(v, now);
        body.put("originalAvailable", problem == null);
        body.put("originalProblem", problem);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (BookingReinstatement.Slot s : reinstatement.openSlots(v, now)) {
            slots.add(Map.of("date", s.date().toString(), "timeWindow", s.timeWindow(), "remaining", s.remaining()));
        }
        body.put("openSlots", slots);
        body.put("otherActiveDates", reinstatement.otherActiveDates(v).stream().map(LocalDate::toString).toList());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/admin/bookings/{id}/reinstate")
    public String reinstate(@PathVariable Long id,
                            @RequestParam(value = "note", required = false) String note,
                            @RequestParam(value = "receipt", required = false) MultipartFile receipt,
                            @RequestParam(value = "visitDate", required = false) String visitDate,
                            @RequestParam(value = "timeWindow", required = false) String timeWindow,
                            @RequestParam(value = "returnTo", required = false) String returnTo,
                            RedirectAttributes redirectAttributes) {
        String director = requireDirector();
        String back = AdminReturnTo.redirect(returnTo);
        LocalDateTime now = LocalDateTime.now();
        Visitor v = repo.findById(id).orElse(null);
        String cleanNote = note == null ? "" : note.trim();

        String error = v == null ? "Booking not found."
                : !Visitor.STATUS_PAYMENT_EXPIRED.equals(v.getStatus()) ? NOT_EXPIRED
                : cleanNote.isEmpty() ? NOTE_REQUIRED
                : cleanNote.length() > BookingReinstatement.MAX_NOTE ? NOTE_TOO_LONG
                : isBanned(v) ? BANNED : null;

        // The target slot: the original one (must still be in the future with seats), or the one the Director chose
        LocalDate targetDate = null;
        String targetWindow = null;
        if (error == null) {
            boolean chosen = (visitDate != null && !visitDate.isBlank()) || (timeWindow != null && !timeWindow.isBlank());
            if (!chosen) {
                String problem = reinstatement.originalProblem(v, now);
                if (problem != null) {
                    error = "The original time slot " + problem + ". Choose another open date and time window, then confirm again.";
                } else {
                    targetDate = v.getVisitDate();
                    targetWindow = v.getTimeWindow();
                }
            } else {
                try {
                    targetDate = LocalDate.parse(visitDate == null ? "" : visitDate.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    targetDate = null;
                }
                targetWindow = timeWindow == null ? null : timeWindow.trim();
                if (targetDate == null || targetWindow == null || targetWindow.isEmpty()
                        || reinstatement.findOpen(v, targetDate, targetWindow, now) == null) {
                    error = SLOT_NOT_OPEN;
                }
            }
        }

        // 🧾 The visitor's receipt, if attached: the same checks as a visitor upload (size, real JPG / PNG / WEBP)
        String receiptName = null;
        if (error == null && receipt != null && !receipt.isEmpty()) {
            if (receiptStorage == null) {
                error = "Receipts can't be saved right now. Please try again, or reinstate without the receipt.";
            } else {
                try {
                    receiptName = receiptStorage.saveReceipt(receipt);
                } catch (ReceiptStorage.InvalidReceiptException e) {
                    error = e.getMessage();
                } catch (java.io.IOException e) {
                    error = "The receipt could not be saved. Please try again.";
                }
            }
        }
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return back;
        }

        BookingReinstatement.prepare(v, targetDate, targetWindow, cleanNote, director, now);
        if (receiptName != null) {
            v.keepEarlierReceipt(); // a receipt is proof of payment: the earlier one is never dropped
            v.setReceiptImageUrl(receiptName);
            v.setReceiptUploadedAt(now);
        }

        // 🎫 An advance booking gets its QR code exactly as at approval; a walk-in is checked in at the gate (no QR)
        String qrFileName = null;
        if (!v.walkIn()) {
            try {
                qrFileName = GuardOperationController.issuePass(v, uploadMirror);
            } catch (Exception e) {
                System.err.println("❌ QR for reinstated visitor #" + v.getId() + " failed: " + e);
                discardNewFiles(null, receiptName);
                redirectAttributes.addFlashAttribute("error", "The booking was not reinstated: the QR code could not be created. Please try again.");
                return back;
            }
        }

        try {
            seatReservation.reinstateIfSeatsRemain(v);
        } catch (SeatReservationService.NotReinstatableException | SeatReservationService.CapacityFullException
                 | PessimisticLockingFailureException e) {
            discardNewFiles(qrFileName, receiptName);
            redirectAttributes.addFlashAttribute("error", e instanceof SeatReservationService.NotReinstatableException ? ALREADY_DONE
                    : e instanceof SeatReservationService.CapacityFullException
                    ? "That time slot just filled up. Please choose another open date and time window."
                    : "That time slot is being updated by another booking right now. Please try again.");
            return back;
        }

        if (notificationService != null) notificationService.bookingReinstated(v); // never throws
        String where = v.getReinstatedFrom() == null ? "" : ", moved to " + v.getVisitDate() + " · " + v.getTimeWindow();
        if (qrFileName != null) {
            GuardOperationController.sendApprovalEmail(mailOutbox, emailService, notificationService, v, qrFileName);
            redirectAttributes.addFlashAttribute("success", "Booking reinstated for billing " + v.getBillingNumber() + " ("
                    + v.amountDueText() + ")" + where + ". The approval email with the QR code is on its way to " + v.getEmail() + ".");
        } else {
            redirectAttributes.addFlashAttribute("success", "Walk-in billing " + v.getBillingNumber() + " reinstated" + where
                    + ". It is on Walk-ins Today, ready to check in until its time window ends.");
        }
        return back;
    }

    // Removes what this attempt created when it did not go through: its new QR code and its new (never attached) receipt
    private void discardNewFiles(String qrFileName, String receiptName) {
        if (qrFileName != null) {
            try {
                Files.deleteIfExists(Paths.get("uploads").resolve(qrFileName));
            } catch (java.io.IOException ignored) {
                // a stray QR file holds no seat and is overwritten by the next pass for this booking
            }
            if (uploadMirror != null) uploadMirror.deleteUpload(qrFileName);
        }
        if (receiptName != null && receiptStorage != null) receiptStorage.delete(receiptName);
    }

    private boolean isBanned(Visitor v) {
        return faceBanRepo != null && IdentityBans.activeBan(v, faceBanRepo.findAll(), LocalDate.now()) != null;
    }

    // 403 for anyone but the Director (SecurityConfig already refuses a Guard; this is the second lock); returns the name
    private static String requireDirector() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> "ROLE_DIRECTOR".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return auth.getName();
    }
}
