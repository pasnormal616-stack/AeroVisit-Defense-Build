package com.paf.aerovisit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 🛂 The passport rate limits, per IP like the other public endpoints: submissions and re-uploads share 10 per 10
 * minutes; the emailed links (continue / resubmit page) 60 per 5 minutes. Fake clock.
 */
class PassportRateLimitTest {

    private long now = 0;
    private final RateLimitFilter filter = new RateLimitFilter(true, () -> now);

    private int send(String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void submissionsAndReuploadsShareTenPerTenMinutesPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", i % 2 == 0 ? "/passport/submit" : "/passport/resubmit", "203.0.113.5")).isEqualTo(200);
        }
        assertThat(send("POST", "/passport/submit", "203.0.113.5")).isEqualTo(429);
        assertThat(send("POST", "/passport/resubmit", "203.0.113.5")).isEqualTo(429);
        assertThat(send("POST", "/passport/submit", "203.0.113.9")).isEqualTo(200); // another visitor is not affected

        now += java.time.Duration.ofMinutes(10).plusSeconds(1).toNanos();
        assertThat(send("POST", "/passport/submit", "203.0.113.5")).isEqualTo(200);
    }

    @Test
    void theEmailedLinksAllowSixtyPerFiveMinutesPerIp() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(send("GET", "/passport/continue", "198.51.100.7")).isEqualTo(200);
        }
        assertThat(send("GET", "/passport/resubmit", "198.51.100.7")).isEqualTo(429);
    }
}
