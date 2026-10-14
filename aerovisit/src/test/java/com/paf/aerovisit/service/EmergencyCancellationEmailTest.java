package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paf.aerovisit.model.Visitor;

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

    private static EmailService serviceFor(RestTemplate rest) {
        EmailService service = new EmailService(rest, "https://museum.example/");
        service.brevoApiKey = EmailServiceTest.API_KEY;
        return service;
    }

    @Test
    void emailNamesTheDateReasonAndReferenceAndEscapesText() throws Exception {
        List<JsonNode> sent = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer.bindTo(rest).build()
            .expect(requestTo(EmailService.BREVO_URL))
            .andExpect(request -> sent.add(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
            .andRespond(withStatus(HttpStatus.CREATED));

        serviceFor(rest).sendEmergencyCancellation(visitor(), "Typhoon <script>alert(1)</script>");

        assertThat(sent).hasSize(1);
        JsonNode email = sent.get(0);
        assertThat(email.path("to").get(0).path("email").asText()).isEqualTo("juan@example.com");
        assertThat(email.path("subject").asText()).contains("2026-10-12");

        String html = email.path("htmlContent").asText();
        assertThat(html).contains("2026-10-12").contains("PAF-A7X92K")
            .contains("Typhoon &lt;script&gt;").doesNotContain("<script>")
            .contains("&lt;b&gt;Juan&lt;/b&gt;").doesNotContain("<b>Juan");
    }

    @Test
    void sendFailureIsThrownToTheCaller() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer.bindTo(rest).build()
            .expect(requestTo(EmailService.BREVO_URL))
            .andRespond(withServerError());

        assertThatThrownBy(() -> serviceFor(rest).sendEmergencyCancellation(visitor(), "Typhoon Signal No. 3"))
            .isInstanceOf(EmailService.MailDeliveryException.class);
    }

    @Test
    void missingApiKeyIsThrownToTheCaller() {
        EmailService service = new EmailService(new RestTemplate(), "https://museum.example/");
        assertThatThrownBy(() -> service.sendEmergencyCancellation(visitor(), "Typhoon Signal No. 3"))
            .isInstanceOf(EmailService.MailDeliveryException.class)
            .hasMessageContaining("BREVO_API_KEY");
    }
}
