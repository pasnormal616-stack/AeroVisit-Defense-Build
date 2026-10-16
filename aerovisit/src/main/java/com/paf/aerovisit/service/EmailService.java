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

import com.paf.aerovisit.model.StaffSchedule;

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

    // Public address of this site, used to build links in emails (set PUBLIC_BASE_URL when running behind ngrok or a domain)
    private final String publicBaseUrl;

    // 🔒 Gmail App Password (AEROVISIT_MAIL_PASSWORD via spring.mail.password). When it is missing, sending fails at once
    // instead of trying to log in to Gmail without one.
    @Value("${spring.mail.password:}")
    String mailPassword = "";

    // Renders the HTML email templates (templates/email/*.html); Spring Boot's Thymeleaf engine in production
    private final ITemplateEngine templates;

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
    private static Path qrFile(String qrCodeFileName) {
        Path qrPath = Paths.get(System.getProperty("user.dir"), "uploads", qrCodeFileName);
        if (!Files.isReadable(qrPath)) {
            throw new MailDeliveryException("QR code attachment unreadable: " + qrPath);
        }
        return qrPath;
    }

    // Sends one HTML email from the museum's address (fromAddress); qrPath may be null (no attachment).
    private void send(String toEmail, String subject, String html, Path qrPath) {
        if (mailPassword == null || mailPassword.isBlank()) {
            throw new MailDeliveryException("AEROVISIT_MAIL_PASSWORD is not set");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, qrPath != null, "UTF-8");
            helper.setFrom(fromAddress, SENDER_NAME);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (qrPath != null) helper.addAttachment("Entry_QRCode.png", new FileSystemResource(qrPath));
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
    public void sendPendingNotification(String toEmail, String name, String date, boolean isMasterBooking) {
        sendPendingNotification(toEmail, name, date, isMasterBooking, null);
    }

    // Same auto-reply, but also shows the visitor's Booking Reference ID when one is provided
    public void sendPendingNotification(String toEmail, String name, String date, boolean isMasterBooking, String referenceId) {
        try {
            String subject = isMasterBooking ? "PAF Aerospace Museum - Master Booking Request Received"
                                             : "PAF Aerospace Museum - Visit Request Received";
            sendHtml(toEmail, subject, render("pending",
                    vars("name", name, "date", date, "masterBooking", isMasterBooking, "referenceId", referenceId)));
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
        try {
            String subject = "PAF Aerospace Museum - Visit Approved: Entry Pass Enclosed";
            String emailContent = render("approved", vars("name", visitorName, "date", date, "referenceId", referenceId));
            send(toEmail, subject, emailContent, qrFile(qrCodeFileName));
            System.out.println("✅ Approval email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send email: " + e.getMessage());
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

    // Figures for the Director's end-of-day email. Counts are bookings; *Guests sum groupSize (a Master Booking is one booking, many guests).
    public record DailyReport(String date, long expected, long expectedGuests, long checkedIn, long checkedInGuests,
                              long noShows, long noShowGuests, long stillOpen, long noShowsAllTime,
                              String nextDate, long pendingNextDay, Insights insights) {
        public Long checkInRate() { return expected > 0 ? Math.round(checkedIn * 100.0 / expected) : null; }
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
        sendCheckInGuidelines(to, name, null);
    }

    // Same guidelines email, but introduces today's Tour Guide when one is on duty (falls back to a generic line when null)
    public void sendCheckInGuidelines(String to, String name, String tourGuideName) {
        try {
            String subject = "PAF Aerospace Museum - Entry Confirmed: Visitor Guidelines";
            sendHtml(to, subject, render("check-in", vars("name", name, "tourGuideName", tourGuideName)));
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
        }
    }

    // --- 9. BOOKING CANCELLATION: FACILITY OVERRIDE (HTML) ---
    public void sendOverrideCancellationEmail(String toEmail, String visitorName, String date, String referenceId) {
        try {
            String subject = "PAF Aerospace Museum - Scheduled Visit Cancelled";
            sendHtml(toEmail, subject, render("override-cancelled", vars("name", visitorName == null ? "Visitor" : visitorName,
                    "date", date, "referenceId", referenceId, "bookUrl", bookingPageUrl())));
            System.out.println("📭 Override Cancellation email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Override Cancellation email: " + e.getMessage());
        }
    }

    // --- 10. BOOKING CANCELLATION: EMERGENCY CLOSURE (HTML) ---
    // Unlike the other senders, failures are thrown, so the closure batch can count and report each one
    public void sendEmergencyCancellation(com.paf.aerovisit.model.Visitor visitor, String reason) throws MailDeliveryException {

        String subject = "PAF Aerospace Museum - Museum Closure: Your Visit on " + visitor.getVisitDate() + " Is Cancelled";

        String visitorName = visitor.getName();
        String htmlMsg = render("emergency-cancelled", vars("name", visitorName == null ? "Visitor" : visitorName,
                "date", String.valueOf(visitor.getVisitDate()), "reason", reason,
                "referenceId", visitor.getReferenceId(), "bookUrl", bookingPageUrl()));

        sendHtml(visitor.getEmail(), subject, htmlMsg);
        System.out.println("📭 Emergency Closure email successfully sent to " + visitor.getEmail());
    }

    // --- 11. STAFF DUTY ROSTER (HTML) ---
    // Sent by "Publish Roster & Notify Staff". Like the emergency closure email, failures are thrown so the batch can count them
    public void sendStaffScheduleEmail(String toEmail, String staffName, List<StaffSchedule> assignedSlots) throws MailDeliveryException {

        String subject = "PAF Aerospace Museum - Upcoming Duty Roster";

        DateTimeFormatter dutyDateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);
        List<Map<String, Object>> slots = new ArrayList<>();
        for (StaffSchedule slot : assignedSlots) {
            slots.add(vars("date", slot.getDutyDate() == null ? "" : slot.getDutyDate().format(dutyDateFormat),
                    "shift", slot.getShiftTime()));
        }

        String htmlMsg = render("staff-roster", vars("staffName", staffName == null ? "Officer" : staffName, "slots", slots));

        sendHtml(toEmail, subject, htmlMsg);
        System.out.println("📨 Duty roster email successfully sent to " + toEmail);
    }
}
