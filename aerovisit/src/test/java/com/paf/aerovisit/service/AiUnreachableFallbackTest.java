package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * 🔌 AI switched ON but its server not answering (crashed, rebooting, stopped outside the switch): the first failed call
 * pauses the AI features, so later bookings / dashboards / feedback skip it at once instead of each waiting out a
 * 10-20 s time-out, and they come back by themselves when the server answers again. Local stub servers only.
 */
class AiUnreachableFallbackTest {

    private HttpServer stub;

    @AfterEach
    void stopStub() {
        if (stub != null) stub.stop(0);
    }

    // An address where nothing listens: the connection is refused at once, like a stopped AI server's port
    private static String deadAddress() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    // A stub AI server that answers every request with this HTTP status
    private String answering(int status) throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        stub.start();
        return "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    private static AiSwitchTest.FakeSwitch switchedOn() {
        AiSwitchTest.FakeSwitch s = new AiSwitchTest.FakeSwitch(); // no database: on
        assertThat(s.state()).isEqualTo(AiSwitch.State.ON);
        return s;
    }

    @Test
    void aBookingThatFindsTheAiServerDownPausesTheCheckForTheNextOnes() throws Exception {
        AiSwitchTest.FakeSwitch ai = switchedOn();
        KycService kyc = new KycService(mock(OcrService.class));
        kyc.apiBaseUrl = deadAddress();
        kyc.aiSwitch = ai;

        KycService.KycResult first = kyc.screen(new byte[] {1}, new byte[] {2}, "Juan", "Dela Cruz");
        assertThat(first.status()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(first.reason()).isEqualTo("AI_SERVICE_UNAVAILABLE");
        assertThat(ai.state()).isEqualTo(AiSwitch.State.STARTING); // paused, the Director's choice is still ON
        assertThat(ai.wanted()).isTrue();

        // The next visitor isn't sent to the dead server at all
        KycService.KycResult next = kyc.screen(new byte[] {1}, new byte[] {2}, "Ana", "Reyes");
        assertThat(next.reason()).isEqualTo("AI_OFF");

        // ...and once the AI server answers again (the 30-second check), the features are back
        ai.answers = true;
        ai.probeWhileStarting();
        assertThat(ai.state()).isEqualTo(AiSwitch.State.ON);
    }

    @Test
    void aServiceUnavailableAnswerPausesTheForecastToo() throws Exception {
        AiSwitchTest.FakeSwitch ai = switchedOn();
        PredictiveAdvisorService advisor = new PredictiveAdvisorService();
        advisor.apiBaseUrl = answering(503); // nginx in front of a FastAPI app that isn't up
        advisor.aiSwitch = ai;

        assertThat(advisor.fetchTurnoutPrediction(3, "Clear", 10)).isNull();
        assertThat(ai.enabled()).isFalse();
        assertThat(advisor.analyzeFeedbackSentiment("Great tour")).isNull(); // answered offline, no call
    }

    @Test
    void anOrdinaryErrorFromARunningAiServerDoesNotPauseAnything() throws Exception {
        for (int status : new int[] {400, 401, 404, 429, 500}) {
            AiSwitchTest.FakeSwitch ai = switchedOn();
            PredictiveAdvisorService advisor = new PredictiveAdvisorService();
            advisor.apiBaseUrl = answering(status);
            advisor.aiSwitch = ai;

            assertThat(advisor.analyzeFeedbackSentiment("Great tour")).isNull();
            assertThat(ai.state()).as("HTTP " + status).isEqualTo(AiSwitch.State.ON);
            stub.stop(0);
            stub = null;
        }
    }

    @Test
    void aSwitchedOffAiStaysOffAndNothingIsReported() {
        AiSwitchTest.FakeSwitch ai = switchedOn();
        ai.set(false);
        ai.reportUnreachable("test");
        assertThat(ai.state()).isEqualTo(AiSwitch.State.OFF);
        ai.answers = true;
        ai.probeWhileStarting();
        assertThat(ai.state()).isEqualTo(AiSwitch.State.OFF); // only the Director turns it back on
    }

    @Test
    void networkFailuresAndGatewayErrorsCountAsUnreachableOtherErrorsDoNot() {
        assertThat(AiSwitch.unreachable(new java.net.ConnectException("refused"))).isTrue();
        assertThat(AiSwitch.unreachable(new java.net.http.HttpTimeoutException("timed out"))).isTrue();
        assertThat(AiSwitch.unreachable(new org.json.JSONException("bad json"))).isFalse();
        assertThat(AiSwitch.unreachable(502)).isTrue();
        assertThat(AiSwitch.unreachable(503)).isTrue();
        assertThat(AiSwitch.unreachable(504)).isTrue();
        assertThat(AiSwitch.unreachable(500)).isFalse();
        assertThat(AiSwitch.unreachable(429)).isFalse();
    }

    @Test
    void theTimeOutsBeingAvoidedAreReal() {
        // Documents why the fallback matters: without it each call to a silent server waits this long
        assertThat(KycService.TIMEOUT).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(PredictiveAdvisorService.TIMEOUT).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
    }
}
