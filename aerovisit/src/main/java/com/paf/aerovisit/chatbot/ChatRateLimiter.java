package com.paf.aerovisit.chatbot;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.paf.aerovisit.security.ClientIp;

import jakarta.servlet.http.HttpServletRequest;

// 🚦 GEMINI QUOTA PROTECTION: a sliding-window limit per client, e.g. at most 10 chatbot questions in any 60 seconds.
// In memory only (one server), no extra library. Clients that went quiet are dropped once many are tracked.
final class ChatRateLimiter {

    // Past this many tracked clients, idle ones (no request inside the window) are dropped before adding more
    static final int MAX_TRACKED_CLIENTS = 10_000;

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

    // Who is asking: the shared client-IP rule (ClientIp: behind a proxy, the LAST X-Forwarded-For hop, never a
    // browser-supplied one)
    static String clientKey(HttpServletRequest request) {
        return ClientIp.of(request);
    }
}
