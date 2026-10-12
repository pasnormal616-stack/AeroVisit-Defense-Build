package com.paf.aerovisit.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Sliding-window chatbot limiter: 10 per client per rolling minute. Fake clock, no Spring. */
class ChatRateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final ChatRateLimiter limiter = new ChatRateLimiter(10, Duration.ofMinutes(1), now::get);

    @Test
    void tenRequestsAMinutePassAndTheEleventhIsRefused() {
        for (int i = 0; i < 10; i++) assertThat(limiter.tryAcquire("203.0.113.5")).as("request " + (i + 1)).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.5")).isFalse();
        assertThat(limiter.tryAcquire("203.0.113.5")).as("refused requests are not counted, still refused").isFalse();
    }

    @Test
    void theWindowSlidesInsteadOfResettingOnTheMinute() {
        for (int i = 0; i < 5; i++) limiter.tryAcquire("a");     // 5 at t=0
        now.addAndGet(30 * SECOND);
        for (int i = 0; i < 5; i++) limiter.tryAcquire("a");     // 5 more at t=30s
        assertThat(limiter.tryAcquire("a")).isFalse();

        now.addAndGet(29 * SECOND);                              // t=59s: the first five are still inside the window
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.secondsUntilAllowed("a")).isEqualTo(1);

        now.addAndGet(1 * SECOND);                               // t=60s: the first five have left
        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();           // the five from t=30s still count
    }

    @Test
    void retryAfterCountsDownToTheOldestRequestLeavingTheWindow() {
        for (int i = 0; i < 10; i++) limiter.tryAcquire("a");
        assertThat(limiter.secondsUntilAllowed("a")).isEqualTo(60);
        now.addAndGet(45 * SECOND + SECOND / 2);
        assertThat(limiter.secondsUntilAllowed("a")).isEqualTo(15);   // 14.5s rounded up
    }

    @Test
    void eachClientHasItsOwnAllowance() {
        for (int i = 0; i < 10; i++) limiter.tryAcquire("198.51.100.1");
        assertThat(limiter.tryAcquire("198.51.100.1")).isFalse();
        assertThat(limiter.tryAcquire("198.51.100.2")).isTrue();
    }

    @Test
    void fiftySimultaneousRequestsFromOneClientLetExactlyTenThrough() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            results.add(pool.submit(() -> { go.await(); return limiter.tryAcquire("burst"); }));
        }
        go.countDown();
        int allowed = 0;
        for (Future<Boolean> r : results) if (r.get(10, TimeUnit.SECONDS)) allowed++;
        pool.shutdownNow();
        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void idleClientsAreDroppedSoMemoryStaysBounded() {
        for (int i = 0; i <= ChatRateLimiter.MAX_TRACKED_CLIENTS; i++) limiter.tryAcquire("old-" + i);
        assertThat(limiter.trackedClients()).isEqualTo(ChatRateLimiter.MAX_TRACKED_CLIENTS + 1);

        now.addAndGet(61 * SECOND);                              // all of them went quiet
        limiter.tryAcquire("new-client");
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    // --- Who is the client? ---

    private static MockHttpServletRequest from(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chatbot/ask");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }

    @Test
    void aDirectConnectionIsKeyedByItsOwnIpAndAForgedHeaderIsIgnored() {
        assertThat(ChatRateLimiter.clientKey(from("203.0.113.9", null))).isEqualTo("203.0.113.9");
        assertThat(ChatRateLimiter.clientKey(from("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    void behindTheLocalProxyTheIpTheProxyAddedIsUsed() {
        // ngrok's agent connects from localhost; it adds the visitor's real IP as the last X-Forwarded-For hop
        assertThat(ChatRateLimiter.clientKey(from("127.0.0.1", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(ChatRateLimiter.clientKey(from("0:0:0:0:0:0:0:1", "198.51.100.7"))).isEqualTo("198.51.100.7");
        // a visitor who sends their own fake header can't pick their key: only the proxy's last hop counts
        assertThat(ChatRateLimiter.clientKey(from("127.0.0.1", "6.6.6.6, 198.51.100.7"))).isEqualTo("198.51.100.7");
        // plain localhost use (no proxy) still works
        assertThat(ChatRateLimiter.clientKey(from("127.0.0.1", null))).isEqualTo("127.0.0.1");
        assertThat(ChatRateLimiter.clientKey(from("127.0.0.1", " , "))).isEqualTo("127.0.0.1");
    }
}
