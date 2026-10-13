package com.paf.aerovisit.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** The shared sliding window behind RateLimitFilter, on a fake clock. */
class SlidingWindowLimiterTest {

    private long now = 0;
    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, Duration.ofMinutes(1), () -> now);

    @Test
    void allowsTheLimitThenRefuses() {
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void theWindowSlidesAndRetryAfterCountsDown() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("a");
        now += Duration.ofSeconds(20).toNanos();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.secondsUntilAllowed("a")).isEqualTo(40);
        now += Duration.ofSeconds(40).toNanos();
        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void keysAreCountedSeparately() {
        for (int i = 0; i < 3; i++) limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void idleKeysAreDroppedOnceManyAreTracked() {
        for (int i = 0; i <= SlidingWindowLimiter.MAX_TRACKED_KEYS; i++) limiter.tryAcquire("old-" + i);
        now += Duration.ofMinutes(2).toNanos();
        limiter.tryAcquire("new");
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void anUnknownKeyWaitsAtLeastOneSecond() {
        assertThat(limiter.secondsUntilAllowed("never-seen")).isEqualTo(1);
    }
}
