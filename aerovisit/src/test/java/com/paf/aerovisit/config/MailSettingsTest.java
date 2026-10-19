package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Email settings in application.properties: Gmail when nothing is set, Amazon SES when the MAIL_* settings are given,
 * and staff security codes and alerts always going to a real inbox, never to a no-reply SES sender. No server needed.
 */
class MailSettingsTest {

    private static final String GMAIL = "PAFMuseumOfficial@gmail.com";

    // application.properties as the app reads it, with only the given environment settings (nothing from this machine)
    private static AbstractEnvironment settings(Map<String, Object> environment) throws IOException {
        AbstractEnvironment env = new AbstractEnvironment() {};
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application.properties")));
        env.getPropertySources().addFirst(new MapPropertySource("environment", environment));
        return env;
    }

    @Test
    void withNothingSetItIsGmailExactlyAsBefore() throws IOException {
        AbstractEnvironment env = settings(Map.of());
        assertThat(env.getProperty("spring.mail.host")).isEqualTo("smtp.gmail.com");
        assertThat(env.getProperty("spring.mail.port")).isEqualTo("587");
        assertThat(env.getProperty("spring.mail.username")).isEqualTo(GMAIL);
        assertThat(env.getProperty("aerovisit.mail.from")).isEqualTo(GMAIL);
        assertThat(env.getProperty("aerovisit.security.inbox")).isEqualTo(GMAIL);
        assertThat(env.getProperty("aerovisit.alerts.email")).isEqualTo(GMAIL);
    }

    @Test
    void sesSettingsChangeTheServerAndSenderButCodesAndAlertsStillReachTheMuseumInbox() throws IOException {
        AbstractEnvironment env = settings(Map.of(
                "MAIL_HOST", "email-smtp.ap-southeast-2.amazonaws.com",
                "MAIL_USERNAME", "AKIAEXAMPLESMTPUSER",
                "MAIL_FROM", "noreply@aerovisit.example"));
        assertThat(env.getProperty("spring.mail.host")).isEqualTo("email-smtp.ap-southeast-2.amazonaws.com");
        assertThat(env.getProperty("spring.mail.username")).isEqualTo("AKIAEXAMPLESMTPUSER");
        assertThat(env.getProperty("aerovisit.mail.from")).isEqualTo("noreply@aerovisit.example");
        assertThat(env.getProperty("aerovisit.security.inbox")).isEqualTo(GMAIL);
        assertThat(env.getProperty("aerovisit.alerts.email")).isEqualTo(GMAIL);
    }

    @Test
    void theSecurityInboxAndAlertInboxCanEachBeSet() throws IOException {
        AbstractEnvironment env = settings(Map.of("SECURITY_INBOX", "it@museum.example"));
        assertThat(env.getProperty("aerovisit.security.inbox")).isEqualTo("it@museum.example");
        assertThat(env.getProperty("aerovisit.alerts.email")).isEqualTo("it@museum.example"); // follows the security inbox

        env = settings(Map.of("SECURITY_INBOX", "it@museum.example", "ALERT_EMAIL", "director@museum.example"));
        assertThat(env.getProperty("aerovisit.alerts.email")).isEqualTo("director@museum.example");
    }
}
