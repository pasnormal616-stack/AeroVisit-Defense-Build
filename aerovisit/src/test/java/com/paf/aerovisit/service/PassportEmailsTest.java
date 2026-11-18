package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import com.paf.aerovisit.service.RecordingMailSender.SentEmail;

/**
 * 🛂 The three passport-review emails: received (under review), approved (the one-time booking link and its expiry)
 * and rejected (the reason and the re-upload link). Links use the public address; visitor text is escaped.
 */
class PassportEmailsTest {

    private RecordingMailSender mail;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mail = new RecordingMailSender();
        emailService = new EmailService(mail, "https://museum.example/");
        emailService.mailPassword = EmailServiceTest.PASSWORD;
    }

    private SentEmail only() {
        assertThat(mail.sent).hasSize(1);
        return mail.sent.get(0);
    }

    @Test
    void receivedSaysThePassportIsUnderReviewAndNothingIsBookedYet() {
        emailService.sendPassportReceived("anna@example.de", "Schmidt, Anna");
        SentEmail email = only();
        assertThat(email.to()).isEqualTo("anna@example.de");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Passport Received: Under Review");
        assertThat(email.html()).contains("Schmidt, Anna").contains("<strong>under review</strong>")
                .contains("No time slot has been reserved for you yet.");
    }

    @Test
    void approvedCarriesTheOneVisitBookingLinkAndWhenItExpires() {
        emailService.sendPassportApproved("anna@example.de", "Schmidt, Anna", "tok-123", "Mon, Nov 16, 2026 at 12:00 PM");
        SentEmail email = only();
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Passport Approved: Book Your Visit");
        assertThat(email.html())
                .contains("href=\"https://museum.example/passport/continue?token=tok-123\"")
                .contains("<strong>one visit</strong>")
                .contains("open it again to continue where you left off")
                .contains("Mon, Nov 16, 2026 at 12:00 PM");
    }

    @Test
    void rejectedCarriesTheReasonEscapedAndTheReuploadLink() {
        emailService.sendPassportRejected("anna@example.de", "Schmidt, Anna", "Cropped <b>page</b>", "tok-9");
        SentEmail email = only();
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Passport Not Accepted: Please Upload a New One");
        assertThat(email.html())
                .contains("Cropped &lt;b&gt;page&lt;/b&gt;")
                .contains("href=\"https://museum.example/passport/resubmit?token=tok-9\"");
    }

    @Test
    void aFailedSendIsReportedSoTheOutboxCanLogIt() {
        mail.failure = new MailSendException("SMTP down");
        assertThatThrownBy(() -> emailService.sendPassportApproved("a@example.de", "A", "t", "x"))
                .isInstanceOf(EmailService.MailDeliveryException.class);
    }
}
