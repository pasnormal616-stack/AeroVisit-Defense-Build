package com.paf.aerovisit.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

// 📮 BACKGROUND EMAIL: a click (Approve, Reject, the booking form's Submit) used to wait for the whole SMTP send (log in
// to the mail server, upload, up to 10 s on a slow server) before the page could load. Those emails now go out here,
// after the page has answered. A failed send is logged and handed to `onFailure` (e.g. the Director's bell), because
// nobody is waiting on the page any more to see an error.
//
// Not used for verification codes (OtpService): the visitor is waiting for that code and the form must be able to say
// "we couldn't send it". Not used for the emergency closure and roster batches either: those report to the Director,
// on the same page, exactly how many emails failed, so the people missed can be phoned.
//
// Two sender threads (a slow mail server can't pile up more), up to 1000 queued emails; when the queue is full the
// email is sent on the caller's thread instead, so nothing is ever dropped. On shutdown (a redeploy) the queue is
// drained for up to 30 s before the server stops.
@Component
public class MailOutbox {

    private final ExecutorService pool;

    public MailOutbox() {
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "mail-outbox-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // Sends in the background; never throws. `what` names the email in the log, e.g. "approval email for #12".
    public void send(String what, Runnable email, Runnable onFailure) {
        pool.execute(() -> deliver(what, email, onFailure));
    }

    // For callers whose outbox is optional (web-layer test slices have none): without it the email is sent right away,
    // with the same never-throw failure handling, so behaviour only differs in timing.
    public static void send(MailOutbox outbox, String what, Runnable email, Runnable onFailure) {
        if (outbox != null) outbox.send(what, email, onFailure);
        else deliver(what, email, onFailure);
    }

    static void deliver(String what, Runnable email, Runnable onFailure) {
        try {
            email.run();
        } catch (RuntimeException e) {
            System.err.println("❌ Could not send " + what + ": " + e.getMessage());
            if (onFailure != null) {
                try {
                    onFailure.run();
                } catch (RuntimeException reportError) {
                    System.err.println("⚠️ Could not report the failed " + what + ": " + reportError.getMessage());
                }
            }
        }
    }

    @PreDestroy
    void drain() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            System.err.println("⚠️ Shutting down with emails still queued: " + pool.shutdownNow().size() + " not sent.");
        }
    }
}
