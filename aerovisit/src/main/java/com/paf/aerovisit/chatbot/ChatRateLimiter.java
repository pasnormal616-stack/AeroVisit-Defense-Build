package com.paf.aerovisit.chatbot;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.servlet.http.HttpServletRequest;

// 🚦 GEMINI QUOTA PROTECTION: a sliding-window limit per client, e.g. at most 10 chatbot questions in any 60 seconds.
// In memory only (one server), no extra library. Clients that went quiet are dropped once many are tracked.
final class ChatRateLimiter {

    // Past this many tracked clients, idle ones (no request inside the window) are dropped before adding more
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final int limit;
    private final long windowNanos;
    private final LongSupplier clock; // System::nanoTime, or a fake clock in tests
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    ChatRateLimiter(int limit, Duration window, LongSupplier clock) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    // True (and the request is counted) when this client has made fewer than `limit` requests in the last window
    boolean tryAcquire(String client) {
        long now = clock.getAsLong();
        if (hits.size() > MAX_TRACKED_CLIENTS) {
            dropIdleClients(now);
        }
        Deque<Long> times = hits.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowNanos) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    // Whole seconds until this client's oldest counted request leaves the window (at least 1), for the Retry-After header
    long secondsUntilAllowed(String client) {
        Deque<Long> times = hits.get(client);
        if (times == null) return 1;
        synchronized (times) {
            if (times.isEmpty()) return 1;
            long waitNanos = windowNanos - (clock.getAsLong() - times.peekFirst());
            return Math.max(1, (long) Math.ceil(waitNanos / 1_000_000_000.0));
        }
    }

    int trackedClients() {
        return hits.size();
    }

    private void dropIdleClients(long now) {
        hits.entrySet().removeIf(e -> {
            Deque<Long> times = e.getValue();
            synchronized (times) {
                return times.isEmpty() || now - times.peekLast() >= windowNanos;
            }
        });
    }

    // Who is asking: the connecting IP. Behind a local reverse proxy (ngrok's agent connects from 127.0.0.1) every
    // visitor would share one IP, so for loopback connections the LAST X-Forwarded-For entry is used: the one the
    // proxy itself added. Earlier entries are whatever the browser sent and could be faked to dodge the limit.
    static String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (remote != null && LOOPBACK.contains(remote) && forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String proxyAdded = hops[hops.length - 1].trim();
            if (!proxyAdded.isEmpty()) return proxyAdded;
        }
        return remote == null ? "unknown" : remote;
    }
}
