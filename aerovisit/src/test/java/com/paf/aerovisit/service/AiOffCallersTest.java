package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** With the AI switched off, nothing calls the AI service: every caller answers at once with its "AI offline" result. */
class AiOffCallersTest {

    // An address where a real call would hang until its time limit, so a quick answer proves no call was made
    private static final String BLACKHOLE = "http://10.255.255.1:8000";

    private static AiSwitch off() {
        AiSwitch s = mock(AiSwitch.class);
        when(s.enabled()).thenReturn(false);
        return s;
    }

    private static long millis(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void aBookingGoesStraightToManualReview() {
        KycService kyc = new KycService(mock(OcrService.class));
        kyc.apiBaseUrl = BLACKHOLE;
        kyc.aiSwitch = off();
        KycService.KycResult[] result = new KycService.KycResult[1];

        assertThat(millis(() -> result[0] = kyc.screen(new byte[] {1}, new byte[] {2}, "Juan", "Dela Cruz"))).isLessThan(1000);
        assertThat(result[0].status()).isEqualTo(KycService.MANUAL_REVIEW);
        assertThat(result[0].reason()).isEqualTo("AI_OFF");
        assertThat(result[0].faceEncoding()).isNull();
    }

    @Test
    void forecastFeedbackChatAndRetrainAnswerOfflineAtOnce() {
        PredictiveAdvisorService ai = new PredictiveAdvisorService();
        ai.apiBaseUrl = BLACKHOLE;
        ai.aiSwitch = off();

        assertThat(millis(() -> {
            assertThat(ai.fetchTurnoutPrediction(3, "Clear", 10)).isNull();
            assertThat(ai.analyzeFeedbackSentiment("Great tour")).isNull();
            assertThat(ai.chatWithSgtAero("hello", "SGT-ABCDEF12")).isNull();
            assertThat(ai.triggerAiRetrain()).isFalse();
        })).isLessThan(1000);
    }
}
