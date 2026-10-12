package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Thread-exhaustion guard: every call to the Python AI service gives up after its timeout (10 s in production) instead
 * of holding a server thread forever. A local stub server that answers too slowly; no Python, no database.
 */
class AiServiceTimeoutTest {

    private static final long STUB_DELAY_MS = 3_000;
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);

    private HttpServer slowAiService;
    private final PredictiveAdvisorService service = new PredictiveAdvisorService();

    @BeforeEach
    void startSlowStub() throws IOException {
        slowAiService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slowAiService.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        slowAiService.createContext("/", exchange -> {
            try {
                Thread.sleep(STUB_DELAY_MS); // a hung / overloaded AI service
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"critical_alert\":false,\"score\":0.1}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientGone) {
                // the caller already gave up: that is the point of the test
            }
            exchange.close();
        });
        slowAiService.start();
        service.apiBaseUrl = "http://127.0.0.1:" + slowAiService.getAddress().getPort();
        service.timeout = SHORT_TIMEOUT;
    }

    @AfterEach
    void stop() {
        slowAiService.stop(0);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Test
    void productionTimeoutsAreTenSeconds() {
        assertThat(PredictiveAdvisorService.TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(new PredictiveAdvisorService().timeout).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void feedbackAnalysisGivesUpInsteadOfHanging() {
        long start = System.nanoTime();
        assertThat(service.analyzeFeedbackSentiment("The tour was great")).isNull(); // the usual "AI offline" result
        assertThat(millisSince(start)).isLessThan(STUB_DELAY_MS - 1_000);
    }

    @Test
    void turnoutPredictionGivesUpInsteadOfHanging() {
        long start = System.nanoTime();
        assertThat(service.fetchTurnoutPrediction(3, "Clear", 40)).isNull();
        assertThat(millisSince(start)).isLessThan(STUB_DELAY_MS - 1_000);
    }

    @Test
    void retrainAndLegacyChatGiveUpInsteadOfHanging() {
        long start = System.nanoTime();
        assertThat(service.triggerAiRetrain()).isFalse();
        assertThat(service.chatWithSgtAero("hello", "SGT-TIME0001")).isNull();
        assertThat(millisSince(start)).isLessThan(2 * STUB_DELAY_MS - 2_000);
    }

    @Test
    void smtpCanNeverHangARequestEither() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.connectiontimeout")).isEqualTo("10000");
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.timeout")).isEqualTo("10000");
        assertThat(props.getProperty("spring.mail.properties.mail.smtp.writetimeout")).isEqualTo("10000");
    }
}
