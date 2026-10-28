package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** 📮 MailOutbox: emails leave the click's thread, failures are reported, never thrown. Plain unit tests. */
class MailOutboxTest {

    @Test
    void sendReturnsBeforeASlowMailServerFinishes() throws Exception {
        MailOutbox outbox = new MailOutbox();
        CountDownLatch smtp = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            long start = System.nanoTime();
            outbox.send("test email", () -> {
                try { smtp.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                delivered.countDown();
            }, null);
            // The caller (the page) already has its answer while the "mail server" is still busy
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
            assertThat(delivered.getCount()).isEqualTo(1);
        } finally {
            smtp.countDown();
        }
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        outbox.drain();
    }

    @Test
    void aFailedSendIsReportedNotThrown() throws Exception {
        MailOutbox outbox = new MailOutbox();
        CountDownLatch reported = new CountDownLatch(1);
        outbox.send("test email", () -> { throw new EmailService.MailDeliveryException("SMTP down"); }, reported::countDown);
        assertThat(reported.await(5, TimeUnit.SECONDS)).isTrue();
        outbox.drain();
    }

    @Test
    void withoutAnOutboxTheEmailIsSentRightAwayWithTheSameHandling() {
        AtomicBoolean sent = new AtomicBoolean();
        MailOutbox.send(null, "test email", () -> sent.set(true), null);
        assertThat(sent).isTrue();

        AtomicBoolean reported = new AtomicBoolean();
        MailOutbox.send(null, "test email", () -> { throw new EmailService.MailDeliveryException("SMTP down"); }, () -> reported.set(true));
        assertThat(reported).isTrue(); // and nothing was thrown at the caller
    }

    @Test
    void aBrokenFailureReportNeverThrowsEither() {
        MailOutbox.send(null, "test email",
                () -> { throw new EmailService.MailDeliveryException("SMTP down"); },
                () -> { throw new IllegalStateException("bell unavailable"); });
    }

    @Test
    void shuttingDownWaitsForQueuedEmails() throws Exception {
        MailOutbox outbox = new MailOutbox();
        AtomicInteger sent = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            outbox.send("test email " + i, () -> {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                sent.incrementAndGet();
            }, null);
        }
        outbox.drain(); // a redeploy: the queue is finished before the server stops
        assertThat(sent.get()).isEqualTo(5);
    }
}
