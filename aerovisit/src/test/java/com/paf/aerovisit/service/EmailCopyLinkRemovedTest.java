package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.RecordingMailSender.SentEmail;

/**
 * 📧 The "Button not working? Copy this link into your browser" line under each email button is gone (it repeated the
 * button's address, which is dead while PUBLIC_BASE_URL is unset). Each of the nine emails that had it still renders its
 * main button exactly once with the right address and label, and its other links (the payment-expired mailto) stay.
 */
class EmailCopyLinkRemovedTest {

    private static final String BASE = "https://museum.example";

    private RecordingMailSender mail;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mail = new RecordingMailSender();
        emailService = new EmailService(mail, BASE + "/");
        emailService.mailPassword = EmailServiceTest.PASSWORD;
    }

    private String html() {
        assertThat(mail.sent).hasSize(1);
        SentEmail email = mail.sent.remove(0);
        return email.html();
    }

    private static int count(String html, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(html);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    // No copy-link line; the one button still links to its page with its label
    private static void onlyTheButton(String html, String url, String label) {
        assertThat(html).doesNotContain("Button not working").doesNotContain("Copy this link").doesNotContain("word-break:break-all");
        assertThat(count(html, "href=\"" + url + "\"")).as(url).isEqualTo(1);
        assertThat(html).containsPattern("href=\"" + Pattern.quote(url) + "\"[^>]*>\\s*" + Pattern.quote(label) + "\\s*</a>");
    }

    private static EmailService.PaymentNotice notice(String reason) {
        return new EmailService.PaymentNotice("v@example.com", "Juan", "PAF-A7X92K", "000123", "PHP 650.00", "2 Regular",
                "Mon, Nov 2, 2026 at 9:00 AM", "tok-pay", reason, false, false, "museum.contact@example.com");
    }

    private static Visitor visitor(boolean paid) {
        Visitor v = new Visitor();
        v.setName("Juan");
        v.setEmail("v@example.com");
        v.setReferenceId("PAF-A7X92K");
        v.setVisitDate(LocalDate.of(2026, 11, 20));
        if (paid) {
            v.setBillingNumber("000123");
            v.setAdmissionTotalCentavos(65_000L);
        }
        return v;
    }

    @Test
    void checkOutKeepsOnlyTheFeedbackButton() {
        emailService.sendCheckOutFeedback("v@example.com", "Juan", "tok-1");
        onlyTheButton(html(), BASE + "/leave-feedback?token=tok-1", "Submit Feedback");
    }

    @Test
    void thePaymentEmailsKeepOnlyTheirButton() {
        emailService.sendPaymentRequest(notice(null));
        onlyTheButton(html(), BASE + "/payment?token=tok-pay", "Pay and Upload Receipt");

        emailService.sendPaymentRejected(notice("The amount does not match."));
        onlyTheButton(html(), BASE + "/payment?token=tok-pay", "Upload a New Receipt");

        emailService.sendPaymentExpired(notice(null));
        String expired = html();
        onlyTheButton(expired, BASE + "/", "Book a New Visit");
        assertThat(expired).contains("href=\"mailto:museum.contact@example.com\""); // the contact link stays
    }

    @Test
    void theRebookAndRescheduleEmailsKeepOnlyTheirButton() throws Exception {
        emailService.sendRejectionEmail("v@example.com", "Juan", "Capacity Reached", null);
        onlyTheButton(html(), BASE + "/", "Book a New Visit");

        emailService.sendEmergencyCancellation(visitor(false), "Typhoon Signal No. 3");
        String cancelled = html();
        assertThat(cancelled).doesNotContain("Button not working");
        assertThat(count(cancelled, "href=\"" + BASE + "/\"")).isEqualTo(1);

        emailService.sendClosureReschedule(visitor(true), "Typhoon Signal No. 3");
        String closure = html();
        assertThat(closure).doesNotContain("Button not working");
        assertThat(count(closure, "href=\"" + BASE + "/manage-booking\"")).isEqualTo(1);

        emailService.sendRescheduleNeeded(visitor(true), false);
        onlyTheButton(html(), BASE + "/manage-booking", "Choose a New Date");
    }

    @Test
    void thePassportEmailsKeepOnlyTheirButton() {
        emailService.sendPassportApproved("v@example.com", "Juan", "tok-a", "Mon, Nov 16, 2026 at 12:00 PM");
        onlyTheButton(html(), BASE + "/passport/continue?token=tok-a", "Book Your Visit");

        emailService.sendPassportRejected("v@example.com", "Juan", "Cropped page", "tok-r");
        onlyTheButton(html(), BASE + "/passport/resubmit?token=tok-r", "Upload a New Passport Copy");
    }
}
