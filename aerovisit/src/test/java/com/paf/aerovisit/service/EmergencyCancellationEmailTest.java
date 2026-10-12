package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.paf.aerovisit.model.Visitor;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/** The emergency closure email: content, escaping, and failures reaching the caller so the batch can count them. */
class EmergencyCancellationEmailTest {

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        emailService = new EmailService(mailSender, "https://museum.example/");
    }

    private static Visitor visitor() {
        Visitor v = new Visitor();
        v.setName("<b>Juan</b>");
        v.setEmail("juan@example.com");
        v.setVisitDate(LocalDate.of(2026, 10, 12));
        v.setReferenceId("PAF-A7X92K");
        return v;
    }

    @Test
    void emailNamesTheDateReasonAndReferenceAndEscapesText() throws Exception {
        emailService.sendEmergencyCancellation(visitor(), "Typhoon <script>alert(1)</script>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("juan@example.com");
        assertThat(message.getSubject()).contains("2026-10-12");

        String html = findHtml(message);
        assertThat(html).contains("2026-10-12").contains("PAF-A7X92K")
            .contains("Typhoon &lt;script&gt;").doesNotContain("<script>")
            .contains("&lt;B&gt;JUAN&lt;/B&gt;").doesNotContain("<B>JUAN");
    }

    private static String findHtml(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text && part.isMimeType("text/html")) return text;
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String html = findHtml(multipart.getBodyPart(i));
                if (html != null) return html;
            }
        }
        return null;
    }

    @Test
    void sendFailureIsThrownToTheCaller() {
        doThrow(new MailSendException("SMTP server unreachable")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> emailService.sendEmergencyCancellation(visitor(), "Typhoon Signal No. 3"))
            .isInstanceOf(MailSendException.class);
    }
}
