package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Visitor-typed text must arrive in HTML emails as plain text, never as markup.
 * Covers every place where visitor text is put into an HTML email.
 */
class EmailServiceTest {

    // A visitor could type this as their name or feedback to plant a fake button in an email
    private static final String ATTACK = "<a href=\"http://evil.example\">Log in</a>";
    private static final String ESCAPED = "&lt;a href=&quot;http://evil.example&quot;&gt;";

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        emailService = new EmailService(mailSender, "https://museum.example/");
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        String html = findHtml(message);
        assertThat(html).as("HTML body").isNotNull();
        return html;
    }

    private static String findHtml(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text && part.isMimeType("text/html")) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String html = findHtml(child);
                if (html != null) {
                    return html;
                }
            }
        }
        return null;
    }

    private static void assertEscaped(String html) {
        assertThat(html).doesNotContain(ATTACK.toUpperCase()).doesNotContain(ATTACK);
        assertThat(html.toLowerCase()).contains(ESCAPED);
    }

    @Test
    void pendingNotificationEscapesName() throws Exception {
        emailService.sendPendingNotification("v@example.com", ATTACK, "2026-10-01", false, "PAF-A7X92K");
        assertEscaped(sentHtml());
    }

    @Test
    void checkInGuidelinesEscapesName() throws Exception {
        emailService.sendCheckInGuidelines("v@example.com", ATTACK);
        assertEscaped(sentHtml());
    }

    @Test
    void checkOutFeedbackEscapesName() throws Exception {
        emailService.sendCheckOutFeedback("v@example.com", ATTACK, "tok-123");
        assertEscaped(sentHtml());
    }

    @Test
    void criticalFeedbackAlertEscapesFeedbackAndName() throws Exception {
        emailService.sendCriticalFeedbackAlert("director@example.com", ATTACK, ATTACK + " terrible visit", -0.9);
        String html = sentHtml();
        assertThat(html).doesNotContain(ATTACK);
        // Both the feedback text and the visitor name are escaped
        assertThat(html.split(java.util.regex.Pattern.quote(ESCAPED), -1)).hasSize(3);
        assertThat(html).contains(" terrible visit");
    }

    @Test
    void rejectionEmailEscapesName() throws Exception {
        emailService.sendRejectionEmail("v@example.com", ATTACK, "Blurry ID", null);
        assertEscaped(sentHtml());
    }

    @Test
    void capacityReachedRejectionInvitesTheVisitorToRebook() throws Exception {
        emailService.sendRejectionEmail("v@example.com", ATTACK, "Capacity Reached", ATTACK);
        String html = sentHtml();
        assertThat(html).contains("The museum has reached maximum security capacity for your requested date. "
                + "Please submit a new booking for a different day.");
        assertThat(html).contains("https://museum.example/'").doesNotContain("PRIMARY REASON").doesNotContain("HOW TO COMPLY");
        assertEscaped(html);
    }

    @Test
    void otherRejectionReasonsStillGetTheStandardEmail() throws Exception {
        emailService.sendRejectionEmail("v@example.com", "Juan", "Expired ID presented", null);
        assertThat(sentHtml()).contains("PRIMARY REASON").contains("Expired ID presented").doesNotContain("maximum security capacity");
    }

    @Test
    void normalNamesAreUnchanged() throws Exception {
        emailService.sendCheckInGuidelines("v@example.com", "Juan Dela Cruz");
        assertThat(sentHtml()).contains("Welcome, JUAN DELA CRUZ!");
    }

    @Test
    void checkInGuidelinesIntroducesTourGuide() throws Exception {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", "Lt. Santos");
        assertThat(sentHtml()).contains("Your tour guide for today is <strong>Lt. Santos</strong>.");
    }

    @Test
    void checkInGuidelinesFallsBackWhenNoGuide() throws Exception {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", null);
        assertThat(sentHtml())
            .doesNotContain("Your tour guide for today is")
            .contains("Please approach any of them for assistance.");
    }

    @Test
    void checkInGuidelinesEscapesTourGuideName() throws Exception {
        emailService.sendCheckInGuidelines("v@example.com", "Juan", ATTACK);
        assertEscaped(sentHtml());
    }

    @Test
    void feedbackLinkUsesConfiguredPublicAddressAndToken() throws Exception {
        assertThat(emailService.buildFeedbackLink("tok-123"))
            .isEqualTo("https://museum.example/leave-feedback?token=tok-123");

        emailService.sendCheckOutFeedback("v@example.com", "Juan", "tok-123");
        assertThat(sentHtml())
            .contains("href='https://museum.example/leave-feedback?token=tok-123'")
            .doesNotContain("ngrok");
    }

    @Test
    void feedbackLinkWorksWithoutTrailingSlash() {
        EmailService local = new EmailService(mailSender, "http://localhost:8081");
        assertThat(local.buildFeedbackLink("abc")).isEqualTo("http://localhost:8081/leave-feedback?token=abc");
    }

    // --- Mandatory exit scan notices ---

    // The approval email attaches uploads/<qrCodeFileName>; a throwaway file stands in for the generated QR image
    private String sentApprovalHtml(String visitorName) throws Exception {
        java.nio.file.Path uploads = java.nio.file.Paths.get(System.getProperty("user.dir"), "uploads");
        java.nio.file.Files.createDirectories(uploads);
        java.nio.file.Path qr = java.nio.file.Files.createTempFile(uploads, "QR_emailtest_", ".png");
        try {
            emailService.sendApprovalEmailWithQR("v@example.com", visitorName, "2026-10-01", qr.getFileName().toString(), "PAF-A7X92K");
            return sentHtml();
        } finally {
            java.nio.file.Files.deleteIfExists(qr);
        }
    }

    @Test
    void approvalEmailCarriesTheEntryAndExitSecurityNotice() throws Exception {
        String html = sentApprovalHtml("Juan Dela Cruz");
        assertThat(html)
            .contains("Dear Juan Dela Cruz,")
            .contains("Booking Reference ID: <strong>PAF-A7X92K</strong>")
            .contains("<strong>⚠ SECURITY NOTICE:</strong>")
            .contains("You must present it for scanning upon both ENTRY and EXIT to formally clear your name from the active manifest.");

        // The QR code is still attached
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        Multipart parts = (Multipart) captor.getValue().getContent();
        boolean hasQr = false;
        for (int i = 0; i < parts.getCount(); i++) {
            hasQr |= "Entry_QRCode.png".equals(parts.getBodyPart(i).getFileName());
        }
        assertThat(hasQr).as("Entry_QRCode.png attachment").isTrue();
    }

    @Test
    void approvalEmailEscapesName() throws Exception {
        assertEscaped(sentApprovalHtml(ATTACK));
    }

    @Test
    void checkInGuidelinesRequireAnExitScan() throws Exception {
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
    void staffScheduleEmailListsEachDateAndShift() throws Exception {
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

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo("AeroVisit - Your Upcoming Duty Roster");
        assertThat(captor.getValue().getAllRecipients()[0].toString()).isEqualTo("reyes@example.com");
    }

    @Test
    void staffScheduleEmailEscapesNameAndShift() throws Exception {
        emailService.sendStaffScheduleEmail("reyes@example.com", ATTACK,
            java.util.List.of(dutyShift(java.time.LocalDate.of(2026, 10, 5), "<b>Morning</b>")));
        String html = sentHtml();
        assertEscaped(html);
        assertThat(html).doesNotContain("<b>Morning</b>").contains("&lt;b&gt;Morning&lt;/b&gt;");
    }
}
