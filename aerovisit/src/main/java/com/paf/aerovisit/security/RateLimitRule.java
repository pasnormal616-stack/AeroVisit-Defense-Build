package com.paf.aerovisit.security;

import java.time.Duration;
import java.util.List;

import org.springframework.util.AntPathMatcher;

// One row of the limit table: which requests it covers, how many per window, and whether the key is the client IP
// (public pages) or the signed-in username (staff pages: guards at one gate share an IP).
record RateLimitRule(String name, String method, List<String> patterns, int limit, Duration window, boolean perUser) {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    static RateLimitRule perIp(String name, String method, int limit, Duration window, String... patterns) {
        return new RateLimitRule(name, method, List.of(patterns), limit, window, false);
    }

    static RateLimitRule perUser(String name, String method, int limit, Duration window, String... patterns) {
        return new RateLimitRule(name, method, List.of(patterns), limit, window, true);
    }

    boolean matches(String requestMethod, String path) {
        return ("*".equals(method) || method.equalsIgnoreCase(requestMethod))
                && patterns.stream().anyMatch(p -> PATHS.match(p, path));
    }
}
