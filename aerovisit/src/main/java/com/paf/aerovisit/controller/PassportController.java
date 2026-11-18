package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.IdentityBans;
import com.paf.aerovisit.service.MailOutbox;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PassportStorage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 🛂 FOREIGN VISITORS, ADVANCE BOOKING (public side). Book a Visit > Nationality: Foreign > Advance Booking does not pick
 * a slot: the visitor sends a passport PDF and contact details here, the Director reviews it (PassportAdminController),
 * and the emailed link decides what happens next:
 *  - approved: GET /passport/continue?token= signs this browser in to that one approval (session attribute
 *    FOREIGN_REVIEW_ID) and opens the normal booking form with the name, email and phone carried over; /save then books
 *    exactly as for a local visitor and marks the approval used (one booking per approval). The link works for
 *    APPROVAL_LINK_DAYS days and can be opened again and again: it resumes the date and time picked so far (POST
 *    /passport/draft, stored on the review), or the booking's payment page until its receipt is uploaded;
 *  - rejected: GET /passport/resubmit?token= shows the reason and takes a new PDF, which replaces the old one on the
 *    same review (never a second record).
 * Every POST here carries the CSRF token, is rate-limited per IP (RateLimitFilter: passport-submit) and checks the ban
 * list first, like /save.
 */
@Controller
public class PassportController {

    // The session key of the approved review whose link opened the booking form (read by VisitorPortalController)
    static final String FOREIGN_REVIEW_ID = "foreignReviewId";

    static final String TERMS_REQUIRED = "Please read and agree to the Terms and Conditions first.";
    static final String EMAIL_NOT_VERIFIED = "Security Error: Please verify your email address with the 6-digit code we sent before submitting.";
    static final String DETAILS_REQUIRED = "Please enter your full name, email address and contact number.";
    static final String LINK_INVALID = "This link is invalid, has expired or has already been used. Please submit your passport again from the booking page.";
    static final String LINK_EXPIRED = "This booking link has expired. Approval links work for " + PassportReview.APPROVAL_LINK_DAYS
            + " days. Please submit your passport again from the booking page.";
    static final String LINK_USED = "This booking link has already been used for a booking. Each approval books one visit. To book another visit, please submit your passport again from the booking page.";
    static final String SUBMIT_FAILED = "Your passport could not be saved. Please try again.";

    private final PassportReviewRepository reviews;
    private final EmailService emailService;

    @Autowired(required = false) private OtpService otpService;
    @Autowired(required = false) private BlacklistedFaceRepository banRepo;
    @Autowired(required = false) private PassportStorage storage;
    @Autowired(required = false) private NotificationService notificationService;
    @Autowired(required = false) private MailOutbox mailOutbox;
    // The booking an approval link made (to resume its payment page)
    @Autowired(required = false) private VisitorRepository visitorRepo;

    // ⏱️ "Now" (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    public PassportController(PassportReviewRepository reviews, EmailService emailService) {
        this.reviews = reviews;
        this.emailService = emailService;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    // ---------- Send a passport (Book a Visit > Foreign > Advance) ----------

    @PostMapping("/passport/submit")
    public String submit(@RequestParam(name = "fullName", required = false) String fullName,
                         @RequestParam(name = "email", required = false) String email,
                         @RequestParam(name = "contactNumber", required = false) String contactNumber,
                         @RequestParam(name = "termsAccepted", required = false) String termsAccepted,
                         @RequestParam(name = "passportFile", required = false) MultipartFile passportFile,
                         RedirectAttributes redirectAttributes) {
        String name = clean(fullName, 200), mail = clean(email, 200), phone = clean(contactNumber, 40);
        String refusal = !"true".equals(termsAccepted) ? TERMS_REQUIRED
                : name == null || mail == null || phone == null ? DETAILS_REQUIRED
                : otpService == null || !otpService.isVerified(mail) ? EMAIL_NOT_VERIFIED : null;
        // 🪪 The ban list first, exactly like /save: the same email or phone as an active ban is refused, nothing is stored
        if (refusal == null && contactBanned(name, mail, phone)) refusal = IdentityBans.BANNED_MESSAGE;
        if (refusal == null) refusal = storage == null ? SUBMIT_FAILED : PassportStorage.error(passportFile);
        if (refusal != null) return backToForm(redirectAttributes, refusal);

        String fileName;
        try {
            fileName = storage.save(passportFile);
        } catch (Exception e) {
            System.err.println("Passport could not be saved: " + e.getMessage());
            return backToForm(redirectAttributes, SUBMIT_FAILED);
        }
        // ♻️ An open review for this email (waiting, or rejected) is replaced on the same row: never a second record
        PassportReview review = reviews.findByEmailIgnoreCaseAndStatusIn(mail, List.of(PassportReview.PENDING, PassportReview.REJECTED))
                .stream().findFirst().orElseGet(PassportReview::new);
        boolean replaced = review.getId() != null;
        String oldFile = review.getFileName();
        review.setFullName(name);
        review.setEmail(mail);
        review.setContactNumber(phone);
        putUnderReview(review, fileName, name);
        reviews.save(review);
        if (replaced && oldFile != null && !oldFile.equals(fileName)) storage.delete(oldFile);
        otpService.consume(mail); // one verified code = one submission

        notifyReceived(review, replaced);
        redirectAttributes.addFlashAttribute("passportEmail", mail);
        return "redirect:/passport/submitted";
    }

    @GetMapping("/passport/submitted")
    public String submitted(Model model) {
        if (!model.containsAttribute("passportEmail")) return "redirect:/";
        return "passport-submitted";
    }

    // ---------- Rejected: upload a new PDF (replaces the old one) ----------

    @GetMapping("/passport/resubmit")
    public String resubmitForm(@RequestParam(name = "token", required = false) String token, Model model) {
        PassportReview review = byToken(token);
        if (review == null || !PassportReview.REJECTED.equals(review.getStatus())) return linkError(model);
        model.addAttribute("review", review);
        model.addAttribute("token", token.trim());
        return "passport-resubmit";
    }

    @PostMapping("/passport/resubmit")
    public String resubmit(@RequestParam(name = "token", required = false) String token,
                           @RequestParam(name = "passportFile", required = false) MultipartFile passportFile,
                           Model model, RedirectAttributes redirectAttributes) {
        PassportReview review = byToken(token);
        if (review == null || !PassportReview.REJECTED.equals(review.getStatus())) return linkError(model);
        String back = "redirect:/passport/resubmit?token=" + review.getToken();
        String refusal = contactBanned(review.getFullName(), review.getEmail(), review.getContactNumber()) ? IdentityBans.BANNED_MESSAGE
                : storage == null ? SUBMIT_FAILED : PassportStorage.error(passportFile);
        if (refusal != null) {
            redirectAttributes.addFlashAttribute("error", refusal);
            return back;
        }
        String fileName;
        try {
            fileName = storage.save(passportFile);
        } catch (Exception e) {
            System.err.println("Passport could not be saved: " + e.getMessage());
            redirectAttributes.addFlashAttribute("error", SUBMIT_FAILED);
            return back;
        }
        String oldFile = review.getFileName();
        putUnderReview(review, fileName, review.getFullName());
        reviews.save(review);
        if (oldFile != null && !oldFile.equals(fileName)) storage.delete(oldFile);

        notifyReceived(review, true);
        redirectAttributes.addFlashAttribute("passportEmail", review.getEmail());
        return "redirect:/passport/submitted";
    }

    // ---------- Approved: the one-time link into the booking form ----------

    // Opening the link, any number of times, never uses it up: only /save does, when a booking is actually made. Within
    // its APPROVAL_LINK_DAYS (whatever happened to it):
    //  - no booking yet (APPROVED): the booking form, resuming at the date and time last picked from this link;
    //  - a booking made with it that still needs its receipt: that booking's payment page, as it is (any device);
    //  - a booking past that point (receipt uploaded, free booking, or anything later): "already used".
    @GetMapping("/passport/continue")
    public String continueToBooking(@RequestParam(name = "token", required = false) String token, Model model,
                                    HttpServletRequest request) {
        PassportReview review = byToken(token);
        LocalDateTime now = now();
        if (review == null) return linkError(model, LINK_INVALID);
        boolean approved = PassportReview.APPROVED.equals(review.getStatus()), used = PassportReview.USED.equals(review.getStatus());
        if (!approved && !used) return linkError(model, LINK_INVALID);
        if (!review.withinLinkWindow(now)) return linkError(model, LINK_EXPIRED);
        // 🍪 Forward, not redirect, in both cases below: the session cookie is SameSite=Strict, and a browser drops it on a
        // redirect that follows a click from another site (Gmail, Outlook web), so the next page used to open in a new,
        // empty session (the plain form). Rendered in this same request, the page sees the session; its own later posts
        // (/save, the receipt upload) are same-site.
        if (approved) {
            // 🔒 New session ID first (no session fixation); the booking form then reads this approval from the session
            request.getSession(true);
            request.changeSessionId();
            request.getSession(false).setAttribute(FOREIGN_REVIEW_ID, review.getId());
            return "forward:/";
        }
        Visitor booking = review.getVisitorId() == null || visitorRepo == null ? null
                : visitorRepo.findById(review.getVisitorId()).orElse(null);
        if (booking == null || !booking.waitingForReceipt()) return linkError(model, LINK_USED);
        // 💳 Booked, not paid yet: resume on its payment page (billing number, deadline, receipt upload), as Manage Booking
        VisitorPortalController.signIntoManageBooking(request, booking.getId());
        return "forward:/manage-booking/payment";
    }

    // 💾 The date and time window just picked on the booking form opened by this browser's approval link, kept on the
    // review so that reopening the link (refresh, another tab, another device) resumes there. Only while the approval is
    // unused and unexpired; a targeted UPDATE, so it can never undo a booking's claim on the approval.
    @PostMapping("/passport/draft")
    @ResponseBody
    public ResponseEntity<Void> saveDraft(@RequestParam(name = "visitDate", required = false) String visitDate,
                                          @RequestParam(name = "timeWindow", required = false) String timeWindow,
                                          HttpServletRequest request) {
        LocalDateTime now = now();
        PassportReview review = approvedFor(request.getSession(false), reviews, now);
        if (review == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String window = clean(timeWindow, 120);
        LocalDate date;
        try {
            date = visitDate == null ? null : LocalDate.parse(visitDate.trim());
        } catch (java.time.format.DateTimeParseException e) {
            date = null;
        }
        if (date == null || window == null || date.isBefore(now.toLocalDate())) return ResponseEntity.badRequest().build();
        reviews.saveDraft(review.getId(), date, window);
        return ResponseEntity.noContent().build();
    }

    // The approved review this session's link opened, while it can still be used; else null (and the key is dropped)
    static PassportReview approvedFor(HttpSession session, PassportReviewRepository reviews, LocalDateTime now) {
        if (session == null || reviews == null) return null;
        Object id = session.getAttribute(FOREIGN_REVIEW_ID);
        if (!(id instanceof Long reviewId)) return null;
        PassportReview review = reviews.findById(reviewId).orElse(null);
        if (review == null || !review.approvalLinkUsable(now)) {
            session.removeAttribute(FOREIGN_REVIEW_ID);
            return null;
        }
        return review;
    }

    // ---------- helpers ----------

    // A new file goes under review: PENDING, no link (an old link stops working), the name-ban flag refreshed
    private void putUnderReview(PassportReview review, String fileName, String name) {
        review.setFileName(fileName);
        review.setStatus(PassportReview.PENDING);
        review.setToken(null);
        review.setTokenExpiresAt(null);
        review.setRejectionReason(null);
        review.setDecidedAt(null);
        review.setDecidedBy(null);
        review.setSubmittedAt(now());
        // 🪪 A name resembling a ban is only flagged for the Director (people share names), never refused
        BlacklistedFace nameBan = IdentityBans.nameBan(List.of(name), bans(), now().toLocalDate());
        String match = nameBan == null ? null : "Ban #" + nameBan.getId() + ": " + nameBan.getName();
        review.setPossibleBanMatch(match == null || match.length() <= 300 ? match : match.substring(0, 300));
    }

    private boolean contactBanned(String name, String email, String phone) {
        if (IdentityBans.contactBan(email, phone, bans(), now().toLocalDate()) == null) return false;
        if (notificationService != null) {
            Visitor attempted = new Visitor(); // never saved: the bell's actor and message only
            attempted.setName(name);
            attempted.setFullName(name);
            attempted.setEmail(email);
            attempted.setContactNumber(phone);
            notificationService.bannedContactAttempt(attempted);
        }
        return true;
    }

    private List<BlacklistedFace> bans() {
        return banRepo == null ? List.of() : banRepo.findAll();
    }

    private void notifyReceived(PassportReview review, boolean resubmitted) {
        String to = review.getEmail(), name = review.getFullName();
        MailOutbox.send(mailOutbox, "passport-received email for review #" + review.getId(),
                () -> emailService.sendPassportReceived(to, name), null);
        if (notificationService != null) notificationService.passportSubmitted(name, resubmitted);
    }

    private PassportReview byToken(String token) {
        if (token == null || token.isBlank() || token.length() > 64) return null;
        return reviews.findByToken(token.trim()).orElse(null);
    }

    private static String linkError(Model model) {
        return linkError(model, LINK_INVALID);
    }

    private static String linkError(Model model, String message) {
        model.addAttribute("error", message);
        return "feedback-error";
    }

    // Back to Book a Visit with the reason; the page reopens Foreign > Advance (passportRetry)
    private static String backToForm(RedirectAttributes redirectAttributes, String message) {
        redirectAttributes.addFlashAttribute("error", message);
        redirectAttributes.addFlashAttribute("passportRetry", true);
        return "redirect:/";
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
