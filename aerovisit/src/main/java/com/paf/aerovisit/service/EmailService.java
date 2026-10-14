package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.paf.aerovisit.model.StaffSchedule;

@Service
public class EmailService {

    // 📮 Every email goes out through Brevo's HTTP API on port 443, because Railway blocks outgoing SMTP ports
    static final String BREVO_URL = "https://api.brevo.com/v3/smtp/email";
    static final String SENDER_NAME = "AeroVisit System";

    // ⏱️ Never let a slow or unreachable Brevo hang a booking/approval request
    static final int CONNECT_TIMEOUT_MS = 5_000;
    static final int READ_TIMEOUT_MS = 10_000;

    private final RestTemplate brevo;

    // Public address of this site, used to build links in emails (set PUBLIC_BASE_URL when running behind ngrok or a domain)
    private final String publicBaseUrl;

    // Sender of every email (aerovisit.mail.from / MAIL_USERNAME); must be a verified sender in Brevo
    @Value("${aerovisit.mail.from:" + DEFAULT_FROM + "}")
    String fromAddress = DEFAULT_FROM;
    static final String DEFAULT_FROM = "pasnormal616@gmail.com";

    // 🔒 Brevo API key: the BREVO_API_KEY environment variable (Railway) or the local .env file, never source code
    @Value("${BREVO_API_KEY:}")
    String brevoApiKey = "";

    // Renders the HTML email templates (templates/email/*.html); Spring Boot's Thymeleaf engine in production
    private final ITemplateEngine templates;

    @Autowired
    EmailService(@Value("${aerovisit.public-base-url:http://localhost:8081}") String publicBaseUrl, ITemplateEngine templates) {
        this(createBrevoClient(), publicBaseUrl, templates);
    }

    // Tests pass a RestTemplate bound to a mock server; the email templates are read from the classpath as in production
    EmailService(RestTemplate brevo, String publicBaseUrl) {
        this(brevo, publicBaseUrl, standaloneTemplates());
    }

    EmailService(RestTemplate brevo, String publicBaseUrl, ITemplateEngine templates) {
        this.brevo = brevo;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.templates = templates;
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

    private static RestTemplate createBrevoClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    /** An email could not be delivered: missing API key, unreadable attachment, Brevo error, or network failure. */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message) { super(message); }
        public MailDeliveryException(String message, Throwable cause) { super(message, cause); }
    }

    // One QR code file from uploads/, sent to Brevo as a Base64 attachment named Entry_QRCode.png
    private static Map<String, String> qrAttachment(String qrCodeFileName) {
        Path qrPath = Paths.get(System.getProperty("user.dir"), "uploads", qrCodeFileName);
        try {
            return Map.of("name", "Entry_QRCode.png", "content", Base64.getEncoder().encodeToString(Files.readAllBytes(qrPath)));
        } catch (IOException e) {
            throw new MailDeliveryException("QR code attachment unreadable: " + qrPath, e);
        }
    }

    // Sends one email through Brevo. Exactly one of htmlContent / textContent is set; attachment may be null.
    private void postToBrevo(String toEmail, String subject, String htmlContent, String textContent, Map<String, String> attachment) {
        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            throw new MailDeliveryException("BREVO_API_KEY is not set");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", brevoApiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("name", SENDER_NAME, "email", fromAddress));
        body.put("to", List.of(Map.of("email", toEmail)));
        body.put("subject", subject);
        if (htmlContent != null) body.put("htmlContent", htmlContent);
        if (textContent != null) body.put("textContent", textContent);
        if (attachment != null) body.put("attachment", List.of(attachment));

        try {
            ResponseEntity<String> response = brevo.postForEntity(BREVO_URL, new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) { // Brevo answers 201 Created
                throw new MailDeliveryException("Brevo answered " + response.getStatusCode());
            }
        } catch (RestClientException e) { // 4xx/5xx from Brevo, DNS failure, timeout
            throw new MailDeliveryException("Brevo request failed: " + e.getMessage(), e);
        }
    }

    private void sendHtml(String toEmail, String subject, String html) {
        postToBrevo(toEmail, subject, html, null, null);
    }

    // 🖋 Renders templates/email/<name>.html (shared layout: email/layout.html). Thymeleaf escapes every th:text value,
    // so visitor-typed text (names, feedback, notes) can never inject HTML. A template problem becomes a
    // MailDeliveryException, so every sender's existing catch handles it exactly like a Brevo failure: a booking,
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
            postToBrevo(toEmail, subject, emailContent, null, qrAttachment(qrCodeFileName));
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
            postToBrevo(toEmail, subject, emailContent, null, qrAttachment(qrCodeFileName));
            System.out.println("⏰ Auto-Reminder successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send reminder email: " + e.getMessage());
        }
    }

    // --- 3. THE END-OF-DAY DIRECTOR REPORT ---
    public void sendDirectorDailyReport(String directorEmail, String date, long expected, long checkedIn, long noShows) {
        try {
            String subject = "PAF Aerospace Museum - Daily Visitor Summary - " + date;
            String emailContent = render("daily-report", vars("date", date, "expected", expected, "checkedIn", checkedIn,
                    "noShows", noShows, "internal", true));
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
