package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Emails go out as JSON posts to Brevo's HTTP API (Railway blocks SMTP). A mock Brevo server records each post.
 * Visitor-typed text must arrive in HTML emails as plain text, never as markup.
 * Covers every place where visitor text is put into an HTML email.
 */
class EmailServiceTest {

    // A visitor could type this as their name or feedback to plant a fake button in an email
    private static final String ATTACK = "<a href=\"http://evil.example\">Log in</a>";
    private static final String ESCAPED = "&lt;a href=&quot;http://evil.example&quot;&gt;";

    static final String API_KEY = "test-brevo-key";

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> sent = new ArrayList<>();
    private RestTemplate rest;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        rest = new RestTemplate();
        MockRestServiceServer.bindTo(rest).build()
            .expect(ExpectedCount.manyTimes(), requestTo(EmailService.BREVO_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("api-key", API_KEY))
            .andExpect(request -> sent.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"messageId\":\"<test@brevo>\"}"));
        emailService = new EmailService(rest, "https://museum.example/");
        emailService.brevoApiKey = API_KEY;
    }

    private JsonNode sentEmail() {
        assertThat(sent).as("emails posted to Brevo").hasSize(1);
        return sent.get(0);
    }

    private String sentHtml() {
        String html = sentEmail().path("htmlContent").asText(null);
        assertThat(html).as("HTML body").isNotNull();
        return html;
    }

    private static void assertEscaped(String html) {
        assertThat(html).doesNotContain(ATTACK.toUpperCase()).doesNotContain(ATTACK);
        assertThat(html.toLowerCase()).contains(ESCAPED);
    }

    // --- Brevo transport ---

    @Test
    void otpEmailIsPostedToBrevoWithTheRequiredJsonAndHeaders() {
        List<String> accept = new ArrayList<>();
        List<String> contentType = new ArrayList<>();
        RestTemplate headerRest = new RestTemplate();
        MockRestServiceServer.bindTo(headerRest).build()
            .expect(requestTo(EmailService.BREVO_URL))
            .andExpect(request -> {
                accept.add(request.getHeaders().getFirst("Accept"));
                contentType.add(request.getHeaders().getFirst("Content-Type"));
                sent.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString()));
            })
            .andRespond(withStatus(HttpStatus.CREATED));
        EmailService service = new EmailService(headerRest, "https://museum.example/");
        service.brevoApiKey = API_KEY;

        assertThat(service.sendOtpEmail("v@example.com", "482913", 5)).isTrue();

        assertThat(accept).containsExactly("application/json");
        assertThat(contentType).containsExactly("application/json");
        JsonNode email = sentEmail();
        assertThat(email.path("sender").path("name").asText()).isEqualTo("AeroVisit System");
        assertThat(email.path("sender").path("email").asText()).isEqualTo("pasnormal616@gmail.com");
        assertThat(email.path("to").get(0).path("email").asText()).isEqualTo("v@example.com");
        assertThat(email.path("subject").asText()).isEqualTo("AeroVisit Verification Code");
        assertThat(email.path("htmlContent").asText()).contains(">482913</p>").contains("valid for <strong>5 minutes</strong>");
    }

    @Test
    void otpEmailReturnsFalseWhenBrevoRejectsIt() {
        RestTemplate failing = new RestTemplate();
        MockRestServiceServer.bindTo(failing).build()
            .expect(requestTo(EmailService.BREVO_URL))
            .andRespond(withBadRequest().body("{\"code\":\"invalid_parameter\"}"));
        EmailService service = new EmailService(failing, "https://museum.example/");
        service.brevoApiKey = API_KEY;

        assertThat(service.sendOtpEmail("v@example.com", "482913", 5)).isFalse();
    }

    @Test
    void otpEmailReturnsFalseWithoutAnApiKeyAndNeverCallsBrevo() {
        emailService.brevoApiKey = "";
        assertThat(emailService.sendOtpEmail("v@example.com", "482913", 5)).isFalse();
        assertThat(sent).isEmpty();
    }

    // --- Visitor text escaping ---

    @Test
    void pendingNotificationEscapesName() {
        emailService.sendPendingNotification("v@example.com", ATTACK, "2026-10-01", false, "PAF-A7X92K");
        assertEscaped(sentHtml());
    }

    @Test
    void checkInGuidelinesEscapesName() {
        emailService.sendCheckInGuidelines("v@example.com", ATTACK);
        assertEscaped(sentHtml());
    }

    @Test
    void checkOutFeedbackEscapesName() {
        emailService.sendCheckOutFeedback("v@example.com", ATTACK, "tok-123");
        assertEscaped(sentHtml());
    }

    @Test
    void criticalFeedbackAlertEscapesFeedbackAndName() {
        emailService.sendCriticalFeedbackAlert("director@example.com", ATTACK, ATTACK + " terrible visit", -0.9);
        String html = sentHtml();
        assertThat(html).doesNotContain(ATTACK);
        // Both the feedback text and the visitor name are escaped
        assertThat(html.split(java.util.regex.Pattern.quote(ESCAPED), -1)).hasSize(3);
        assertThat(html).contains(" terrible visit");
    }

    @Test
    void rejectionEmailEscapesName() {
        emailService.sendRejectionEmail("v@example.com", ATTACK, "Blurry ID", null);
        assertEscaped(sentHtml());
    }

    @Test
    void capacityReachedRejectionInvitesTheVisitorToRebook() {
        emailService.sendRejectionEmail("v@example.com", ATTACK, "Capacity Reached", ATTACK);
        String html = sentHtml();
        assertThat(html).contains("The museum has reached maximum security capacity for your requested date. "
                + "Please submit a new booking for a different day.");
        assertThat(html).contains("https://museum.example/'").doesNotContain("PRIMARY REASON").doesNotContain("HOW TO COMPLY");
        assertEscaped(html);
    }

    @Test
    void otherRejectionReasonsStillGetTheStandardEmail() {
        emailService.sendRejectionEmail("v@example.com", "Juan", "Expired ID presented", null);
        assertThat(sentHtml()).contains("PRIMARY REASON").contains("Expired ID presented").doesNotContain("maximum security capacity");
    }

    @Test
    void normalNamesAreUnchanged() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan Dela Cruz");
        assertThat(sentHtml()).contains("Welcome, JUAN DELA CRUZ!");
    }

    @Test
    void checkInGuidelinesIntroducesTourGuide() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", "Lt. Santos");
        assertThat(sentHtml()).contains("Your tour guide for today is <strong>Lt. Santos</strong>.");
    }

    @Test
    void checkInGuidelinesFallsBackWhenNoGuide() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", null);
        assertThat(sentHtml())
            .doesNotContain("Your tour guide for today is")
            .contains("Please approach any of them for assistance.");
    }

    @Test
    void checkInGuidelinesEscapesTourGuideName() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", ATTACK);
        assertEscaped(sentHtml());
    }

    @Test
    void feedbackLinkUsesConfiguredPublicAddressAndToken() {
        assertThat(emailService.buildFeedbackLink("tok-123"))
            .isEqualTo("https://museum.example/leave-feedback?token=tok-123");

        emailService.sendCheckOutFeedback("v@example.com", "Juan", "tok-123");
        assertThat(sentHtml())
            .contains("href='https://museum.example/leave-feedback?token=tok-123'")
            .doesNotContain("ngrok");
    }

    @Test
    void feedbackLinkWorksWithoutTrailingSlash() {
        EmailService local = new EmailService(rest, "http://localhost:8081");
        assertThat(local.buildFeedbackLink("abc")).isEqualTo("http://localhost:8081/leave-feedback?token=abc");
    }

    // --- QR code attachments (Base64) and mandatory exit scan notices ---

    private static final byte[] FAKE_QR = "\u0089PNG fake qr bytes".getBytes(StandardCharsets.ISO_8859_1);

    // The QR emails attach uploads/<qrCodeFileName>; a throwaway file stands in for the generated QR image
    private JsonNode sendWithQrFile(java.util.function.Consumer<String> send) throws Exception {
        java.nio.file.Path uploads = java.nio.file.Paths.get(System.getProperty("user.dir"), "uploads");
        java.nio.file.Files.createDirectories(uploads);
        java.nio.file.Path qr = java.nio.file.Files.createTempFile(uploads, "QR_emailtest_", ".png");
        try {
            java.nio.file.Files.write(qr, FAKE_QR);
            send.accept(qr.getFileName().toString());
            return sentEmail();
        } finally {
            java.nio.file.Files.deleteIfExists(qr);
        }
    }

    private static void assertQrAttached(JsonNode email) {
        JsonNode attachments = email.path("attachment");
        assertThat(attachments.size()).as("attachments").isEqualTo(1);
        assertThat(attachments.get(0).path("name").asText()).isEqualTo("Entry_QRCode.png");
        assertThat(Base64.getDecoder().decode(attachments.get(0).path("content").asText())).isEqualTo(FAKE_QR);
    }

    private String sentApprovalHtml(String visitorName) throws Exception {
        JsonNode email = sendWithQrFile(file -> emailService.sendApprovalEmailWithQR("v@example.com", visitorName, "2026-10-01", file, "PAF-A7X92K"));
        assertQrAttached(email);
        return email.path("htmlContent").asText();
    }

    @Test
    void approvalEmailCarriesTheEntryAndExitSecurityNotice() throws Exception {
        String html = sentApprovalHtml("Juan Dela Cruz");
        assertThat(html)
            .contains("Dear Juan Dela Cruz,")
            .contains("Booking Reference ID: <strong>PAF-A7X92K</strong>")
            .contains("<strong>⚠ SECURITY NOTICE:</strong>")
            .contains("You must present it for scanning upon both ENTRY and EXIT to formally clear your name from the active manifest.");
    }

    @Test
    void approvalEmailEscapesName() throws Exception {
        assertEscaped(sentApprovalHtml(ATTACK));
    }

    @Test
    void reminderEmailIsPlainTextWithTheQrAttached() throws Exception {
        JsonNode email = sendWithQrFile(file -> emailService.sendReminderEmailWithQR("v@example.com", "Juan", "2026-10-01", file));
        assertQrAttached(email);
        assertThat(email.has("htmlContent")).isFalse();
        assertThat(email.path("textContent").asText()).startsWith("Dear Juan,").contains("TOMORROW (2026-10-01)");
    }

    @Test
    void missingQrFileIsLoggedAndNothingIsSent() {
        emailService.sendApprovalEmailWithQR("v@example.com", "Juan", "2026-10-01", "QR_does_not_exist.png", null);
        assertThat(sent).isEmpty();
    }

    @Test
    void checkInGuidelinesRequireAnExitScan() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", "Lt. Santos");
        assertThat(sentHtml())
            .contains("<strong>📤 EXIT SCAN REQUIRED:</strong> Keep your Entry QR Code handy.")
            .contains("you are required to scan this code at the gate when you leave.");
    }

    // --- Staff duty roster ---

    private static com.paf.aerovisit.model.StaffSchedule dutyShift(java.time.LocalDate date, String shiftTime) {
        com.paf.aerovisit.model.StaffSchedule s = new com.paf.aerovisit.model.StaffSchedule();
        s.setDutyDate(date);
        s.setShiftTime(shiftTime);
        return s;
    }

    @Test
    void staffScheduleEmailListsEachDateAndShift() {
        emailService.sendStaffScheduleEmail("reyes@example.com", "Sgt. Reyes", java.util.List.of(
            dutyShift(java.time.LocalDate.of(2026, 10, 5), "Morning (0800H-1200H)"),
            dutyShift(java.time.LocalDate.of(2026, 10, 7), "Full Day (0800H-1700H)")));

        String html = sentHtml();
        assertThat(html)
            .contains("Good day, SGT. REYES!")
            .contains("Here is your upcoming duty roster:")
            .contains(">Date</th>").contains(">Shift Time</th>")
            .contains(">Mon, Oct 5, 2026</td>").contains(">Morning (0800H-1200H)</td>")
            .contains(">Wed, Oct 7, 2026</td>").contains(">Full Day (0800H-1700H)</td>");

        assertThat(sentEmail().path("subject").asText()).isEqualTo("AeroVisit - Your Upcoming Duty Roster");
        assertThat(sentEmail().path("to").get(0).path("email").asText()).isEqualTo("reyes@example.com");
    }

    @Test
    void staffScheduleEmailEscapesNameAndShift() {
        emailService.sendStaffScheduleEmail("reyes@example.com", ATTACK,
            java.util.List.of(dutyShift(java.time.LocalDate.of(2026, 10, 5), "<b>Morning</b>")));
        String html = sentHtml();
        assertEscaped(html);
        assertThat(html).doesNotContain("<b>Morning</b>").contains("&lt;b&gt;Morning&lt;/b&gt;");
    }
}
