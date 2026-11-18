package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.MailOutbox;
import com.paf.aerovisit.service.PassportStorage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 🛂 PASSPORT REVIEW (Director only: SecurityConfig /admin/passports/**). The Foreign visitors' passports waiting before
 * an advance booking, kept apart from Pending Approval (there is no booking yet). Approve emails a one-time booking link
 * valid PassportReview.APPROVAL_LINK_DAYS days; Reject needs a reason and emails it with a link to upload a new PDF.
 *
 * Passport files are never served from /uploads (denied to everyone): only here, as an inline PDF with no-store:
 *  - /admin/passports/{id}/file: a review's file (Director);
 *  - /admin/visitors/{id}/passport: a booking's file, by the same rule as receipts (PaymentAdminController.canViewReceipt:
 *    the Director, any booking; a Guard, bookings dated today, e.g. a Foreign walk-in in the Process panel).
 */
@Controller
public class PassportAdminController {

    static final String PAGE = "/admin/passports";
    private static final DateTimeFormatter LINK_EXPIRY = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a", Locale.ENGLISH);

    private final PassportReviewRepository reviews;
    private final VisitorRepository visitors;
    private final EmailService emailService;

    @Autowired(required = false) private PassportStorage storage;
    @Autowired(required = false) private MailOutbox mailOutbox;

    // ⏱️ "Now" (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    public PassportAdminController(PassportReviewRepository reviews, VisitorRepository visitors, EmailService emailService) {
        this.reviews = reviews;
        this.visitors = visitors;
        this.emailService = emailService;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    @GetMapping(PAGE)
    public String list(Model model) {
        model.addAttribute("reviews", reviews.findByStatusOrderBySubmittedAtAsc(PassportReview.PENDING));
        return "passports";
    }

    @PostMapping(PAGE + "/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        PassportReview review = reviews.findById(id).orElse(null);
        if (review == null || !PassportReview.PENDING.equals(review.getStatus())) {
            redirectAttributes.addFlashAttribute("error", "This passport is no longer waiting for review.");
            return "redirect:" + PAGE;
        }
        LocalDateTime now = now();
        review.setStatus(PassportReview.APPROVED);
        review.setToken(UUID.randomUUID().toString());
        review.setTokenExpiresAt(now.plusDays(PassportReview.APPROVAL_LINK_DAYS));
        review.setRejectionReason(null);
        decided(review, now);
        reviews.save(review);
        String to = review.getEmail(), name = review.getFullName(), token = review.getToken(),
                expires = review.getTokenExpiresAt().format(LINK_EXPIRY);
        MailOutbox.send(mailOutbox, "passport-approved email for review #" + review.getId(),
                () -> emailService.sendPassportApproved(to, name, token, expires), null);
        redirectAttributes.addFlashAttribute("success", "Passport approved for " + name + ". The booking link is on its way to "
                + to + " (valid until " + expires + ").");
        return "redirect:" + PAGE;
    }

    @PostMapping(PAGE + "/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(name = "reason", required = false) String reason,
                         RedirectAttributes redirectAttributes) {
        PassportReview review = reviews.findById(id).orElse(null);
        if (review == null || !PassportReview.PENDING.equals(review.getStatus())) {
            redirectAttributes.addFlashAttribute("error", "This passport is no longer waiting for review.");
            return "redirect:" + PAGE;
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 500) {
            redirectAttributes.addFlashAttribute("error", "Please give a reason (up to 500 characters). It is emailed to the visitor.");
            return "redirect:" + PAGE;
        }
        review.setStatus(PassportReview.REJECTED);
        review.setRejectionReason(cleanReason);
        review.setToken(UUID.randomUUID().toString()); // the re-upload link
        review.setTokenExpiresAt(null);
        decided(review, now());
        reviews.save(review);
        String to = review.getEmail(), name = review.getFullName(), token = review.getToken();
        MailOutbox.send(mailOutbox, "passport-rejected email for review #" + review.getId(),
                () -> emailService.sendPassportRejected(to, name, cleanReason, token), null);
        redirectAttributes.addFlashAttribute("success", "Passport rejected for " + name + ". The reason and a link to upload a new copy are on their way to " + to + ".");
        return "redirect:" + PAGE;
    }

    @GetMapping(PAGE + "/{id}/file")
    public ResponseEntity<Resource> reviewFile(@PathVariable Long id) {
        PassportReview review = reviews.findById(id).orElse(null);
        return pdf(review == null ? null : review.getFileName());
    }

    @GetMapping("/admin/visitors/{id}/passport")
    public ResponseEntity<Resource> bookingFile(@PathVariable Long id) {
        Visitor v = visitors.findById(id).orElse(null);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (v == null || !PaymentAdminController.canViewReceipt(auth, v, now().toLocalDate())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return pdf(v.getPassportFileUrl());
    }

    private ResponseEntity<Resource> pdf(String fileName) {
        Path file = storage == null || fileName == null ? null : storage.find(fileName);
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(new FileSystemResource(file));
    }

    private static void decided(PassportReview review, LocalDateTime now) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        review.setDecidedBy(auth == null ? "unknown" : auth.getName());
        review.setDecidedAt(now);
    }
}
