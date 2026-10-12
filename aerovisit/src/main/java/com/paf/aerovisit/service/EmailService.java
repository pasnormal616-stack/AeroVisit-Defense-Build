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
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
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

    @Autowired
    EmailService(@Value("${aerovisit.public-base-url:http://localhost:8081}") String publicBaseUrl) {
        this(createBrevoClient(), publicBaseUrl);
    }

    // Tests pass a RestTemplate bound to a mock server
    EmailService(RestTemplate brevo, String publicBaseUrl) {
        this.brevo = brevo;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
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

    // 🔒 Visitor-typed text goes into HTML emails, so it is escaped first (a name like "<a href=...>" shows as text, not a link)
    private static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }

    // Link visitors open to leave feedback after check-out
    String buildFeedbackLink(String feedbackToken) {
        return publicBaseUrl + "/leave-feedback?token=" + feedbackToken;
    }

    // 📧 Booking-form verification code (OtpService). Returns false instead of throwing, so the form can say "try again".
    public boolean sendOtpEmail(String recipientEmail, String otpCode, long ttlMinutes) {
        String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                + "</div>"
                + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                + "<p>Use this code to verify your email on the museum booking form:</p>"
                + "<p style='text-align: center; font-size: 2em; font-weight: bold; letter-spacing: 8px; color: #00205B; margin: 25px 0;'>" + escape(otpCode) + "</p>"
                + "<p>The code is valid for <strong>" + ttlMinutes + " minutes</strong>. Never share it with anyone; museum staff will never ask for it.</p>"
                + "<p style='color: #777; font-size: 0.9em;'>If you did not request this code, you can ignore this email.</p>"
                + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>AeroVisit Automated System</p>"
                + "</div></div>";
        try {
            sendHtml(recipientEmail, "AeroVisit Verification Code", htmlMsg);
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
            
            String subject = isMasterBooking ? "AeroVisit - Master Booking Request Pending" : "AeroVisit - Visit Request Received";
            
            String customMessage = isMasterBooking ? 
                "Because this is a large Master Booking (Educational Tour), it has bypassed standard limits and been forwarded directly to the Museum Director for special review. Your current status is <strong>PENDING</strong>." : 
                "Your current status is <strong>PENDING</strong>. Please wait for the administrator to review your request.";

            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                    + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                    + "<h3 style='color: #00205B;'>Hello, " + escape(name.toUpperCase()) + "!</h3>"
                    + "<p>Your visit request for <strong>" + date + "</strong> has been successfully received by the system.</p>"
                    + (referenceId != null
                        ? "<p>Your Booking Reference ID is <strong style='font-size: 1.2em; letter-spacing: 1px;'>" + referenceId + "</strong>. Keep it to manage your booking.</p>"
                        : "")
                    + "<div style='padding: 15px; background-color: #fff3cd; border-left: 5px solid #ffc107; margin: 20px 0; color: #856404;'>"
                    + "<p style='margin: 0;'>" + customMessage + "</p>"
                    + "</div>"
                    + "<p>You will receive an official Entry QR Code in a separate email once your tour is APPROVED.</p>"
                    + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>AeroVisit Automated System</p>"
                    + "</div></div>";

            sendHtml(toEmail, subject, htmlMsg);
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
            String subject = "PAF Aerospace Museum - Visit Approved";

            String emailContent = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; color: #333;'>"
                    + "<p>Dear " + escape(visitorName) + ",</p>"
                    + "<p>Your visit scheduled for <strong>" + escape(date) + "</strong> has been officially <strong>APPROVED</strong>.</p>"
                    + (referenceId != null ? "<p>Booking Reference ID: <strong>" + escape(referenceId) + "</strong></p>" : "")
                    + "<p>Please find your Entry QR Code attached to this email. "
                    + "You must present this QR code to the guard upon arrival at the gate.</p>"
                    + "<div style='padding: 15px; margin: 20px 0; background-color: #fff3cd; border: 2px solid #dc3545; border-left: 8px solid #dc3545; border-radius: 6px;'>"
                    + "<p style='margin: 0; color: #842029;'><strong>⚠ SECURITY NOTICE:</strong> <strong>This QR code is your official base pass. "
                    + "You must present it for scanning upon both ENTRY and EXIT to formally clear your name from the active manifest.</strong></p>"
                    + "</div>"
                    + "<p>Safe travels,<br>PAF Aerospace Museum Security Team</p>"
                    + "</div>";

            postToBrevo(toEmail, subject, emailContent, null, qrAttachment(qrCodeFileName));
            System.out.println("✅ Approval email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send email: " + e.getMessage());
        }
    }

    // --- 2. THE AUTOMATED REMINDER EMAIL ---
    public void sendReminderEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName) {
        try {
            String subject = "REMINDER: Your PAF Aerospace Museum Visit is Tomorrow!";

            String emailContent = "Dear " + visitorName + ",\n\n"
                    + "This is an automated reminder that your scheduled visit to the PAF Aerospace Museum is TOMORROW (" + date + ").\n\n"
                    + "Your Entry QR Code is attached to this email. Please have it ready on your phone with your screen brightness turned up to scan at the gate.\n\n"
                    + "See you tomorrow,\n"
                    + "PAF Aerospace Museum Security Team";

            postToBrevo(toEmail, subject, null, emailContent, qrAttachment(qrCodeFileName));
            System.out.println("⏰ Auto-Reminder successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send reminder email: " + e.getMessage());
        }
    }

    // --- 3. THE END-OF-DAY DIRECTOR REPORT ---
    public void sendDirectorDailyReport(String directorEmail, String date, long expected, long checkedIn, long noShows) {
        try {
            String subject = "📊 AeroVisit Daily Summary: " + date;

            String emailContent = "Good Evening Director,\n\n"
                    + "Here is your automated AeroVisit Daily Security & Attendance Report for " + date + ":\n\n"
                    + "📋 Total Expected Visitors: " + expected + "\n"
                    + "✅ Total Successfully Checked-In: " + checkedIn + "\n"
                    + "❌ Total No-Shows / Pending / Rejected: " + noShows + "\n\n"
                    + "All system operations are running nominally. You can view the full details on your Admin Dashboard.\n\n"
                    + "System Generated by AeroVisit Engine";

            postToBrevo(directorEmail, subject, null, emailContent, null);
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
            String subject = "🟢 Entry Authorized: PAF Museum Guidelines";
            
            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                    + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                    + "<h3 style='color: #00205B;'>Welcome, " + escape(name.toUpperCase()) + "!</h3>"
                    + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #D4AF37; margin: 20px 0;'>"
                    + (tourGuideName != null && !tourGuideName.isBlank()
                        ? "<p style='margin: 0;'>Your tour guide for today is <strong>" + escape(tourGuideName) + "</strong>. Please approach them for assistance.</p>"
                        : "<p style='margin: 0;'>We're glad to have you with us today! Our Tour Guides and museum personnel are stationed throughout the gallery. Please approach any of them for assistance.</p>")
                    + "</div>"
                    + "<p>Your QR code has been successfully scanned and your entry is officially logged. For your safety and the preservation of our military history, please adhere to the following protocols:</p>"
                    + "<h4 style='color: #28a745; border-bottom: 2px solid #28a745; padding-bottom: 5px;'>🟢 DO's (Authorized Actions)</h4>"
                    + "<ul style='line-height: 1.6;'>"
                    + "<li><strong>Take Photos:</strong> Photography is highly encouraged in all public exhibition areas.</li>"
                    + "<li><strong>Ask Questions:</strong> Our Tour Guides and personnel are here to help.</li>"
                    + "<li><strong>Stay in Zones:</strong> Remain within the designated blue-line civilian pathways.</li>"
                    + "</ul>"
                    + "<h4 style='color: #dc3545; border-bottom: 2px solid #dc3545; padding-bottom: 5px;'>🔴 DON'Ts (Restricted Actions)</h4>"
                    + "<ul style='line-height: 1.6;'>"
                    + "<li><strong>No Touching:</strong> Do not touch the aircraft, glass displays, or historical artifacts.</li>"
                    + "<li><strong>No Food/Drinks:</strong> Consuming food and beverages inside the gallery is strictly prohibited.</li>"
                    + "<li><strong>No Crossing Barriers:</strong> Do not cross velvet ropes or restricted barricades.</li>"
                    + "</ul>"
                    + "<div style='padding: 15px; margin: 25px 0 0 0; background-color: #fff; border: 2px solid #D4AF37; border-left: 8px solid #00205B; border-radius: 6px;'>"
                    + "<p style='margin: 0; color: #00205B;'><strong>📤 EXIT SCAN REQUIRED:</strong> Keep your Entry QR Code handy. "
                    + "To formally clear your departure and receive your post-visit feedback form, you are required to scan this code at the gate when you leave.</p>"
                    + "</div>"
                    + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Enjoy your tour through Philippine Air Force History!</p>"
                    + "</div></div>";

            sendHtml(to, subject, htmlMsg);
            System.out.println("🟢 Check-In Guidelines successfully sent to " + to);
            
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Check-In email: " + e.getMessage());
        }
    }

    // --- 5. THE CHECK-OUT FEEDBACK EMAIL (HTML) ---
    // 🔒 Uses the visitor's secure token (not the guessable numeric ID) so only the real visitor can open the form
    public void sendCheckOutFeedback(String to, String name, String feedbackToken) {
        try {
            String subject = "👋 Departure Logged: Please Submit Your After-Action Report";
            
            String feedbackLink = buildFeedbackLink(feedbackToken);
            
            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                    + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333; text-align: center;'>"
                    + "<h3 style='color: #00205B;'>Thank you for visiting, " + escape(name.toUpperCase()) + "!</h3>"
                    + "<p>Your departure from the base has been securely logged.</p>"
                    + "<p>The Museum Director requests your <strong>After-Action Report</strong>. Your feedback helps us improve the experience for future civilians and military personnel.</p>"
                    + "<a href='" + feedbackLink + "' style='display: inline-block; margin-top: 20px; padding: 15px 30px; background-color: #D4AF37; color: #00205B; text-decoration: none; font-weight: bold; font-size: 16px; border-radius: 5px;'>📋 SUBMIT OFFICIAL FEEDBACK</a>"
                    + "</div></div>";

            sendHtml(to, subject, htmlMsg);
            System.out.println("👋 Check-Out Feedback successfully sent to " + to);
            
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Check-Out email: " + e.getMessage());
        }
    }

    // --- 7. NLP CRITICAL FEEDBACK ALERT (AI TRIGGERED) ---
    public void sendCriticalFeedbackAlert(String directorEmail, String visitorName, String feedbackText, double sentimentScore) {
        try {
            String subject = "🚨 AI ALERT: Critical Negative Feedback Detected";
            
            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #dc3545; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #dc3545; color: white; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>🚨 COMMAND CENTER ALERT</h2>"
                    + "<p style='margin: 5px 0 0 0;'>NLP Sentiment Analysis Engine</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                    + "<h3 style='color: #dc3545;'>Director, immediate attention requested.</h3>"
                    + "<p>The AI system has intercepted a highly negative After-Action Report from a recent visitor.</p>"
                    + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #dc3545; margin: 20px 0; font-style: italic;'>"
                    + "\"" + escape(feedbackText) + "\""
                    + "</div>"
                    + "<p><strong>Visitor:</strong> " + escape(visitorName) + "</p>"
                    + "<p><strong>AI Severity Score:</strong> " + sentimentScore + " (Negative)</p>"
                    + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Please address this operational issue immediately.</p>"
                    + "</div></div>";

            sendHtml(directorEmail, subject, htmlMsg);
            System.out.println("🚨 NLP Critical Alert successfully dispatched to Director!");
            
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send NLP Alert email: " + e.getMessage());
        }
    }

    // --- 8. 🔥 NEW: REGISTRATION REJECTION WITH COMPLIANCE NOTES (HTML) ---
    public void sendRejectionEmail(String toEmail, String visitorName, String presetReason, String additionalNotes) {
        try {

            // 📅 Capacity rejection: nothing is wrong with the registration, so the visitor is simply invited to rebook
            if (presetReason != null && presetReason.contains("Capacity Reached")) {
                String subject = "AeroVisit - Requested Date Fully Booked";

                String capacityMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                        + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                        + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                        + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                        + "</div>"
                        + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                        + "<h3 style='color: #00205B;'>Dear " + escape(visitorName == null ? "Visitor" : visitorName.toUpperCase()) + ",</h3>"
                        + "<p>Thank you for your interest in visiting the PAF Aerospace Museum.</p>"
                        + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #D4AF37; margin: 20px 0;'>"
                        + "<p style='margin: 0;'>The museum has reached maximum security capacity for your requested date. "
                        + "Please submit a new booking for a different day.</p>"
                        + "</div>"
                        + (additionalNotes != null && !additionalNotes.trim().isEmpty()
                            ? "<p><strong>Note from the museum:</strong><br>" + escape(additionalNotes) + "</p>"
                            : "")
                        + "<p>There is no issue with your registration details. We apologize for the inconvenience and look forward to welcoming you on another date.</p>"
                        + "<p style='text-align: center;'><a href='" + publicBaseUrl + "/' style='display: inline-block; padding: 15px 30px; background-color: #D4AF37; color: #00205B; text-decoration: none; font-weight: bold; border-radius: 5px;'>BOOK A NEW VISIT</a></p>"
                        + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Thank you for your understanding,<br>PAF Aerospace Museum Administration</p>"
                        + "</div></div>";

                sendHtml(toEmail, subject, capacityMsg);
                System.out.println("📅 Capacity Reached (rebook) email successfully sent to " + toEmail);
                return;
            }

            String subject = "⚠️ AeroVisit Registration - Action Required";

            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #dc3545; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #dc3545; color: white; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>⚠️ REGISTRATION UPDATE</h2>"
                    + "<p style='margin: 5px 0 0 0;'>Philippine Air Force Aerospace Museum</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                    + "<h3 style='color: #00205B;'>Dear " + escape(visitorName.toUpperCase()) + ",</h3>"
                    + "<p>Your visit request to the PAF Aerospace Museum could not be approved at this time for the following reason:</p>"
                    + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #dc3545; margin: 20px 0;'>";
            
            // Add the Preset Reason
            htmlMsg += "<p style='margin: 0;'><strong>PRIMARY REASON:</strong><br>" + presetReason + "</p>";
            
            // Dynamically add the Notes section only if the admin typed something
            if (additionalNotes != null && !additionalNotes.trim().isEmpty()) {
                htmlMsg += "<p style='margin: 15px 0 0 0;'><strong>ADMINISTRATIVE NOTES:</strong><br>" + additionalNotes + "</p>";
            }
            
            htmlMsg += "</div>"
                    + "<h4 style='color: #00205B; border-bottom: 2px solid #00205B; padding-bottom: 5px;'>HOW TO COMPLY</h4>"
                    + "<p>Please submit a new registration request and ensure the above issue is resolved prior to submission.</p>"
                    + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Thank you,<br>AeroVisit Security Command</p>"
                    + "</div></div>";

            sendHtml(toEmail, subject, htmlMsg);
            System.out.println("⚠️ Rejection Compliance email successfully sent to " + toEmail);
            
        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Rejection email: " + e.getMessage());
        }
    }

    // --- 9. BOOKING CANCELLATION: FACILITY OVERRIDE (HTML) ---
    public void sendOverrideCancellationEmail(String toEmail, String visitorName, String date, String referenceId) {
        try {
            String subject = "AeroVisit - Your Scheduled Visit Has Been Cancelled";

            String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                    + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                    + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                    + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                    + "</div>"
                    + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                    + "<h3 style='color: #00205B;'>Dear " + escape(visitorName == null ? "Visitor" : visitorName.toUpperCase()) + ",</h3>"
                    + "<p>We regret to inform you that your approved visit on <strong>" + escape(date) + "</strong> has been cancelled. "
                    + "Due to unforeseen operational requirements, the museum will be reserved for a private facility event on that date and will be closed to general visitors.</p>"
                    + (referenceId != null ? "<p>Cancelled Booking Reference ID: <strong>" + escape(referenceId) + "</strong></p>" : "")
                    + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #D4AF37; margin: 20px 0;'>"
                    + "<p style='margin: 0;'>Your previous Entry QR Code is no longer valid. We sincerely apologize for the inconvenience and warmly invite you to book a new visit on another date of your choice.</p>"
                    + "</div>"
                    + "<p style='text-align: center;'><a href='" + publicBaseUrl + "/' style='display: inline-block; padding: 15px 30px; background-color: #D4AF37; color: #00205B; text-decoration: none; font-weight: bold; border-radius: 5px;'>BOOK A NEW VISIT</a></p>"
                    + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Thank you for your understanding,<br>PAF Aerospace Museum Administration</p>"
                    + "</div></div>";

            sendHtml(toEmail, subject, htmlMsg);
            System.out.println("📭 Override Cancellation email successfully sent to " + toEmail);

        } catch (MailDeliveryException e) {
            System.err.println("❌ Failed to send Override Cancellation email: " + e.getMessage());
        }
    }

    // --- 10. BOOKING CANCELLATION: EMERGENCY CLOSURE (HTML) ---
    // Unlike the other senders, failures are thrown, so the closure batch can count and report each one
    public void sendEmergencyCancellation(com.paf.aerovisit.model.Visitor visitor, String reason) throws MailDeliveryException {

        String subject = "AeroVisit - Museum Closed: Your Visit on " + visitor.getVisitDate() + " Is Cancelled";

        String visitorName = visitor.getName();
        String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #C41F1E; border-radius: 10px; overflow: hidden;'>"
                + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                + "</div>"
                + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                + "<h3 style='color: #00205B;'>Dear " + escape(visitorName == null ? "Visitor" : visitorName.toUpperCase()) + ",</h3>"
                + "<p>We regret to inform you that the museum will be <strong>closed on " + escape(String.valueOf(visitor.getVisitDate())) + "</strong> due to an emergency, and your visit on that date has been cancelled.</p>"
                + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #C41F1E; margin: 20px 0;'>"
                + "<p style='margin: 0;'><strong>Reason for closure:</strong> " + escape(reason) + "</p>"
                + "</div>"
                + (visitor.getReferenceId() != null ? "<p>Cancelled Booking Reference ID: <strong>" + escape(visitor.getReferenceId()) + "</strong></p>" : "")
                + "<p>Any Entry QR Code issued for this visit is no longer valid. For your safety, please do not travel to the museum on this date. You are welcome to book a new visit on another date of your choice.</p>"
                + "<p style='text-align: center;'><a href='" + publicBaseUrl + "/' style='display: inline-block; padding: 15px 30px; background-color: #D4AF37; color: #00205B; text-decoration: none; font-weight: bold; border-radius: 5px;'>BOOK A NEW VISIT</a></p>"
                + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>Thank you for your understanding,<br>PAF Aerospace Museum Administration</p>"
                + "</div></div>";

        sendHtml(visitor.getEmail(), subject, htmlMsg);
        System.out.println("📭 Emergency Closure email successfully sent to " + visitor.getEmail());
    }

    // --- 11. STAFF DUTY ROSTER (HTML) ---
    // Sent by "Publish Roster & Notify Staff". Like the emergency closure email, failures are thrown so the batch can count them
    public void sendStaffScheduleEmail(String toEmail, String staffName, List<StaffSchedule> assignedSlots) throws MailDeliveryException {

        String subject = "AeroVisit - Your Upcoming Duty Roster";

        DateTimeFormatter dutyDateFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);
        StringBuilder rows = new StringBuilder();
        for (StaffSchedule slot : assignedSlots) {
            rows.append("<tr>")
                .append("<td style='padding: 10px; border: 1px solid #ccc; background-color: #fff;'>")
                .append(slot.getDutyDate() == null ? "" : slot.getDutyDate().format(dutyDateFormat)).append("</td>")
                .append("<td style='padding: 10px; border: 1px solid #ccc; background-color: #fff;'>")
                .append(escape(slot.getShiftTime())).append("</td>")
                .append("</tr>");
        }

        String htmlMsg = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: auto; border: 2px solid #00205B; border-radius: 10px; overflow: hidden;'>"
                + "<div style='background-color: #00205B; color: #D4AF37; padding: 20px; text-align: center;'>"
                + "<h2 style='margin: 0;'>🦅 AEROVISIT COMMAND</h2>"
                + "<p style='margin: 5px 0 0 0; color: white;'>Philippine Air Force Aerospace Museum</p>"
                + "</div>"
                + "<div style='padding: 20px; background-color: #f4f6f9; color: #333;'>"
                + "<h3 style='color: #00205B;'>Good day, " + escape(staffName == null ? "Officer" : staffName.toUpperCase()) + "!</h3>"
                + "<p>Here is your upcoming duty roster:</p>"
                + "<table style='width: 100%; border-collapse: collapse; margin: 15px 0;'>"
                + "<tr>"
                + "<th style='padding: 10px; border: 1px solid #00205B; background-color: #00205B; color: #D4AF37; text-align: left;'>Date</th>"
                + "<th style='padding: 10px; border: 1px solid #00205B; background-color: #00205B; color: #D4AF37; text-align: left;'>Shift Time</th>"
                + "</tr>"
                + rows
                + "</table>"
                + "<div style='padding: 15px; background-color: #fff; border-left: 5px solid #D4AF37; margin: 20px 0;'>"
                + "<p style='margin: 0;'>For any change to this roster, please coordinate with the Museum Director.</p>"
                + "</div>"
                + "<p style='text-align: center; font-weight: bold; margin-top: 30px;'>AeroVisit Automated System</p>"
                + "</div></div>";

        sendHtml(toEmail, subject, htmlMsg);
        System.out.println("📨 Duty roster email successfully sent to " + toEmail);
    }
}