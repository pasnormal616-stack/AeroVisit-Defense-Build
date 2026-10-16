package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;

import com.paf.aerovisit.service.RecordingMailSender.SentEmail;

/**
 * Emails go out through Gmail SMTP (JavaMailSender) as HTML MimeMessages. A recording mail sender keeps each message.
 * Visitor-typed text must arrive in HTML emails as plain text, never as markup.
 * Covers every place where visitor text is put into an HTML email.
 */
class EmailServiceTest {

    // A visitor could type this as their name or feedback to plant a fake button in an email
    private static final String ATTACK = "<a href=\"http://evil.example\">Log in</a>";
    private static final String ESCAPED = "&lt;a href=&quot;http://evil.example&quot;&gt;";

    static final String PASSWORD = "test-app-password";

    private RecordingMailSender mail;
    private List<SentEmail> sent;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mail = new RecordingMailSender();
        sent = mail.sent;
        emailService = new EmailService(mail, "https://museum.example/");
        emailService.mailPassword = PASSWORD;
    }

    private SentEmail sentEmail() {
        assertThat(sent).as("emails sent").hasSize(1);
        return sent.get(0);
    }

    private String sentHtml() {
        String html = sentEmail().html();
        assertThat(html).as("HTML body").isNotNull();
        return html;
    }

    private static void assertEscaped(String html) {
        assertThat(html).doesNotContain(ATTACK.toUpperCase()).doesNotContain(ATTACK);
        assertThat(html.toLowerCase()).contains(ESCAPED);
    }

    // --- Gmail SMTP transport ---

    @Test
    void otpEmailIsSentAsHtmlFromTheOfficialMuseumAddress() {
        assertThat(emailService.sendOtpEmail("v@example.com", "482913", 5)).isTrue();

        SentEmail email = sentEmail();
        assertThat(email.fromName()).isEqualTo("AeroVisit System");
        assertThat(email.fromAddress()).isEqualTo("PAFMuseumOfficial@gmail.com");
        assertThat(email.to()).isEqualTo("v@example.com");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Email Verification Code");
        assertThat(email.html()).contains(">482913</p>").contains("valid for <strong>5 minutes</strong>");
        assertThat(email.plainText()).isNull();
        assertThat(email.attachments()).isEmpty();
    }

    @Test
    void otpEmailReturnsFalseWhenGmailRejectsTheLogin() {
        mail.failure = new MailAuthenticationException("535-5.7.8 Username and Password not accepted");
        assertThat(emailService.sendOtpEmail("v@example.com", "482913", 5)).isFalse();
    }

    @Test
    void otpEmailReturnsFalseWithoutAPasswordAndNeverContactsGmail() {
        emailService.mailPassword = "";
        assertThat(emailService.sendOtpEmail("v@example.com", "482913", 5)).isFalse();
        assertThat(sent).isEmpty();
    }

    // --- Staff account security code (password changes), worded for the Director, not as a booking code ---

    @Test
    void staffSecurityCodeEmailNamesTheRequestedChangeAndIsNotABookingCode() {
        assertThat(emailService.sendStaffOtpEmail("PAFMuseumOfficial@gmail.com", "730154", 5,
                "change the Guard account's password")).isTrue();

        SentEmail email = sentEmail();
        assertThat(email.to()).isEqualTo("PAFMuseumOfficial@gmail.com");
        assertThat(email.fromAddress()).isEqualTo("PAFMuseumOfficial@gmail.com");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Staff Account Security Code");
        assertThat(email.html())
            .contains("PHILIPPINE AIR FORCE AEROSPACE MUSEUM - VISITOR MANAGEMENT SYSTEM")
            .contains("Dear Director,")
            .contains("to <strong>change the Guard account&#39;s password</strong>")
            .contains(">730154</p>").contains("valid for <strong>5 minutes</strong>")
            .contains("No change is saved until the code is entered.")
            .contains("AeroVisit Visitor Management System<br>(automated notice)")
            .doesNotContain("booking form").doesNotContain("Dear Visitor");
    }

    @Test
    void staffSecurityCodeReturnsFalseWhenTheEmailFails() {
        mail.failure = new MailAuthenticationException("535-5.7.8 Username and Password not accepted");
        assertThat(emailService.sendStaffOtpEmail("PAFMuseumOfficial@gmail.com", "730154", 5, "change the Director's password")).isFalse();
    }

    @Test
    void staffSecurityCodeEscapesTheRequestedChange() {
        emailService.sendStaffOtpEmail("PAFMuseumOfficial@gmail.com", "730154", 5, ATTACK);
        assertEscaped(sentHtml());
    }

    // --- Forgot Password reset code (public sign-in page) ---

    @Test
    void passwordResetEmailHasItsOwnWordingForTheDirector() {
        assertThat(emailService.sendPasswordResetEmail("PAFMuseumOfficial@gmail.com", "905521", 5)).isTrue();

        SentEmail email = sentEmail();
        assertThat(email.to()).isEqualTo("PAFMuseumOfficial@gmail.com");
        assertThat(email.fromAddress()).isEqualTo("PAFMuseumOfficial@gmail.com");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Staff Password Reset Code");
        assertThat(email.html())
            .contains("PHILIPPINE AIR FORCE AEROSPACE MUSEUM - VISITOR MANAGEMENT SYSTEM")
            .contains("Dear Director,")
            .contains("A password reset was requested for the Director's AeroVisit staff account")
            .contains(">905521</p>").contains("valid for <strong>5 minutes</strong> and can be used once.")
            .contains("Your current password keeps working until a new one is saved.")
            .contains("If you did not request a password reset, ignore this email")
            .contains("AeroVisit Visitor Management System<br>(automated notice)")
            .doesNotContain("booking form").doesNotContain("Dear Visitor").doesNotContain("Settings page");
        assertThat(email.plainText()).isNull();
        assertThat(email.attachments()).isEmpty();
    }

    @Test
    void passwordResetEmailReturnsFalseWhenTheEmailFails() {
        mail.failure = new MailAuthenticationException("535-5.7.8 Username and Password not accepted");
        assertThat(emailService.sendPasswordResetEmail("PAFMuseumOfficial@gmail.com", "905521", 5)).isFalse();
    }

    @Test
    void passwordResetEmailCannotBeSentWithoutTheMailPassword() {
        emailService.mailPassword = "";
        assertThat(emailService.sendPasswordResetEmail("PAFMuseumOfficial@gmail.com", "905521", 5)).isFalse();
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
        assertThat(sentEmail().subject()).isEqualTo("PAF Aerospace Museum - Requested Date Fully Booked");
        assertEscaped(html);
    }

    @Test
    void otherRejectionReasonsStillGetTheStandardEmail() {
        emailService.sendRejectionEmail("v@example.com", "Juan", "Expired ID presented", "Bring a valid ID");
        assertThat(sentHtml()).contains("<strong>Reason:</strong>").contains("Expired ID presented")
            .contains("<strong>Administrative notes:</strong>").contains("Bring a valid ID")
            .doesNotContain("maximum security capacity");
        assertThat(sentEmail().subject()).isEqualTo("PAF Aerospace Museum - Visit Request Not Approved");
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
        EmailService local = new EmailService(mail, "http://localhost:8081");
        assertThat(local.buildFeedbackLink("abc")).isEqualTo("http://localhost:8081/leave-feedback?token=abc");
    }

    // --- QR code attachments and mandatory exit scan notices ---

    private static final byte[] FAKE_QR = "\u0089PNG fake qr bytes".getBytes(StandardCharsets.ISO_8859_1);

    // The QR emails attach uploads/<qrCodeFileName>; a throwaway file stands in for the generated QR image
    private SentEmail sendWithQrFile(java.util.function.Consumer<String> send) throws Exception {
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

    private static void assertQrAttached(SentEmail email) {
        assertThat(email.attachments()).as("attachments").hasSize(1).containsKey("Entry_QRCode.png");
        assertThat(email.attachments().get("Entry_QRCode.png")).isEqualTo(FAKE_QR);
    }

    private String sentApprovalHtml(String visitorName) throws Exception {
        SentEmail email = sendWithQrFile(file -> emailService.sendApprovalEmailWithQR("v@example.com", visitorName, "2026-10-01", file, "PAF-A7X92K"));
        assertQrAttached(email);
        return email.html();
    }

    @Test
    void approvalEmailCarriesTheEntryAndExitSecurityNotice() throws Exception {
        String html = sentApprovalHtml("Juan Dela Cruz");
        assertThat(html)
            .contains("Dear <span>Juan Dela Cruz</span>,")
            .contains("Booking Reference ID: <strong>PAF-A7X92K</strong>")
            .contains("<strong>Security Notice:</strong>")
            .contains("It must be presented for scanning upon both entry and exit to formally clear your name from the active visitor manifest.");
        assertThat(sent.get(0).subject()).isEqualTo("PAF Aerospace Museum - Visit Approved: Entry Pass Enclosed");
        assertThat(sent.get(0).fromAddress()).isEqualTo("PAFMuseumOfficial@gmail.com");
    }

    @Test
    void approvalEmailEscapesName() throws Exception {
        assertEscaped(sentApprovalHtml(ATTACK));
    }

    @Test
    void reminderEmailIsHtmlWithTheQrAttached() throws Exception {
        SentEmail email = sendWithQrFile(file -> emailService.sendReminderEmailWithQR("v@example.com", "Juan", "2026-10-01", file));
        assertQrAttached(email);
        assertThat(email.plainText()).isNull();
        assertThat(email.html()).contains("Dear <span>Juan</span>,")
            .contains("is tomorrow, <strong style=\"white-space:nowrap;\">2026-10-01</strong>.");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Visit Reminder for Tomorrow");
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

        assertThat(sentEmail().subject()).isEqualTo("PAF Aerospace Museum - Upcoming Duty Roster");
        assertThat(sentEmail().to()).isEqualTo("reyes@example.com");
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
        List<SentEmail> formal = new ArrayList<>(sent);
        sent.clear();
        sendWithQrFile(file -> emailService.sendApprovalEmailWithQR("v@example.com", "Juan", "2026-10-01", file, "PAF-A7X92K"));
        formal.add(sent.remove(0));
        sendWithQrFile(file -> emailService.sendReminderEmailWithQR("v@example.com", "Juan", "2026-10-01", file));
        formal.add(sent.remove(0));
        emailService.sendDirectorDailyReport("d@example.com", DAILY);
        emailService.sendCriticalFeedbackAlert("d@example.com", "Juan", "Closed exhibit", -0.9);
        List<SentEmail> internal = new ArrayList<>(sent);

        assertThat(formal).hasSize(12);
        assertThat(internal).hasSize(2);
        for (SentEmail email : concat(formal, internal)) {
            String subject = email.subject();
            String html = email.html();
            assertThat(html).as(subject).isNotNull().contains(HEADER);
            assertThat(email.plainText()).as(subject).isNull();
            assertThat(email.fromAddress()).as(subject).isEqualTo("PAFMuseumOfficial@gmail.com");
            assertThat(subject).as("subject").startsWith("PAF Aerospace Museum - ");
            assertThat(EMOJI.matcher(subject).find()).as("emoji in subject " + subject).isFalse();
            assertThat(EMOJI.matcher(html).find()).as("emoji in body of " + subject).isFalse();
        }
        for (SentEmail email : formal) {
            assertThat(email.html()).as(email.subject()).contains(SIGNATURE);
        }
        // Sent TO the Director: a system signature instead of the office signing to itself
        for (SentEmail email : internal) {
            assertThat(email.html()).as(email.subject())
                .doesNotContain(SIGNATURE).contains("AeroVisit Visitor Management System<br>(automated notice)");
        }
    }

    private static List<SentEmail> concat(List<SentEmail> a, List<SentEmail> b) {
        List<SentEmail> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static final EmailService.DailyReport DAILY =
            new EmailService.DailyReport("2026-10-01", 12, 47, 10, 41, 2, 6, 0, 148, "2026-10-02", 3,
                    new EmailService.Insights("10:00 AM - 11:00 AM", 14, 85L, 8,
                            new java.util.LinkedHashMap<>(java.util.Map.of("Active Military Personnel", 4L)),
                            java.util.Map.of("Male", 6L), 4.3, 12));

    @Test
    void theDailySummaryIsHtmlWithCheckInsNoShowsWalkInsAndPendingApprovals() {
        emailService.sendDirectorDailyReport("d@example.com", DAILY);
        SentEmail email = sentEmail();
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Daily Visitor Summary - 2026-10-01");
        assertThat(email.html())
            .contains(">12 bookings, 47 guests</td>")
            .contains(">10 bookings, 41 guests (83%)</td>")
            .contains(">2 bookings, 6 guests</td>")
            .contains(">Walk-ins</td>").contains(">Not recorded</td>")
            .contains(">Pending approvals for 2026-10-02</td>").contains(">3</td>")
            .contains(">No-shows to date (all time)</td>").contains(">148</td>")
            .doesNotContain("Still open")    // only shown when a booking is still APPROVED at midnight
            .contains(">10:00 AM - 11:00 AM (14 guests)</td>")
            .contains(">1 h 25 min (8 guard check-outs)</td>")
            .contains(">Active Military Personnel: 4</div>").contains(">Male: 6</div>")
            .contains(">4.3 / 5 (12 ratings)</td>");
    }

    @Test
    void theDailySummaryShowsStillOpenBookingsAndNoRateOnAnEmptyDay() {
        emailService.sendDirectorDailyReport("d@example.com",
                new EmailService.DailyReport("2026-10-01", 0, 0, 0, 0, 0, 0, 0, 0, "2026-10-02", 0, EmailService.Insights.NONE));
        assertThat(sentHtml()).contains(">0 bookings, 0 guests</td>").doesNotContain("%)")
            .contains(">No check-ins</td>").contains(">Not available (no guard check-outs)</td>")
            .contains("<span>None</span>").contains(">No ratings yet</td>");
        sent.clear();

        emailService.sendDirectorDailyReport("d@example.com",
                new EmailService.DailyReport("2026-10-01", 1, 40, 0, 0, 0, 0, 1, 0, "2026-10-02", 0, EmailService.Insights.NONE));
        assertThat(sentHtml()).contains("Still open (resolved by the next night").contains(">1 bookings</td>");
    }

    @Test
    void aMissingTemplateIsAMailFailureNotACrash() {
        // An engine looking in a folder without the email templates: every render fails
        org.thymeleaf.templateresolver.ClassLoaderTemplateResolver nowhere = new org.thymeleaf.templateresolver.ClassLoaderTemplateResolver();
        nowhere.setPrefix("no-such-folder/");
        nowhere.setSuffix(".html");
        org.thymeleaf.spring6.SpringTemplateEngine missing = new org.thymeleaf.spring6.SpringTemplateEngine();
        missing.setTemplateResolver(nowhere);
        EmailService service = new EmailService(mail, "https://museum.example/", missing);
        service.mailPassword = PASSWORD;

        assertThat(service.sendOtpEmail("v@example.com", "1", 5)).isFalse();         // same as an SMTP failure
        service.sendPendingNotification("v@example.com", "Juan", "2026-10-01", false); // caught and logged, never thrown
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sendStaffScheduleEmail("s@example.com", "Reyes", List.of()))
            .isInstanceOf(EmailService.MailDeliveryException.class);              // batch senders still count it as a failure
        assertThat(sent).isEmpty();
    }
}
