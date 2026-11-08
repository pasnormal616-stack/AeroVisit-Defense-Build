package com.paf.aerovisit.service;

import com.paf.aerovisit.controller.GuardOperationController;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * 📝 THE FEEDBACK EMAIL, EXACTLY ONCE PER BOOKING. Every path sends it through sendOnce(): the 15-minute sweep (G minutes
 * after the booked window ends, Director setting, default 30), a guard's check-out (scan or manual) and the 5:30 PM /
 * 11:59 PM auto check-out. sendOnce() claims the send with one conditional UPDATE (claimFeedbackEmail, like GateClaim),
 * so the sweep and a check-out at the same moment can't both email. The visitor's status is never changed here:
 * check-out stays the guards' (and the auto check-out's) job, and Inside Now stays the record of who is inside.
 */
@Service
public class FeedbackEmails {

    public static final int MIN_DELAY = 0, MAX_DELAY = 180;

    // Only visits that really happened: the sweep never emails a no-show, a cancelled, denied, released or
    // never-checked-in booking (those statuses are simply not read)
    static final Set<String> ATTENDED_STATUSES = Set.of("CHECKED-IN", "CHECKED-OUT");

    private final VisitorRepository repo;
    private final EmailService emailService;

    // Optional, so test slices without them still start: without the outbox the email goes out right away; without
    // the settings the default delay applies
    @Autowired(required = false)
    MailOutbox mailOutbox;

    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    public FeedbackEmails(VisitorRepository repo, EmailService emailService) {
        this.repo = repo;
        this.emailService = emailService;
    }

    // --- Settings ---

    public int delayMinutes() {
        if (settingsRepo == null) return PortalSettings.DEFAULT_FEEDBACK_DELAY_MINUTES;
        try {
            return settingsRepo.findById(PortalSettings.SINGLETON_ID).map(PortalSettings::feedbackDelay)
                    .orElse(PortalSettings.DEFAULT_FEEDBACK_DELAY_MINUTES);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Feedback email setting could not be read; using the default. " + e.getMessage());
            return PortalSettings.DEFAULT_FEEDBACK_DELAY_MINUTES;
        }
    }

    public void saveDelay(int minutes) {
        PortalSettings s = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        s.setFeedbackDelayMinutes(minutes);
        settingsRepo.save(s);
    }

    public static String delayError(int minutes) {
        return minutes < MIN_DELAY || minutes > MAX_DELAY
                ? "The feedback email delay must be a whole number of minutes from " + MIN_DELAY + " to " + MAX_DELAY + "." : null;
    }

    // --- Sending ---

    // Sends the feedback email unless this booking already had it; true when this call sent it
    public boolean sendOnce(Visitor v, LocalDateTime now) {
        if (v == null || v.getId() == null || v.getFeedbackEmailSentAt() != null) return false;
        if (repo.claimFeedbackEmail(v.getId(), now) != 1) return false; // the sweep or another gate got there first
        v.setFeedbackEmailSentAt(now);
        send(mailOutbox, emailService, v);
        return true;
    }

    // The same for callers that may have no FeedbackEmails (web test slices, an engine built in a unit test): without it
    // the booking's own timestamp is the only check, as no database is there to claim against
    public static boolean sendOnce(FeedbackEmails feedback, EmailService emailService, Visitor v, LocalDateTime now) {
        if (feedback != null) return feedback.sendOnce(v, now);
        if (v == null || v.getFeedbackEmailSentAt() != null) return false;
        v.setFeedbackEmailSentAt(now);
        send(null, emailService, v);
        return true;
    }

    // Through the outbox like the other emails (the token is never in the label); a failure is logged, never thrown
    private static void send(MailOutbox outbox, EmailService emailService, Visitor v) {
        String to = v.getEmail(), name = v.getName(), token = v.getSecureToken();
        MailOutbox.send(outbox, "feedback email for visitor #" + v.getId(), () -> emailService.sendCheckOutFeedback(to, name, token), null);
    }

    // 🕒 THE SWEEP: today's checked-in visits whose booked window ended at least G minutes ago get their email. A window
    // that can't be read (older bookings) is skipped here; their check-out or the 11:59 PM auto check-out sends it.
    // One failure never stops the rest.
    public int sendDue(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int delay = delayMinutes();
        int sent = 0;
        for (Visitor v : repo.findByVisitDateAndStatusInAndFeedbackEmailSentAtIsNull(today, ATTENDED_STATUSES)) {
            try {
                if (!today.equals(v.getVisitDate()) || v.getStatus() == null || !ATTENDED_STATUSES.contains(v.getStatus())) continue;
                LocalTime[] window = GuardOperationController.parseTimeWindow(v.getTimeWindow());
                if (window == null) continue;
                if (now.isBefore(today.atTime(window[1]).plusMinutes(delay))) continue;
                if (sendOnce(v, now)) sent++;
            } catch (RuntimeException e) {
                System.err.println("❌ Feedback email for visitor #" + v.getId() + " skipped: " + e.getMessage());
            }
        }
        return sent;
    }
}
