package com.paf.aerovisit.security;

import static com.paf.aerovisit.security.RateLimitRule.perIp;
import static com.paf.aerovisit.security.RateLimitRule.perUser;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 🚦 RATE LIMITS for every public form and the costly staff actions (the table below; first matching rule wins).
// Runs inside Spring Security's chain just before CsrfFilter: after the session's login is loaded (staff rules key on
// the username) and before the form-login filter (POST /login is counted). It never reads request parameters, so a
// refused /save is turned away before its 15 MB multipart body is parsed. The chatbot (ChatRateLimiter) and OTP
// (OtpService) keep their own limits. aerovisit.ratelimit.enabled=false turns it off (the test suite does).
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;
    static final String MESSAGE = "Too many requests. Please wait a moment and try again.";

    static final List<RateLimitRule> RULES = List.of(
        // Public (per client IP). Generous: a school, a venue Wi-Fi or a mobile carrier can put many visitors on one IP.
        perIp("login",          "POST", 10,  Duration.ofMinutes(5),  "/login"),
        perIp("booking-submit", "POST", 20,  Duration.ofMinutes(10), "/save"),
        perIp("status-lookup",  "GET",  20,  Duration.ofMinutes(1),  "/check-status"),
        perIp("manage-login",   "POST", 20,  Duration.ofMinutes(5),  "/manage-booking/login"),
        perIp("manage-actions", "POST", 30,  Duration.ofMinutes(5),  "/manage-booking/**"),
        perIp("feedback",       "*",    10,  Duration.ofMinutes(10), "/leave-feedback", "/submit-feedback"),
        perIp("booking-page",   "GET",  120, Duration.ofMinutes(1),  "/"),
        perIp("public-api",     "GET",  60,  Duration.ofMinutes(1),  "/api/zones"),
        // Forgot Password: asking for a code mails the museum inbox, so it is tight; entering the code is looser (a typo
        // must not use up the budget, and OtpService burns a code after 5 wrong tries anyway)
        perIp("reset-request",  "POST", 3,   Duration.ofMinutes(15), "/forgot-password/request"),
        perIp("reset-confirm",  "POST", 10,  Duration.ofMinutes(15), "/forgot-password/reset"),
        // Staff (per username; a signed-out request is left to Spring Security)
        perUser("gate-actions",  "POST", 60, Duration.ofMinutes(1),  "/admin/api/scan", "/admin/manual-checkin",
                "/admin/api/confirm-check-in", "/admin/api/deny-entry", "/admin/deny-entry"),
        perUser("ocr",           "POST", 20, Duration.ofMinutes(1),  "/api/booking/verify-id"),
        perUser("bans",          "POST", 20, Duration.ofMinutes(1),  "/admin/biometric-ban/*", "/admin/bans/*/lift"),
        perUser("uploads",       "POST", 20, Duration.ofMinutes(10), "/admin/content/photos/upload"),
        perUser("exports",       "GET",  10, Duration.ofMinutes(1),  "/admin/report", "/admin/export-excel"),
        perUser("password-form", "POST", 5,  Duration.ofMinutes(10), "/admin/api/staff-accounts/request",
                "/admin/api/staff-accounts/confirm"),
        perUser("mock-data",     "GET",  2,  Duration.ofMinutes(10), "/admin/inject-mock-data"));

    private final boolean enabled;
    private final Map<RateLimitRule, SlidingWindowLimiter> limiters = new LinkedHashMap<>();

    public RateLimitFilter(boolean enabled, LongSupplier clock) {
        this.enabled = enabled;
        RULES.forEach(rule -> limiters.put(rule, new SlidingWindowLimiter(rule.limit(), rule.window(), clock)));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATHS.getPathWithinApplication(request);
        for (RateLimitRule rule : RULES) {
            if (!rule.matches(request.getMethod(), path)) continue;
            String who = rule.perUser() ? currentUser() : ClientIp.of(request);
            if (who != null) {
                SlidingWindowLimiter limiter = limiters.get(rule);
                if (!limiter.tryAcquire(who)) {
                    reject(request, response, rule, who, path, limiter.secondsUntilAllowed(who));
                    return;
                }
            }
            break; // first matching rule only
        }
        chain.doFilter(request, response);
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken ? null : auth.getName();
    }

    // JSON callers (chatbot, scanner, OTP, fetch) get a 429 body with every field their scripts read; form pages get
    // the friendly 429 error page. The log line shows the key: on Railway it must be the visitor's real public IP.
    private static void reject(HttpServletRequest request, HttpServletResponse response, RateLimitRule rule,
                               String who, String path, long retryAfter) throws IOException {
        log.warn("🚦 Rate limit '{}' reached by {} on {} {}", rule.name(), who, request.getMethod(), path);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        if (wantsJson(request, path)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(new JSONObject()
                    .put("ok", false).put("status", "error").put("type", "error")
                    .put("message", MESSAGE).put("reply", MESSAGE).put("retryAfter", retryAfter).toString());
        } else {
            response.sendError(429);
        }
    }

    private static boolean wantsJson(HttpServletRequest request, String path) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return path.startsWith("/api/") || path.startsWith("/admin/api/") || path.startsWith("/otp/")
                || (accept != null && accept.contains("application/json"));
    }
}
