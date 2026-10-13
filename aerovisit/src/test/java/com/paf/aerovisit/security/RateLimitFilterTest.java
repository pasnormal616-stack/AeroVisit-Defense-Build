package com.paf.aerovisit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

/** The rule table and responses of RateLimitFilter, on a fake clock (no Spring context). */
class RateLimitFilterTest {

    private long now = 0;
    private final RateLimitFilter filter = new RateLimitFilter(true, () -> now);
    private boolean reachedApp;

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse send(String method, String path, String ip) throws Exception {
        return send(request(method, path, ip));
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        reachedApp = chain.getRequest() != null;
        return response;
    }

    private static MockHttpServletRequest request(String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return request;
    }

    private void signIn(String username) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, "n/a", AuthorityUtils.createAuthorityList("ROLE_GUARD")));
    }

    // --- Public pages: per client IP ---

    @Test
    void loginAllowsTenAttemptsPerFiveMinutesPerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            send("POST", "/login", "203.0.113.1");
            assertThat(reachedApp).as("attempt " + (i + 1)).isTrue();
        }
        MockHttpServletResponse refused = send("POST", "/login", "203.0.113.1");
        assertThat(reachedApp).isFalse();
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("300");
        assertThat(refused.getContentAsString()).isEmpty();               // a form page: Spring's 429 error page renders it

        assertThat(send("POST", "/login", "203.0.113.2").getStatus()).isEqualTo(200); // another visitor
        now += Duration.ofMinutes(5).toNanos();
        send("POST", "/login", "203.0.113.1");
        assertThat(reachedApp).isTrue();                                   // the window moved on
    }

    @Test
    void eachPublicFormHasItsOwnLimit() throws Exception {
        record Case(String method, String path, int limit) {}
        int n = 10;
        for (Case c : List.of(new Case("POST", "/save", 20), new Case("GET", "/check-status", 20),
                              new Case("POST", "/manage-booking/login", 20), new Case("POST", "/manage-booking/cancel", 30),
                              new Case("GET", "/leave-feedback", 10), new Case("GET", "/", 120),
                              new Case("GET", "/api/zones", 60))) {
            String ip = "198.51.100." + n++;
            for (int i = 0; i < c.limit(); i++) send(c.method(), c.path(), ip);
            assertThat(reachedApp).as(c.path() + " within limit").isTrue();
            assertThat(send(c.method(), c.path(), ip).getStatus()).as(c.path() + " over limit").isEqualTo(429);
        }
    }

    @Test
    void theFeedbackPageAndItsSubmitShareOneBudget() throws Exception {
        for (int i = 0; i < 5; i++) send("GET", "/leave-feedback", "203.0.113.30");
        for (int i = 0; i < 5; i++) send("POST", "/submit-feedback", "203.0.113.30");
        assertThat(send("POST", "/submit-feedback", "203.0.113.30").getStatus()).isEqualTo(429);
    }

    @Test
    void theFirstMatchingRuleWinsSoManageLoginDoesNotUseUpTheOtherActions() throws Exception {
        for (int i = 0; i < 20; i++) send("POST", "/manage-booking/login", "203.0.113.40");
        assertThat(send("POST", "/manage-booking/login", "203.0.113.40").getStatus()).isEqualTo(429);
        send("POST", "/manage-booking/update", "203.0.113.40");
        assertThat(reachedApp).isTrue();
    }

    @Test
    void pagesWithoutARuleAreNeverLimited() throws Exception {
        for (int i = 0; i < 500; i++) send("GET", "/styles/visitor.css", "203.0.113.50");
        assertThat(reachedApp).isTrue();
        for (int i = 0; i < 500; i++) send("GET", "/success-page", "203.0.113.50");
        assertThat(reachedApp).isTrue();
    }

    // --- Who is asking ---

    @Test
    void aBrowserCannotDodgeTheLimitWithItsOwnForwardedHeader() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest r = request("POST", "/login", "203.0.113.60");
            r.addHeader("X-Forwarded-For", "10.9.9." + i);
            send(r);
        }
        MockHttpServletRequest r = request("POST", "/login", "203.0.113.60");
        r.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(send(r).getStatus()).isEqualTo(429);
    }

    @Test
    void visitorsBehindTheProxyAreCountedSeparately() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest r = request("POST", "/login", "100.64.0.9"); // the proxy's own address
            r.addHeader("X-Forwarded-For", "198.51.100.61");
            send(r);
        }
        MockHttpServletRequest same = request("POST", "/login", "100.64.0.9");
        same.addHeader("X-Forwarded-For", "198.51.100.61");
        assertThat(send(same).getStatus()).isEqualTo(429);
        MockHttpServletRequest other = request("POST", "/login", "100.64.0.9");
        other.addHeader("X-Forwarded-For", "198.51.100.62");
        send(other);
        assertThat(reachedApp).isTrue();
    }

    // --- Staff actions: per username ---

    @Test
    void gateActionsAreCountedPerGuardNotPerIp() throws Exception {
        signIn("guard-a");
        for (int i = 0; i < 60; i++) send("POST", "/admin/api/scan", "10.0.0.5");
        assertThat(reachedApp).isTrue();
        assertThat(send("POST", "/admin/manual-checkin", "10.0.0.5").getStatus()).isEqualTo(429); // same budget

        signIn("guard-b");                                                  // the same gate PC, another guard
        send("POST", "/admin/api/scan", "10.0.0.5");
        assertThat(reachedApp).isTrue();
    }

    @Test
    void aSignedOutRequestToAStaffActionIsLeftToSpringSecurity() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        for (int i = 0; i < 100; i++) send("POST", "/admin/api/scan", "203.0.113.70");
        assertThat(reachedApp).isTrue();
    }

    @Test
    void theOtherStaffRulesHaveTheirLimits() throws Exception {
        record Case(String method, String path, int limit) {}
        int n = 0;
        for (Case c : List.of(new Case("POST", "/api/booking/verify-id", 20), new Case("POST", "/admin/biometric-ban/7", 20),
                              new Case("POST", "/admin/content/photos/upload", 20), new Case("GET", "/admin/report", 10),
                              new Case("POST", "/admin/settings/password", 5), new Case("GET", "/admin/inject-mock-data", 2))) {
            signIn("director-" + n++);
            for (int i = 0; i < c.limit(); i++) send(c.method(), c.path(), "10.0.0.6");
            assertThat(reachedApp).as(c.path() + " within limit").isTrue();
            assertThat(send(c.method(), c.path(), "10.0.0.6").getStatus()).as(c.path() + " over limit").isEqualTo(429);
        }
    }

    // --- Responses ---

    @Test
    void jsonCallersGetEveryFieldTheirScriptsRead() throws Exception {
        signIn("guard-json");
        for (int i = 0; i < 60; i++) send("POST", "/admin/api/scan", "10.0.0.7");
        MockHttpServletResponse refused = send("POST", "/admin/api/scan", "10.0.0.7");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentType()).startsWith("application/json");
        JSONObject body = new JSONObject(refused.getContentAsString());
        assertThat(body.getBoolean("ok")).isFalse();                      // OTP script
        assertThat(body.getString("status")).isEqualTo("error");          // scanner
        assertThat(body.getString("reply")).isEqualTo(RateLimitFilter.MESSAGE); // chatbot
        assertThat(body.getString("message")).isEqualTo(RateLimitFilter.MESSAGE);
        assertThat(body.getLong("retryAfter")).isEqualTo(60);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    void aFormPageAskingForJsonGetsJsonToo() throws Exception {
        for (int i = 0; i < 20; i++) send("GET", "/check-status", "203.0.113.80");
        MockHttpServletRequest r = request("GET", "/check-status", "203.0.113.80");
        r.addHeader("Accept", "application/json");
        assertThat(send(r).getContentType()).startsWith("application/json");
    }

    // --- Switches ---

    @Test
    void turnedOffItNeverRefuses() throws Exception {
        RateLimitFilter off = new RateLimitFilter(false, () -> now);
        for (int i = 0; i < 50; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            off.doFilter(request("POST", "/login", "203.0.113.90"), response, chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void renderingTheErrorPageIsNotCountedAgain() throws Exception {
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest error = request("POST", "/login", "203.0.113.95");
            error.setDispatcherType(DispatcherType.ERROR);
            error.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/login"); // what Tomcat sets on the error dispatch
            send(error);
        }
        send("POST", "/login", "203.0.113.95");
        assertThat(reachedApp).isTrue();
    }
}
