package com.paf.aerovisit.security;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

// 🚦 At most `limit` requests per key in any `window` (ChatRateLimiter's algorithm, shared). In memory only (one
// server), no extra library. Keys that went quiet are dropped once many are tracked, so a flood of new IPs can't grow
// memory without bound.
public final class SlidingWindowLimiter {

    static final int MAX_TRACKED_KEYS = 10_000;

    private final int limit;
    private final long windowNanos;
    private final LongSupplier clock; // System::nanoTime, or a fake clock in tests
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int limit, Duration window, LongSupplier clock) {
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.clock = clock;
    }

    // True (and counted) while this key has made fewer than `limit` requests in the last window
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (hits.size() > MAX_TRACKED_KEYS) dropIdleKeys(now);
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowNanos) times.pollFirst();
            if (times.size() >= limit) return false;
            times.addLast(now);
            return true;
        }
    }

    // Whole seconds until the oldest counted request leaves the window (at least 1): the Retry-After value
    public long secondsUntilAllowed(String key) {
        Deque<Long> times = hits.get(key);
        if (times == null) return 1;
        synchronized (times) {
            if (times.isEmpty()) return 1;
            long waitNanos = windowNanos - (clock.getAsLong() - times.peekFirst());
            return Math.max(1, (long) Math.ceil(waitNanos / 1_000_000_000.0));
        }
    }

    int trackedKeys() {
        return hits.size();
    }

    private void dropIdleKeys(long now) {
        hits.entrySet().removeIf(e -> {
            Deque<Long> times = e.getValue();
            synchronized (times) {
                return times.isEmpty() || now - times.peekLast() >= windowNanos;
            }
        });
    }
}
