package com.paf.aerovisit.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** The News rate limits: the public feed per IP (60 pages a minute), the Director's photo uploads per user. Fake clock. */
class NewsRateLimitTest {

    private long now = 0;
    private final RateLimitFilter filter = new RateLimitFilter(true, () -> now);

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private int send(String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void theFeedAllows60PagesAMinutePerIp() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(send("GET", "/news/feed", "203.0.113.5")).isEqualTo(200);
        }
        assertThat(send("GET", "/news/feed", "203.0.113.5")).isEqualTo(429);
        assertThat(send("GET", "/news/feed", "203.0.113.9")).isEqualTo(200); // another visitor is not affected

        now += java.time.Duration.ofSeconds(61).toNanos();
        assertThat(send("GET", "/news/feed", "203.0.113.5")).isEqualTo(200);
    }

    @Test
    void photoUploadsAreLimitedPerDirector() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "director", "n/a", AuthorityUtils.createAuthorityList("ROLE_DIRECTOR")));
        for (int i = 0; i < 60; i++) {
            assertThat(send("POST", "/admin/content/news/" + (i % 3 + 1) + "/photos", "10.0.0.2")).isEqualTo(200);
        }
        assertThat(send("POST", "/admin/content/news/1/photos", "10.0.0.3")).isEqualTo(429);
    }
}
