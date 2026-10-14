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
        assertThat(email.path("subject").asText()).isEqualTo("PAF Aerospace Museum - Email Verification Code");
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
        assertThat(html).contains("the museum has reached its maximum security capacity for your requested date. "
                + "We kindly ask that you submit a new booking for a different day.");
        assertThat(html).contains("href=\"https://museum.example/\"").doesNotContain("<strong>Reason:</strong>");
        assertThat(sentEmail().path("subject").asText()).isEqualTo("PAF Aerospace Museum - Requested Date Fully Booked");
        assertEscaped(html);
    }

    @Test
    void otherRejectionReasonsStillGetTheStandardEmail() {
        emailService.sendRejectionEmail("v@example.com", "Juan", "Expired ID presented", "Bring a valid ID");
        assertThat(sentHtml()).contains("<strong>Reason:</strong>").contains("Expired ID presented")
            .contains("<strong>Administrative notes:</strong>").contains("Bring a valid ID")
            .doesNotContain("maximum security capacity");
        assertThat(sentEmail().path("subject").asText()).isEqualTo("PAF Aerospace Museum - Visit Request Not Approved");
    }

    @Test
    void rejectionReasonAndNotesAreEscapedToo() {
        emailService.sendRejectionEmail("v@example.com", "Juan", ATTACK, ATTACK);
        String html = sentHtml();
        assertThat(html).doesNotContain(ATTACK);
        assertThat(html.split(java.util.regex.Pattern.quote(ESCAPED), -1)).hasSize(3); // reason + notes
    }

    @Test
    void namesAppearAsEnteredInAFormalSalutation() {
        emailService.sendCheckInGuidelines("v@example.com", "Juan Dela Cruz");
        assertThat(sentHtml()).contains("Dear <span>Juan Dela Cruz</span>,").doesNotContain("JUAN DELA CRUZ");
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
            .contains("href=\"https://museum.example/leave-feedback?token=tok-123\"")
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
            .contains("Dear <span>Juan Dela Cruz</span>,")
            .contains("Booking Reference ID: <strong>PAF-A7X92K</strong>")
            .contains("<strong>Security Notice:</strong>")
            .contains("It must be presented for scanning upon both entry and exit to formally clear your name from the active visitor manifest.");
        assertThat(sent.get(0).path("subject").asText()).isEqualTo("PAF Aerospace Museum - Visit Approved: Entry Pass Enclosed");
    }

    @Test
    void approvalEmailEscapesName() throws Exception {
        assertEscaped(sentApprovalHtml(ATTACK));
    }

    @Test
    void reminderEmailIsHtmlWithTheQrAttached() throws Exception {
        JsonNode email = sendWithQrFile(file -> emailService.sendReminderEmailWithQR("v@example.com", "Juan", "2026-10-01", file));
        assertQrAttached(email);
        assertThat(email.has("textContent")).isFalse();
        assertThat(email.path("htmlContent").asText()).contains("Dear <span>Juan</span>,")
            .contains("is tomorrow, <strong style=\"white-space:nowrap;\">2026-10-01</strong>.");
        assertThat(email.path("subject").asText()).isEqualTo("PAF Aerospace Museum - Visit Reminder for Tomorrow");
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
            .contains("<strong>Exit scan required:</strong> Please keep your Entry QR Code available.")
            .contains("You are required to scan it at the gate when you leave");
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
            .contains("Good day, <span>Sgt. Reyes</span>.")
            .contains("Please find below your upcoming duty roster:")
            .contains(">Date</th>").contains(">Shift Time</th>")
            .contains(">Mon, Oct 5, 2026</td>").contains(">Morning (0800H-1200H)</td>")
            .contains(">Wed, Oct 7, 2026</td>").contains(">Full Day (0800H-1700H)</td>");

        assertThat(sentEmail().path("subject").asText()).isEqualTo("PAF Aerospace Museum - Upcoming Duty Roster");
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

    // --- One formal design for every email: header, signature, HTML, no emoji anywhere ---

    private static final java.util.regex.Pattern EMOJI = java.util.regex.Pattern.compile(
            "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]");
    private static final String HEADER = "PHILIPPINE AIR FORCE AEROSPACE MUSEUM - VISITOR MANAGEMENT SYSTEM";
    private static final String SIGNATURE = "Respectfully,<br>Office of the Director<br>Philippine Air Force Aerospace Museum<br>Villamor Air Base, Pasay City";

    @Test
    void everyEmailUsesTheFormalLayoutWithoutEmoji() throws Exception {
        com.paf.aerovisit.model.Visitor closed = new com.paf.aerovisit.model.Visitor();
        closed.setName("Juan"); closed.setEmail("v@example.com"); closed.setVisitDate(java.time.LocalDate.of(2026, 10, 12));
        List<Runnable> visitorEmails = List.of(
            () -> emailService.sendOtpEmail("v@example.com", "482913", 5),
            () -> emailService.sendPendingNotification("v@example.com", "Juan", "2026-10-01", false, "PAF-A7X92K"),
            () -> emailService.sendPendingNotification("v@example.com", "Juan", "2026-10-01", true),
            () -> emailService.sendCheckInGuidelines("v@example.com", "Juan", "Lt. Santos"),
            () -> emailService.sendCheckOutFeedback("v@example.com", "Juan", "tok-1"),
            () -> emailService.sendRejectionEmail("v@example.com", "Juan", "Capacity Reached", null),
            () -> emailService.sendRejectionEmail("v@example.com", "Juan", "Expired ID presented", null),
            () -> emailService.sendOverrideCancellationEmail("v@example.com", "Juan", "2026-10-01", "PAF-A7X92K"),
            () -> emailService.sendEmergencyCancellation(closed, "Typhoon Signal No. 3"),
            () -> emailService.sendStaffScheduleEmail("s@example.com", "Sgt. Reyes",
                    List.of(dutyShift(java.time.LocalDate.of(2026, 10, 5), "Morning (0800H-1200H)"))));
        for (Runnable send : visitorEmails) send.run();
        List<JsonNode> formal = new ArrayList<>(sent);
        sent.clear();
        sendWithQrFile(file -> emailService.sendApprovalEmailWithQR("v@example.com", "Juan", "2026-10-01", file, "PAF-A7X92K"));
        formal.add(sent.remove(0));
        sendWithQrFile(file -> emailService.sendReminderEmailWithQR("v@example.com", "Juan", "2026-10-01", file));
        formal.add(sent.remove(0));
        emailService.sendDirectorDailyReport("d@example.com", "2026-10-01", 40, 35, 5);
        emailService.sendCriticalFeedbackAlert("d@example.com", "Juan", "Closed exhibit", -0.9);
        List<JsonNode> internal = new ArrayList<>(sent);

        assertThat(formal).hasSize(12);
        assertThat(internal).hasSize(2);
        for (JsonNode email : concat(formal, internal)) {
            String subject = email.path("subject").asText();
            String html = email.path("htmlContent").asText(null);
            assertThat(html).as(subject).isNotNull().contains(HEADER);
            assertThat(email.has("textContent")).as(subject).isFalse();
            assertThat(subject).as("subject").startsWith("PAF Aerospace Museum - ");
            assertThat(EMOJI.matcher(subject).find()).as("emoji in subject " + subject).isFalse();
            assertThat(EMOJI.matcher(html).find()).as("emoji in body of " + subject).isFalse();
        }
        for (JsonNode email : formal) {
            assertThat(email.path("htmlContent").asText()).as(email.path("subject").asText()).contains(SIGNATURE);
        }
        // Sent TO the Director: a system signature instead of the office signing to itself
        for (JsonNode email : internal) {
            assertThat(email.path("htmlContent").asText()).as(email.path("subject").asText())
                .doesNotContain(SIGNATURE).contains("AeroVisit Visitor Management System<br>(automated notice)");
        }
    }

    private static List<JsonNode> concat(List<JsonNode> a, List<JsonNode> b) {
        List<JsonNode> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Test
    void theDailySummaryIsHtmlWithTheThreeFigures() {
        emailService.sendDirectorDailyReport("d@example.com", "2026-10-01", 40, 35, 5);
        JsonNode email = sentEmail();
        assertThat(email.path("subject").asText()).isEqualTo("PAF Aerospace Museum - Daily Visitor Summary - 2026-10-01");
        assertThat(email.path("htmlContent").asText()).contains(">40</td>").contains(">35</td>").contains(">5</td>");
    }

    @Test
    void aMissingTemplateIsAMailFailureNotACrash() {
        // An engine looking in a folder without the email templates: every render fails
        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver nowhere = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        nowhere.setPrefix("no-such-folder/");
        nowhere.setSuffix(".html");
        org.thymeleaf.spring6.SpringTemplateEngine missing = new org.thymeleaf.spring6.SpringTemplateEngine();
        missing.setTemplateResolver(nowhere);
        EmailService service = new EmailService(rest, "https://museum.example/", missing);
        service.brevoApiKey = API_KEY;

        assertThat(service.sendOtpEmail("v@example.com", "1", 5)).isFalse();         // same as a Brevo failure
        service.sendPendingNotification("v@example.com", "Juan", "2026-10-01", false); // caught and logged, never thrown
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sendStaffScheduleEmail("s@example.com", "Reyes", List.of()))
            .isInstanceOf(EmailService.MailDeliveryException.class);              // batch senders still count it as a failure
        assertThat(sent).isEmpty();
    }
}
