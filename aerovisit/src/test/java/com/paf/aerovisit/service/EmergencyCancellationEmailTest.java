package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.RecordingMailSender.SentEmail;

/** The emergency closure email: content, escaping, and failures reaching the caller so the batch can count them. */
class EmergencyCancellationEmailTest {

    private static Visitor visitor() {
        Visitor v = new Visitor();
        v.setName("<b>Juan</b>");
        v.setEmail("juan@example.com");
        v.setVisitDate(LocalDate.of(2026, 10, 12));
        v.setReferenceId("PAF-A7X92K");
        return v;
    }

    private static EmailService serviceFor(RecordingMailSender mail) {
        EmailService service = new EmailService(mail, "https://museum.example/");
        service.mailPassword = EmailServiceTest.PASSWORD;
        return service;
    }

    @Test
    void emailNamesTheDateReasonAndReferenceAndEscapesText() throws Exception {
        RecordingMailSender mail = new RecordingMailSender();

        serviceFor(mail).sendEmergencyCancellation(visitor(), "Typhoon <script>alert(1)</script>");

        assertThat(mail.sent).hasSize(1);
        SentEmail email = mail.sent.get(0);
        assertThat(email.to()).isEqualTo("juan@example.com");
        assertThat(email.subject()).contains("2026-10-12");

        String html = email.html();
        assertThat(html).contains("2026-10-12").contains("PAF-A7X92K")
            .contains("Typhoon &lt;script&gt;").doesNotContain("<script>")
            .contains("&lt;b&gt;Juan&lt;/b&gt;").doesNotContain("<b>Juan");
    }

    @Test
    void sendFailureIsThrownToTheCaller() {
        RecordingMailSender mail = new RecordingMailSender();
        mail.failure = new MailSendException("421 Service not available");

        assertThatThrownBy(() -> serviceFor(mail).sendEmergencyCancellation(visitor(), "Typhoon Signal No. 3"))
            .isInstanceOf(EmailService.MailDeliveryException.class);
    }

    @Test
    void missingPasswordIsThrownToTheCaller() {
        EmailService service = new EmailService(new RecordingMailSender(), "https://museum.example/");
        assertThatThrownBy(() -> service.sendEmergencyCancellation(visitor(), "Typhoon Signal No. 3"))
            .isInstanceOf(EmailService.MailDeliveryException.class)
            .hasMessageContaining("AEROVISIT_MAIL_PASSWORD");
    }
}
