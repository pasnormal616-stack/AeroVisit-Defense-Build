package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Service
public class EmailService {

    // 📮 Every email goes out through Gmail SMTP (spring.mail.*: smtp.gmail.com:587, STARTTLS, 10 s timeouts)
    static final String SENDER_NAME = "AeroVisit System";

    private final JavaMailSender mailSender;

    // Sender of every email, set in exactly one place: aerovisit.mail.from, which follows spring.mail.username
    // (PAFMuseumOfficial@gmail.com), so the "From" always matches the account Gmail logs in with
    @Value("${aerovisit.mail.from:" + DEFAULT_FROM + "}")
    String fromAddress = DEFAULT_FROM;
    static final String DEFAULT_FROM = "PAFMuseumOfficial@gmail.com";

    // Public address of this site, used to build links in emails (set PUBLIC_BASE_URL to the public HTTPS address on AWS)
    private final String publicBaseUrl;

    // 🔒 Gmail App Password (AEROVISIT_MAIL_PASSWORD via spring.mail.password). When it is missing, sending fails at once
    // instead of trying to log in to Gmail without one.
    @Value("${spring.mail.password:}")
    String mailPassword = "";

    // Renders the HTML email templates (templates/email/*.html); Spring Boot's Thymeleaf engine in production
    private final ITemplateEngine templates;

    // ☁️ Brings a QR code back from S3 when a redeploy wiped it from uploads/ (off locally). Optional: tests build this class directly.
    @Autowired(required = false)
    UploadMirror uploadMirror;

    @Autowired
    EmailService(JavaMailSender mailSender,
                 @Value("${aerovisit.public-base-url:http://localhost:8081}") String publicBaseUrl, ITemplateEngine templates) {
        this.mailSender = mailSender;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.templates = templates;
    }

    // Tests pass a recording JavaMailSender; the email templates are read from the classpath as in production
    EmailService(JavaMailSender mailSender, String publicBaseUrl) {
        this(mailSender, publicBaseUrl, standaloneTemplates());
    }

    // Same dialect as Spring Boot's engine (SpringEL), reading templates/email/*.html from the classpath
    static ITemplateEngine standaloneTemplates() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /** An email could not be delivered: missing password, unreadable attachment, SMTP error, or network failure. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message) { super(message); }
        public MailDeliveryException(String message, Throwable cause) { super(message, cause); }
    }

    // One QR code file from uploads/, attached to the email as Entry_QRCode.png
    private Path qrFile(String qrCodeFileName) {
        if (uploadMirror != null) uploadMirror.restoreUpload(qrCodeFileName);
        Path qrPath = Paths.get(System.getProperty("user.dir"), "uploads", qrCodeFileName);
        if (!Files.isReadable(qrPath)) {
            throw new MailDeliveryException("QR code attachment unreadable: " + qrPath);
        }
        return qrPath;
    }

    // Sends one HTML email from the museum's address (fromAddress); qrPath may be null (no attachment).
    private void send(String toEmail, String subject, String html, Path qrPath) {
        send(toEmail, subject, html, qrPath, null);
    }

    // Same, with a Reply-To address (null = replies go to the sender)
    private void send(String toEmail, String subject, String html, Path qrPath, String replyTo) {
        send(toEmail, subject, html, qrPath == null ? null : new FileSystemResource(qrPath), "Entry_QRCode.png", replyTo);
    }

    // 🚶 The walk-in gate pass: the QR is made in memory from the booking's secure token (never saved under uploads/)
    static final String WALK_IN_QR_FILE = "Walk-in_Pass_QR.png";

    private void sendWithWalkInQr(String toEmail, String subject, String html, String qrToken) {
        send(toEmail, subject, html,
                new org.springframework.core.io.ByteArrayResource(com.paf.aerovisit.util.QRCodeGenerator.pngBytes(qrToken, 320)),
                WALK_IN_QR_FILE, null);
    }

    // qr: shown in the message itself (<img src="cid:entryQr">: approved.html, reminder.html, the walk-in emails) and
    // also attached as qrName; null = a plain HTML email
    private void send(String toEmail, String subject, String html, org.springframework.core.io.Resource qr, String qrName, String replyTo) {
        if (mailPassword == null || mailPassword.isBlank()) {
            throw new MailDeliveryException("AEROVISIT_MAIL_PASSWORD is not set");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, qr != null, "UTF-8");
            helper.setFrom(fromAddress, SENDER_NAME);
            helper.setTo(toEmail);
            if (replyTo != null && !replyTo.isBlank()) helper.setReplyTo(replyTo);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (qr != null) {
                helper.addInline("entryQr", qr, "image/png");
                helper.addAttachment(qrName, qr, "image/png");
            }
            mailSender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) { // login refused, SMTP error, timeout
            throw new MailDeliveryException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    private void sendHtml(String toEmail, String subject, String html) {
        send(toEmail, subject, html, null);
    }

    // 🖋 Renders templates/email/<name>.html (shared layout: email/layout.html). Thymeleaf escapes every th:text value,
    // so visitor-typed text (names, feedback, notes) can never inject HTML. A template problem becomes a
    // MailDeliveryException, so every sender's existing catch handles it exactly like a send failure: a booking,
    // approval or check-in never fails because of an email.
    private String render(String template, Map<String, Object> variables) {
        try {
            Context context = new Context(Locale.ENGLISH);
            context.setVariables(variables);
            return templates.process("email/" + template, context);
        } catch (RuntimeException e) {
            throw new MailDeliveryException("Email template '" + template + "' failed: " + e.getMessage(), e);
        }
    }

    // Template variables; values may be null (e.g. a booking without a Reference ID), unlike Map.of
    private static Map<String, Object> vars(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return map;
    }

    // The booking page, for the "Book a New Visit" buttons
    private String bookingPageUrl() {
        return publicBaseUrl + "/";
    }

    // Link visitors open to leave feedback after check-out
    String buildFeedbackLink(String feedbackToken) {
        return publicBaseUrl + "/leave-feedback?token=" + feedbackToken;
    }

    // 📧 Booking-form verification code (OtpService). Returns false instead of throwing, so the form can say "try again".
    public boolean sendOtpEmail(String recipientEmail, String otpCode, long ttlMinutes) {
        try {
            sendHtml(recipientEmail, "PAF Aerospace Museum - Email Verification Code",
                    render("otp", vars("otpCode", otpCode, "ttlMinutes", ttlMinutes)));
            System.out.println("📧 Verification code sent to " + recipientEmail);
            return true;
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send verification code: " + e.getMessage());
            return false;
        }
    }

    // 🔐 Staff account security code: confirms a password change in the staff portal (sent to the museum's own inbox).
    // Its own wording, so the Director never receives a "booking" code. Returns false instead of throwing, like the OTP.
    public boolean sendStaffOtpEmail(String recipientEmail, String otpCode, long ttlMinutes, String requestedChange) {
        try {
            sendHtml(recipientEmail, "PAF Aerospace Museum - Staff Account Security Code",
                    render("staff-otp", vars("otpCode", otpCode, "ttlMinutes", ttlMinutes,
                            "requestedChange", requestedChange, "internal", true)));
            System.out.println("🔐 Staff security code sent to " + recipientEmail);
            return true;
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send staff security code: " + e.getMessage());
            return false;
        }
    }

    // 🔑 Forgot Password code (public sign-in page): lets the Director set a new password without being signed in.
    // Returns false instead of throwing, like the other codes.
    public boolean sendPasswordResetEmail(String recipientEmail, String otpCode, long ttlMinutes) {
        try {
            sendHtml(recipientEmail, "PAF Aerospace Museum - Staff Password Reset Code",
                    render("forgot-password", vars("otpCode", otpCode, "ttlMinutes", ttlMinutes, "internal", true)));
            System.out.println("🔑 Password reset code sent to " + recipientEmail);
            return true;
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send password reset code: " + e.getMessage());
            return false;
        }
    }

    // --- NEW: THE INSTANT "PENDING REGISTRATION" AUTO-REPLY ---
    public void sendPendingNotification(String toEmail, String name, String date) {
        sendPendingNotification(toEmail, name, date, null);
    }

    // Same auto-reply, but also shows the visitor's Booking Reference ID when one is provided
    public void sendPendingNotification(String toEmail, String name, String date, String referenceId) {
        try {
            String subject = "PAF Aerospace Museum - Visit Request Received";
            sendHtml(toEmail, subject, render("pending", vars("name", name, "date", date, "referenceId", referenceId)));
            System.out.println("⏳ Pending Auto-Reply successfully sent to " + toEmail);
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Pending Auto-Reply email: " + e.getMessage());
        }
    }

    // --- 1. THE ORIGINAL APPROVAL EMAIL ---
    public void sendApprovalEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName) {
        sendApprovalEmailWithQR(toEmail, visitorName, date, qrCodeFileName, null);
    }

    // Same approval email, but also shows the visitor's Booking Reference ID when one is provided
    public void sendApprovalEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName, String referenceId) {
        sendApprovalEmailWithQR(toEmail, visitorName, date, qrCodeFileName, referenceId, null);
    }

    // ...plus the booked time window (e.g. "Morning (08:00 AM - 12:00 PM)"), shown with the gate's arrive-on-time rule;
    // null hides it (no readable slot, e.g. an older booking's "Master Booking Bypass")
    public void sendApprovalEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName,
                                        String referenceId, String timeWindow) {
        try {
            String subject = "PAF Aerospace Museum - Visit Approved: Entry Pass Enclosed";
            String emailContent = render("approved", vars("name", visitorName, "date", date, "referenceId", referenceId,
                    "timeWindow", timeWindow));
            send(toEmail, subject, emailContent, qrFile(qrCodeFileName));
            System.out.println("✅ Approval email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send email: " + e.getMessage());
            // 📮 Passed on (after logging): the approval email is sent in the background (MailOutbox), which reports a
            // failure to the Director's bell; without the QR code the visitor can't enter
            throw e;
        }
    }

    // --- 2. THE AUTOMATED REMINDER EMAIL ---
    public void sendReminderEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName) {
        try {
            String subject = "PAF Aerospace Museum - Visit Reminder for Tomorrow";
            String emailContent = render("reminder", vars("name", visitorName, "date", date));
            send(toEmail, subject, emailContent, qrFile(qrCodeFileName));
            System.out.println("⏰ Auto-Reminder successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send reminder email: " + e.getMessage());
        }
    }

    // Figures for the Director's end-of-day email. Counts are bookings; *Guests sum groupSize (a group is one booking, many guests).
    public record DailyReport(String date, long expected, long expectedGuests, long checkedIn, long checkedInGuests,
                              long noShows, long noShowGuests, long stillOpen, long noShowsAllTime,
                              String nextDate, long pendingNextDay, Insights insights, WalkInPayments walkIns) {
        public DailyReport(String date, long expected, long expectedGuests, long checkedIn, long checkedInGuests,
                           long noShows, long noShowGuests, long stillOpen, long noShowsAllTime,
                           String nextDate, long pendingNextDay, Insights insights) {
            this(date, expected, expectedGuests, checkedIn, checkedInGuests, noShows, noShowGuests, stillOpen, noShowsAllTime,
                 nextDate, pendingNextDay, insights, WalkInPayments.NONE);
        }

        public Long checkInRate() { return expected > 0 ? Math.round(checkedIn * 100.0 / expected) : null; }
    }

    // 🚶 The day's walk-ins (bookings and guests, part of the expected figures) and their verified payments: count,
    // total and each billing number with who verified it, to check against Landbank's records the same day
    public record WalkInPayment(String billingNumber, String amount, String verifiedBy, String verifiedAt) {}

    public record WalkInPayments(long bookings, long guests, int count, String total, java.util.List<WalkInPayment> payments) {
        public static final WalkInPayments NONE = new WalkInPayments(0, 0, 0, com.paf.aerovisit.util.Peso.format(0), java.util.List.of());
    }

    // Visit insights from data each Visitor row already holds. Null / empty = nothing to report that day.
    public record Insights(String peakHour, long peakHourGuests, Long avgVisitMinutes, long timedVisits,
                           Map<String, Long> visitorTypes, Map<String, Long> sexes, Double avgRating, long ratingCount) {
        public static final Insights NONE = new Insights(null, 0, null, 0, Map.of(), Map.of(), null, 0);

        public String avgVisit() {
            if (avgVisitMinutes == null) return null;
            return (avgVisitMinutes >= 60 ? avgVisitMinutes / 60 + " h " : "") + avgVisitMinutes % 60 + " min";
        }
    }

    // --- 3. THE END-OF-DAY DIRECTOR REPORT ---
    public void sendDirectorDailyReport(String directorEmail, DailyReport report) {
        try {
            String subject = "PAF Aerospace Museum - Daily Visitor Summary - " + report.date();
            String emailContent = render("daily-report", vars("r", report, "date", report.date(), "internal", true));
            sendHtml(directorEmail, subject, emailContent);
            System.out.println("📊 Daily Director Report successfully dispatched!");

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Director Report: " + e.getMessage());
        }
    }

    // --- 4. THE CHECK-IN GUIDELINES EMAIL (HTML) ---
    public void sendCheckInGuidelines(String to, String name) {
        try {
            String subject = "PAF Aerospace Museum - Entry Confirmed: Visitor Guidelines";
            sendHtml(to, subject, render("check-in", vars("name", name)));
            System.out.println("🟢 Check-In Guidelines successfully sent to " + to);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Check-In email: " + e.getMessage());
        }
    }

    // --- 5. THE CHECK-OUT FEEDBACK EMAIL (HTML) ---
    // 🔒 Uses the visitor's secure token (not the guessable numeric ID) so only the real visitor can open the form
    public void sendCheckOutFeedback(String to, String name, String feedbackToken) {
        try {
            String subject = "PAF Aerospace Museum - Thank You for Your Visit: Feedback Request";
            String feedbackLink = buildFeedbackLink(feedbackToken);
            sendHtml(to, subject, render("check-out", vars("name", name, "feedbackLink", feedbackLink)));
            System.out.println("👋 Check-Out Feedback successfully sent to " + to);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Check-Out email: " + e.getMessage());
        }
    }

    // --- 7. NLP CRITICAL FEEDBACK ALERT (AI TRIGGERED) ---
    public void sendCriticalFeedbackAlert(String directorEmail, String visitorName, String feedbackText, double sentimentScore) {
        try {
            String subject = "PAF Aerospace Museum - Alert: Critical Visitor Feedback";
            sendHtml(directorEmail, subject, render("feedback-alert", vars("visitorName", visitorName,
                    "feedbackText", feedbackText, "sentimentScore", sentimentScore, "internal", true)));
            System.out.println("🚨 NLP Critical Alert successfully dispatched to Director!");

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send NLP Alert email: " + e.getMessage());
        }
    }

    // --- 8. 🔥 NEW: REGISTRATION REJECTION WITH COMPLIANCE NOTES (HTML) ---
    public void sendRejectionEmail(String toEmail, String visitorName, String presetReason, String additionalNotes) {
        try {
            String name = visitorName == null ? "Visitor" : visitorName;

            // 📅 Capacity rejection: nothing is wrong with the registration, so the visitor is simply invited to rebook
            if (presetReason != null && presetReason.contains("Capacity Reached")) {
                String subject = "PAF Aerospace Museum - Requested Date Fully Booked";
                sendHtml(toEmail, subject, render("rejected-capacity",
                        vars("name", name, "notes", additionalNotes, "bookUrl", bookingPageUrl())));
                System.out.println("📅 Capacity Reached (rebook) email successfully sent to " + toEmail);
                return;
            }

            String subject = "PAF Aerospace Museum - Visit Request Not Approved";
            sendHtml(toEmail, subject, render("rejected", vars("name", name, "reason", presetReason, "notes", additionalNotes)));
            System.out.println("⚠️ Rejection Compliance email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Rejection email: " + e.getMessage());
            throw e; // 📮 passed on, like the approval email: the background sender reports it to the Director's bell
        }
    }

    // --- 9. BOOKING CANCELLATION: EMERGENCY CLOSURE (HTML) ---
    // Unlike the other senders, failures are thrown, so the closure batch can count and report each one
    public void sendEmergencyCancellation(com.paf.aerovisit.model.Visitor visitor, String reason) throws MailDeliveryException {

        String subject = "PAF Aerospace Museum - Museum Closure: Your Visit on " + visitor.getVisitDate() + " Is Cancelled";

        String visitorName = visitor.getName();
        String htmlMsg = render("emergency-cancelled", vars("name", visitorName == null ? "Visitor" : visitorName,
                "date", String.valueOf(visitor.getVisitDate()), "reason", reason, "paid", false,
                "referenceId", visitor.getReferenceId(), "bookUrl", bookingPageUrl()));

        sendHtml(visitor.getEmail(), subject, htmlMsg);
        System.out.println("📭 Emergency Closure email successfully sent to " + visitor.getEmail());
    }

    // 🚨 Emergency closure for a booking already paid (or whose receipt is being checked): the payment is kept and the
    // visitor picks another date in Manage Booking (the Terms: payments are non-refundable). Thrown like the email above.
    public void sendClosureReschedule(com.paf.aerovisit.model.Visitor visitor, String reason) throws MailDeliveryException {
        String subject = "PAF Aerospace Museum - Museum Closure: Please Reschedule Your Visit on " + visitor.getVisitDate();
        String visitorName = visitor.getName();
        String htmlMsg = render("emergency-cancelled", vars("name", visitorName == null ? "Visitor" : visitorName,
                "date", String.valueOf(visitor.getVisitDate()), "reason", reason, "paid", true,
                "billingNumber", visitor.getBillingNumber(), "amount", visitor.amountDueText(),
                "referenceId", visitor.getReferenceId(), "bookUrl", manageBookingUrl(),
                "deadline", visitor.rescheduleDeadline() == null ? null : visitor.rescheduleDeadline().toString()));
        sendHtml(visitor.getEmail(), subject, htmlMsg);
        System.out.println("📭 Closure reschedule email successfully sent to " + visitor.getEmail());
    }

    // 📅 A paid booking must pick a new date in Manage Booking by its deadline (Visitor.rescheduleDeadline): sent when a paid
    // request was not reviewed before its visit date, and (reminder = true) once, 7 days before the deadline
    public void sendRescheduleNeeded(com.paf.aerovisit.model.Visitor visitor, boolean reminder) throws MailDeliveryException {
        String subject = reminder
                ? "PAF Aerospace Museum - Reminder: Please Choose a New Date for Your Visit"
                : "PAF Aerospace Museum - Please Choose a New Date for Your Visit";
        String visitorName = visitor.getName();
        String htmlMsg = render("reschedule-needed", vars("name", visitorName == null ? "Visitor" : visitorName,
                "date", String.valueOf(visitor.getVisitDate()), "reminder", reminder,
                "unreviewed", com.paf.aerovisit.model.Visitor.UNREVIEWED_NOTE.equals(visitor.getRejectionReason()),
                "deadline", String.valueOf(visitor.rescheduleDeadline()),
                "billingNumber", visitor.getBillingNumber(), "amount", visitor.amountDueText(),
                "referenceId", visitor.getReferenceId(), "bookUrl", manageBookingUrl()));
        sendHtml(visitor.getEmail(), subject, htmlMsg);
        System.out.println("📭 Reschedule-needed email successfully sent to " + visitor.getEmail());
    }

    // --- 10. 💳 PAYMENT (Landbank Link.BizPortal, outside AeroVisit) ---
    // What the payment emails show, read from the booking when the email is queued (the booking may change later)
    // walkIn: booked at the museum for today (the emails then speak of minutes and the gate, not of approval and a QR)
    // approved: the booking was already approved (older approve-then-pay bookings); false = pay first, then review
    // qrToken: a walk-in's secure token, so the walk-in payment email carries its gate QR (null for advance bookings)
    public record PaymentNotice(String to, String name, String referenceId, String billingNumber, String amount,
                                String breakdown, String deadline, String paymentToken, String reason, boolean walkIn,
                                boolean approved, String contactEmail, String qrToken) {
        // Without a gate QR (advance bookings, and callers that don't need one)
        public PaymentNotice(String to, String name, String referenceId, String billingNumber, String amount,
                             String breakdown, String deadline, String paymentToken, String reason, boolean walkIn,
                             boolean approved, String contactEmail) {
            this(to, name, referenceId, billingNumber, amount, breakdown, deadline, paymentToken, reason, walkIn, approved,
                    contactEmail, null);
        }

        public static PaymentNotice of(com.paf.aerovisit.model.Visitor v) {
            return of(v, null);
        }

        // contactEmail: the museum contact email from Settings (Website Content), or null when none is set
        public static PaymentNotice of(com.paf.aerovisit.model.Visitor v, String contactEmail) {
            return new PaymentNotice(v.getEmail(), v.getName() == null ? "Visitor" : v.getName(), v.getReferenceId(),
                    v.getBillingNumber(), v.amountDueText(), v.admissionSummary(),
                    PaymentWorkflow.deadlineText(v.getPaymentDueAt()), v.getPaymentToken(), v.getPaymentRejectionReason(), v.walkIn(),
                    "APPROVED".equals(v.getStatus()), contactEmail == null || contactEmail.isBlank() ? null : contactEmail.trim(),
                    v.walkIn() ? v.getSecureToken() : null);
        }
    }

    // The visitor's own payment page: the unguessable token is the key (never the billing number)
    String paymentPageUrl(String paymentToken) {
        return publicBaseUrl + "/payment?token=" + paymentToken;
    }

    private String manageBookingUrl() {
        return publicBaseUrl + "/manage-booking";
    }

    private Map<String, Object> paymentVars(PaymentNotice n) {
        return vars("name", n.name(), "referenceId", n.referenceId(), "billingNumber", n.billingNumber(), "amount", n.amount(),
                "breakdown", n.breakdown(), "deadline", n.deadline(), "reason", n.reason(), "walkIn", n.walkIn(),
                "approved", n.approved(),
                "contactEmail", n.contactEmail() != null ? n.contactEmail() : fromAddress,
                "payUrl", n.paymentToken() == null ? manageBookingUrl() : paymentPageUrl(n.paymentToken()),
                "bookUrl", bookingPageUrl(), "walkInQr", n.walkIn() && n.qrToken() != null);
    }

    // Approved, please pay: billing number, amount, breakdown, deadline and the button to the payment page (no QR yet).
    // Failures are thrown, so the background sender reports them to the Director's bell (like the approval email).
    public void sendPaymentRequest(PaymentNotice n) {
        try {
            String subject = n.walkIn() ? "PAF Aerospace Museum - Your Walk-in Billing Number: " + n.billingNumber()
                    : n.approved() ? "PAF Aerospace Museum - Visit Approved: Please Complete Your Payment"
                    : "PAF Aerospace Museum - Booking Received: Please Pay by " + n.deadline();
            String html = render("payment-requested", paymentVars(n));
            // 🚶 A walk-in's email also carries its gate QR ("Show this QR to the guard at the gate")
            if (n.walkIn() && n.qrToken() != null) sendWithWalkInQr(n.to(), subject, html, n.qrToken());
            else sendHtml(n.to(), subject, html);
            System.out.println("💳 Payment request email successfully sent to " + n.to());
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send payment request email: " + e.getMessage());
            throw e;
        }
    }

    // 🚶 A FREE walk-in (nothing to pay): its gate pass by email, so the QR isn't lost if the visitor closes the page.
    // Failures are thrown (the background sender logs them); the "go to the gate" screen already shows the same QR.
    public void sendWalkInPass(String toEmail, String visitorName, String referenceId, String timeWindow, String qrToken) {
        try {
            String html = render("walkin-pass", vars("name", visitorName == null ? "Visitor" : visitorName,
                    "referenceId", referenceId, "timeWindow", timeWindow));
            sendWithWalkInQr(toEmail, "PAF Aerospace Museum - Your Walk-in Pass for Today", html, qrToken);
            System.out.println("🚶 Walk-in pass email successfully sent to " + toEmail);
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send walk-in pass email: " + e.getMessage());
            throw e;
        }
    }

    // The receipt was not accepted: the reason, the new deadline and the same link to upload another one
    public void sendPaymentRejected(PaymentNotice n) {
        try {
            sendHtml(n.to(), "PAF Aerospace Museum - Payment Receipt Not Accepted: Upload a New One by " + n.deadline(),
                    render("payment-rejected", paymentVars(n)));
            System.out.println("💳 Receipt rejection email successfully sent to " + n.to());
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send receipt rejection email: " + e.getMessage());
            throw e;
        }
    }

    // No receipt by the deadline: the seats were released (the nightly-style sweep never throws, so failures are logged).
    // Reply-To is the museum contact email, so a visitor who already paid and simply replies reaches the museum even
    // when the sender is a no-reply address (Amazon SES); no contact email set = no Reply-To.
    public void sendPaymentExpired(PaymentNotice n) {
        try {
            send(n.to(), "PAF Aerospace Museum - Booking Released: Payment Not Received", render("payment-expired", paymentVars(n)),
                    null, n.contactEmail());
            System.out.println("💳 Payment expiry email successfully sent to " + n.to());
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send payment expiry email: " + e.getMessage());
        }
    }

    // ---------- 🛂 Passport review (Foreign advance bookings) ----------

    // The passport was received (or a new one replaced a rejected one): the museum reviews it, nothing to do yet
    public void sendPassportReceived(String to, String name) {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("name", name);
        try {
            sendHtml(to, "PAF Aerospace Museum - Passport Received: Under Review", render("passport-received", vars));
            System.out.println("🛂 Passport-received email successfully sent to " + to);
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send passport-received email: " + e.getMessage());
            throw e;
        }
    }

    // Approved: the one-time link into the booking form (bookUrl ends with the token), usable until `expires`
    public void sendPassportApproved(String to, String name, String token, String expires) {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("name", name);
        vars.put("bookUrl", publicBaseUrl + "/passport/continue?token=" + token);
        vars.put("expires", expires);
        try {
            sendHtml(to, "PAF Aerospace Museum - Passport Approved: Book Your Visit", render("passport-approved", vars));
            System.out.println("🛂 Passport-approved email successfully sent to " + to);
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send passport-approved email: " + e.getMessage());
            throw e;
        }
    }

    // Rejected: the reason, and the link to upload a new passport PDF (it replaces the rejected one)
    public void sendPassportRejected(String to, String name, String reason, String token) {
        Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("name", name);
        vars.put("reason", reason);
        vars.put("uploadUrl", publicBaseUrl + "/passport/resubmit?token=" + token);
        try {
            sendHtml(to, "PAF Aerospace Museum - Passport Not Accepted: Please Upload a New One", render("passport-rejected", vars));
            System.out.println("🛂 Passport-rejected email successfully sent to " + to);
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send passport-rejected email: " + e.getMessage());
            throw e;
        }
    }
}
