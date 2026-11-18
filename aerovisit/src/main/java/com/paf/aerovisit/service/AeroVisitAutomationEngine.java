package com.paf.aerovisit.service;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AeroVisitAutomationEngine {

    private final VisitorRepository repo;
    private final EmailService emailService;

    // Inbox for the Director's daily report (aerovisit.alerts.email: ALERT_EMAIL, else the system's sending address)
    @org.springframework.beans.factory.annotation.Value("${aerovisit.alerts.email:}")
    String alertEmail;

    // 🔔 Header bell: arrivals this morning, and the 30-day clean-up. Optional, so tests building the engine by hand still work.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    NotificationService notificationService;

    // The museum contact email (Settings) shown in, and set as Reply-To of, the "Booking Released" email. Optional.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    PaymentWorkflow paymentWorkflow;

    // 📝 The feedback email, once per booking (sweep + auto check-out). Optional, so engines built in unit tests still
    // work: without it the booking's own timestamp guards the send.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    FeedbackEmails feedbackEmails;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.paf.aerovisit.repository.AdminNotificationRepository notificationRepo;

    // A single, clean constructor that perfectly links both the database and the email sender
    public AeroVisitAutomationEngine(VisitorRepository repo, EmailService emailService) {
        this.repo = repo;
        this.emailService = emailService;
    }

    // =========================================================================
    // 1. 🌅 AUTOMATED VISITOR REMINDERS (Runs at 7:00 AM)
    // =========================================================================
    @Scheduled(cron = "0 0 7 * * ?") 
    public void executeDailyReminders() {
        System.out.println("🤖 [AUTOMATION] Waking Up: Checking for tomorrow's visitors...");
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        
        List<Visitor> visitorsToRemind = repo.findByVisitDateAndStatusAndReminderSentFalse(tomorrow, "APPROVED");

        if (visitorsToRemind.isEmpty()) {
            System.out.println("📭 No new reminders to send for " + tomorrow);
            return;
        }

        for (Visitor v : visitorsToRemind) {
            if (v.getQrCodeUrl() != null) {
                emailService.sendReminderEmailWithQR(v.getEmail(), v.getName(), tomorrow.toString(), v.getQrCodeUrl());
                v.setReminderSent(true);
                repo.save(v);
            }
        }
        System.out.println("✅ Automated Reminder Cycle Complete.");
    }

    // 🔔 6:30 AM: large groups arriving today go to the Guard's and Director's bell
    @Scheduled(cron = "0 30 6 * * ?")
    public void executeArrivalAlerts() {
        if (notificationService == null) return;
        for (Visitor v : repo.findByVisitDate(LocalDate.now())) {
            if ("APPROVED".equals(v.getStatus())) notificationService.arrivingToday(v); // only big groups; never throws
        }
    }

    // =========================================================================
    // 2. 📊 END-OF-DAY DIRECTOR REPORT (Runs at 12:00 AM)
    // =========================================================================
    // Midnight, after the 11:59 PM sweep, so night-window visits are complete. It reports the day that just ended.
    @Scheduled(cron = "0 0 0 * * *")
    public void executeDirectorReport() {
        directorReport(LocalDateTime.now());
    }

    // The report's day: a run before noon (the midnight schedule) covers yesterday; a run after noon (e.g. a cron
    // temporarily moved for testing) covers today
    static LocalDate reportDay(LocalDateTime now) {
        return now.toLocalTime().isBefore(java.time.LocalTime.NOON) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    void directorReport(LocalDateTime now) {
        System.out.println("📊 [AUTOMATION] Compiling Daily Security Dispatch...");
        LocalDate today = reportDay(now);
        
        // Expected = the day's approved demand (same four statuses as the Analytics funnel); the rows below are parts of it
        List<Visitor> expected = repo.findByVisitDate(today).stream()
            .filter(v -> v.getStatus() != null && EXPECTED_STATUSES.contains(v.getStatus())).toList();
        // Everyone who entered: still inside, or already checked out (by the guard or the 11:59 PM sweep)
        List<Visitor> checkedIn = expected.stream().filter(v -> "CHECKED-IN".equals(v.getStatus()) || "CHECKED-OUT".equals(v.getStatus())).toList();
        List<Visitor> noShows = expected.stream().filter(v -> "NO_SHOW".equals(v.getStatus())).toList();
        // Still APPROVED at midnight: a label the sweep can't read (e.g. an older booking's "Master Booking Bypass") is resolved the next night
        long stillOpen = expected.stream().filter(v -> "APPROVED".equals(v.getStatus())).count();
        LocalDate nextDay = today.plusDays(1);
        long pendingNextDay = repo.findByVisitDate(nextDay).stream().filter(Visitor::awaitsApproval).count();

        // 📬 The system alert inbox (aerovisit.alerts.email in application.properties), never an address in the code
        if (alertEmail == null || alertEmail.isBlank()) {
            System.err.println("⚠️ Director Dispatch skipped: no alert inbox configured (set ALERT_EMAIL).");
            return;
        }
        emailService.sendDirectorDailyReport(alertEmail, new EmailService.DailyReport(today.toString(),
            expected.size(), guests(expected), checkedIn.size(), guests(checkedIn), noShows.size(), guests(noShows),
            stillOpen, repo.countTotalNoShows(), nextDay.toString(), pendingNextDay, insights(checkedIn),
            walkInPayments(repo.findByBookingCategoryAndVisitDate(Visitor.CATEGORY_WALK_IN, today))));
        System.out.println("✅ Director Dispatch successfully sent to: " + alertEmail);
    }

    static final Set<String> EXPECTED_STATUSES = Set.of("APPROVED", "CHECKED-IN", "CHECKED-OUT", "NO_SHOW");

    private static final DateTimeFormatter VERIFIED_AT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    // 🚶 The day's walk-ins that held seats (same statuses as Expected), and their payments verified as paid: count, total,
    // and each billing number with who verified it, by billing number
    static EmailService.WalkInPayments walkInPayments(List<Visitor> walkIns) {
        List<Visitor> held = walkIns.stream().filter(v -> v.getStatus() != null && EXPECTED_STATUSES.contains(v.getStatus())).toList();
        List<Visitor> paid = walkIns.stream().filter(v -> Visitor.PAY_PAID.equals(v.getPaymentStatus()))
            .sorted(Comparator.comparing(Visitor::getBillingNumber, Comparator.nullsLast(Comparator.<String>naturalOrder()))).toList();
        if (held.isEmpty() && paid.isEmpty()) return EmailService.WalkInPayments.NONE;
        long total = paid.stream().mapToLong(Visitor::amountDueCentavos).sum();
        return new EmailService.WalkInPayments(held.size(), guests(held), paid.size(), com.paf.aerovisit.util.Peso.format(total), paid.stream()
            .map(v -> new EmailService.WalkInPayment(v.getBillingNumber(), v.amountDueText(),
                    v.getPaymentVerifiedBy() == null ? "" : v.getPaymentVerifiedBy(),
                    v.getPaymentVerifiedAt() == null ? "" : VERIFIED_AT.format(v.getPaymentVerifiedAt())))
            .toList());
    }

    private static long guests(List<Visitor> visitors) {
        return visitors.stream().mapToLong(v -> Math.max(1, v.getGroupSize())).sum();
    }

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    // Visit insights from data the Visitor row already holds (no schema change). Actual attendees only (CHECKED-IN / CHECKED-OUT).
    static EmailService.Insights insights(List<Visitor> checkedIn) {
        // Peak arrival hour: guests per check-in hour (same weighting as the Analytics heatmap); a tie goes to the earlier hour
        Map<Integer, Long> guestsByHour = checkedIn.stream().filter(v -> v.getCheckInTime() != null)
            .collect(Collectors.groupingBy(v -> v.getCheckInTime().getHour(), TreeMap::new,
                     Collectors.summingLong(v -> Math.max(1, v.getGroupSize()))));
        Map.Entry<Integer, Long> peak = guestsByHour.entrySet().stream()
            .max(Map.Entry.<Integer, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
            .orElse(null);
        String peakHour = peak == null ? null
            : HOUR.format(LocalTime.of(peak.getKey(), 0)) + " - " + HOUR.format(LocalTime.of(peak.getKey(), 0).plusHours(1));

        // Visit duration: guard check-outs only. A sweep check-out is stamped with the sweep's time (5:30 PM or 11:59 PM),
        // not the visitor's real exit, and autoCheckOut marks it "(Auto-Sweep)" in checkedInBy
        long[] minutes = checkedIn.stream()
            .filter(v -> v.getCheckInTime() != null && v.getCheckOutTime() != null && !v.getCheckOutTime().isBefore(v.getCheckInTime()))
            .filter(v -> v.getCheckedInBy() == null || !v.getCheckedInBy().contains("(Auto-Sweep)"))
            .mapToLong(v -> Duration.between(v.getCheckInTime(), v.getCheckOutTime()).toMinutes()).toArray();
        Long avgMinutes = minutes.length == 0 ? null : Math.round(Arrays.stream(minutes).average().getAsDouble());

        // Satisfaction: ratings this day's visitors have sent so far (a rating has no timestamp of its own)
        IntSummaryStatistics ratings = checkedIn.stream().map(Visitor::getRating).filter(Objects::nonNull)
            .mapToInt(Integer::intValue).summaryStatistics();
        Double avgRating = ratings.getCount() == 0 ? null : Math.round(ratings.getAverage() * 10.0) / 10.0;

        // Demographics describe the lead visitor of each booking (companions are stored as names only)
        return new EmailService.Insights(peakHour, peak == null ? 0 : peak.getValue(), avgMinutes, minutes.length,
            countBy(checkedIn, Visitor::getVisitorType), countBy(checkedIn, Visitor::getSex), avgRating, ratings.getCount());
    }

    // Lead visitors per value, largest first (ties alphabetical); a blank value is "Not stated"
    private static Map<String, Long> countBy(List<Visitor> visitors, Function<Visitor, String> field) {
        return visitors.stream()
            .collect(Collectors.groupingBy(v -> { String s = field.apply(v); return s == null || s.isBlank() ? "Not stated" : s.trim(); },
                     Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    // =========================================================================
    // 3. 🧹 AUTOMATED BASE SWEEP & FEEDBACK EMAIL (Runs at 5:30 PM)
    // =========================================================================
    @Scheduled(cron = "0 30 17 * * ?") 
    public void executeBaseSweep() {
        System.out.println("🧹 [AUTOMATION] Initiating End-of-Day Base Sweep...");
        // 🌙 Only visitors whose time window has ended: a night-window visitor still inside stays CHECKED-IN
        int count = autoCheckOut(LocalDateTime.now(), false);
        
        if (count > 0) {
            System.out.println("✅ [SWEEP COMPLETE] " + count + " civilian(s) were automatically checked out.");
        } else {
            System.out.println("✅ [SWEEP COMPLETE] Base is clear. Occupancy is 0.");
        }
    }

    // =========================================================================
    // 3a. 💳 PAYMENT DEADLINE SWEEP (every 15 minutes)
    // =========================================================================
    // Built like the no-show sweep. A booking still waiting for payment (AWAITING_PAYMENT, or its receipt was rejected)
    // whose deadline has passed becomes PAYMENT_EXPIRED: not seat-holding, so its seats free up at once. That covers pay
    // first (AWAITING_RECEIPT, and a pending booking whose receipt was rejected) and older approve-then-pay bookings
    // (APPROVED). The visitor is emailed and the Director's bell is told. A booking whose receipt was submitted
    // (PROOF_SUBMITTED) is never expired automatically: the Director decides. The receipt files are kept.
    static final java.util.Set<String> EXPIRABLE_PAYMENT_STATUSES = Set.of(Visitor.PAY_AWAITING, Visitor.PAY_REJECTED);
    static final java.util.Set<String> EXPIRABLE_BOOKING_STATUSES = Set.of(Visitor.STATUS_AWAITING_RECEIPT, "APPROVED",
            "PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED");

    @Scheduled(cron = "0 */15 * * * *")
    public void executePaymentDeadlineSweep() {
        int expired = expireUnpaidBookings(LocalDateTime.now());
        if (expired > 0) {
            System.out.println("💳 [PAYMENT SWEEP] " + expired + " unpaid booking(s) released at their payment deadline.");
        }
        int late = releaseLateWalkIns(LocalDateTime.now());
        if (late > 0) {
            System.out.println("🚶 [WALK-IN SWEEP] " + late + " walk-in(s) not checked in by their deadline released (NO_SHOW).");
        }
    }

    // 🚶 A walk-in not checked in by its check-in deadline (free: the walk-in minutes after booking; paid: the window's end)
    // stops holding today's seats: NO_SHOW, like the nightly no-show sweep, but within 15 minutes. Advance bookings are
    // left to the nightly sweep.
    int releaseLateWalkIns(LocalDateTime now) {
        int count = 0;
        for (Visitor v : repo.findByBookingCategoryAndStatusAndCheckInDueAtBefore(Visitor.CATEGORY_WALK_IN, "APPROVED", now)) {
            if (!v.walkIn() || !"APPROVED".equals(v.getStatus()) || v.getCheckInDueAt() == null || !now.isAfter(v.getCheckInDueAt())) continue;
            // 🔒 Only if still APPROVED in the database: a guard who checked the group in after this list was read wins
            if (repo.releaseIfStatus(v.getId(), "APPROVED", "NO_SHOW") != 1) continue;
            v.setStatus("NO_SHOW");
            v.setQrCodeUrl(null);
            count++;
        }
        return count;
    }

    int expireUnpaidBookings(LocalDateTime now) {
        int count = 0;
        String contactEmail = null;
        for (Visitor v : repo.findByStatusInAndPaymentStatusIn(EXPIRABLE_BOOKING_STATUSES, EXPIRABLE_PAYMENT_STATUSES)) {
            if (v.getStatus() == null || !EXPIRABLE_BOOKING_STATUSES.contains(v.getStatus())
                    || !EXPIRABLE_PAYMENT_STATUSES.contains(v.getPaymentStatus())) continue;
            if (v.getPaymentDueAt() == null || !now.isAfter(v.getPaymentDueAt())) continue;
            // 🔒 Only if the database still says unpaid: a receipt uploaded (or a payment verified) after this list was read
            // wins, and gets no "released" email
            if (repo.expireIfStillUnpaid(v.getId(), v.getStatus(), EXPIRABLE_PAYMENT_STATUSES, now) != 1) continue;
            v.setStatus(Visitor.STATUS_PAYMENT_EXPIRED);
            v.setQrCodeUrl(null);
            count++;
            if (count == 1 && paymentWorkflow != null) contactEmail = paymentWorkflow.contactEmail(); // read once per sweep
            emailService.sendPaymentExpired(EmailService.PaymentNotice.of(v, contactEmail)); // logs a failure, never throws
            if (notificationService != null) notificationService.paymentExpired(v); // never throws
        }
        return count;
    }

    // =========================================================================
    // 3c. 📝 FEEDBACK EMAIL SWEEP (every 15 minutes)
    // =========================================================================
    // A visit's feedback email goes out G minutes (Director setting, default 30) after its booked time window ends,
    // whether or not the guard checked the visitor out, for advance bookings and walk-ins. Check-out is unchanged:
    // the visitor stays CHECKED-IN (Inside Now) until a guard or the 5:30 PM / 11:59 PM auto check-out.
    @Scheduled(cron = "0 */15 * * * *")
    public void executeFeedbackEmailSweep() {
        if (feedbackEmails == null) return;
        try {
            int sent = feedbackEmails.sendDue(LocalDateTime.now());
            if (sent > 0) System.out.println("📝 [FEEDBACK SWEEP] " + sent + " feedback email(s) sent after the visit window ended.");
        } catch (RuntimeException e) {
            System.err.println("❌ Feedback email sweep failed this round: " + e.getMessage());
        }
    }

    // =========================================================================
    // 3b. 🚫 NO-SHOW SWEEP (Runs at 11:59 PM)
    // =========================================================================
    // Kept apart from the 5:30 PM sweep because Director-configured time windows can run into the night. A window
    // can't cross midnight (TimeWindowController), so at 11:59 PM every window ending by 11:58 PM is over; one ending
    // at exactly 11:59 PM is still open and is caught the next night as an earlier date.
    @Scheduled(cron = "0 59 23 * * *")
    public void executeNoShowSweep() {
        // 🌙 End of day: anyone still checked in for today (or earlier) is checked out, night windows included
        int checkedOut = autoCheckOut(LocalDateTime.now(), true);
        if (checkedOut > 0) {
            System.out.println("🌙 [NIGHT SWEEP] " + checkedOut + " civilian(s) still inside were automatically checked out.");
        }
        int noShows = markNoShows(LocalDateTime.now());
        System.out.println("🚫 [NO-SHOW SWEEP] " + noShows + " approved booking(s) marked NO_SHOW (visit over, never checked in).");
    }

    // =========================================================================
    // 4. 🗑️ DATA RETENTION PURGE (Runs nightly at 2:00 AM)
    // =========================================================================
    // Finished visits keep their database row (analytics, reports, audit) but lose their ID photos, QR code, payment
    // receipts and any legacy face data, and have their personal details anonymized, once the visit date is more than
    // RETENTION_DAYS days old. DENIED_ENTRY is purged too (decided 2026-10-18): a gate denial follows the same 30 days. A
    // person who must be kept out for longer is banned (Ban Visitor: email, phone and name, no photo).
    static final int RETENTION_DAYS = 30;
    static final Set<String> PURGEABLE_STATUSES = Set.of("CHECKED-OUT", "NO_SHOW", "CANCELLED", "REJECTED",
            "CANCELLED_BY_VISITOR", "CANCELLED_OVERRIDE", "DENIED_ENTRY", Visitor.STATUS_PAYMENT_EXPIRED,
            Visitor.STATUS_RESCHEDULE_REQUIRED);

    // What the purge writes over a visitor's personal details (kept in sync with VisitorRepository.findExpiredWithFiles)
    static final String REDACTED = "REDACTED";
    static final String EXPIRED_UNREVIEWED_REASON = "Expired: the request was not reviewed before the visit date.";

    // Face bans (optional, so test slices without the repository still start; without it no ban is auto-lifted)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.paf.aerovisit.repository.BlacklistedFaceRepository faceBans;

    // ☁️ S3 copies of uploads/ (off locally): the purge deletes them with the local files
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    UploadMirror uploadMirror;

    // 💬 Plain SQL for chat_logs (written by the AI service, so there is no JPA entity for it). Optional: test slices
    // without a database still start, and then no chat message is purged
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 2 * * ?")
    public void executeDataRetentionPurge() {
        LocalDate today = LocalDate.now();
        Path uploads = Paths.get("uploads");
        System.out.println("🗑️ [AUTOMATION] Data retention purge: anonymizing visits and removing ID photos and QR codes older than " + RETENTION_DAYS + " days...");
        int expired = expireUnreviewedBookings(today);
        System.out.println("⌛ [RETENTION] " + expired + " unreviewed request(s) past their visit date expired (paid ones: Reschedule Needed).");
        int reminded = remindToReschedule(today);
        if (reminded > 0) System.out.println("📅 [RETENTION] " + reminded + " reminder(s) sent to pick a new date.");
        int purged = purgeExpiredFiles(today, uploads);
        System.out.println("✅ [PURGE COMPLETE] " + purged + " finished visit(s) anonymized and cleared of ID photo / QR files.");
        int passports = purgePassportReviews(LocalDateTime.now(), uploads);
        System.out.println("🛂 [RETENTION] " + passports + " passport review(s) older than " + RETENTION_DAYS + " days cleared of their file and personal details.");
        int lifted = liftExpiredBans(today, uploads);
        System.out.println("🧬 [RETENTION] " + lifted + " expired temporary ban(s) lifted, mugshot and face vector deleted.");
        int chats = purgeOldChatLogs(LocalDateTime.now().minusDays(RETENTION_DAYS));
        System.out.println("💬 [RETENTION] " + chats + " chatbot message(s) older than " + RETENTION_DAYS + " days deleted.");
        if (notificationRepo != null) {
            try {
                int bell = notificationRepo.deleteOlderThan(LocalDateTime.now().minusDays(RETENTION_DAYS));
                System.out.println("🔔 [RETENTION] " + bell + " bell notification(s) older than " + RETENTION_DAYS + " days deleted.");
            } catch (RuntimeException e) {
                System.err.println("⚠️ [RETENTION] bell notifications not purged: " + e.getMessage());
            }
        }
    }

    // 📅 One reminder, 7 days before the deadline (or later if missed), to a RESCHEDULE_REQUIRED booking still without a
    // new date. reminderSent marks it sent (reset when the booking became RESCHEDULE_REQUIRED). Returns the reminders sent.
    static final int RESCHEDULE_REMINDER_DAYS = 7;

    int remindToReschedule(LocalDate today) {
        int count = 0;
        for (Visitor v : repo.findByStatus(Visitor.STATUS_RESCHEDULE_REQUIRED)) {
            LocalDate deadline = v.rescheduleDeadline();
            if (deadline == null || v.isReminderSent() || deadline.isBefore(today)
                    || today.isBefore(deadline.minusDays(RESCHEDULE_REMINDER_DAYS))) continue;
            try {
                emailService.sendRescheduleNeeded(v, true);
            } catch (RuntimeException e) {
                System.err.println("❌ Reschedule reminder failed for visitor #" + v.getId() + ": " + e.getMessage());
                continue; // tried again next night
            }
            v.setReminderSent(true);
            repo.save(v);
            count++;
        }
        return count;
    }

    // 💬 Chatbot messages (table chat_logs, written by the AI service) older than the retention period are deleted:
    // visitors sometimes type personal details into the chat. A missing table (the AI service never ran) is skipped,
    // so the rest of the nightly purge is never blocked. Returns the messages deleted.
    int purgeOldChatLogs(LocalDateTime cutoff) {
        if (jdbc == null) return 0;
        try {
            return jdbc.update("DELETE FROM chat_logs WHERE `timestamp` < ?", java.sql.Timestamp.valueOf(cutoff));
        } catch (org.springframework.dao.DataAccessException e) {
            System.err.println("⚠️ [RETENTION] chat_logs not purged: " + e.getMostSpecificCause().getMessage());
            return 0;
        }
    }

    // ⌛ GAP 1: a request still waiting for review (PENDING, PENDING_MANUAL_REVIEW, VERIFIED) whose visit date has passed
    // can never be approved, so it becomes REJECTED with an "expired" reason. REJECTED is already a terminal, seat-free,
    // purgeable status everywhere (NO_SHOW would count it as approved demand in the analytics), so the purge clears its
    // photos and details 30 days later like any other finished visit. Never emails anyone. Returns the requests expired.
    // 💳 A PAID request (receipt submitted, or already marked paid) is not rejected: the museum didn't review it in time,
    // so it becomes RESCHEDULE_REQUIRED like a closure. The visitor keeps the payment, is emailed to pick a new date in
    // Manage Booking (within Visitor.RESCHEDULE_DAYS), and the Director's bell is told.
    int expireUnreviewedBookings(LocalDate today) {
        int count = 0;
        for (Visitor v : repo.findByStatusInAndVisitDateBefore(Visitor.APPROVABLE_STATUSES, today)) {
            if (!v.awaitsApproval() || v.getVisitDate() == null || !v.getVisitDate().isBefore(today)) continue;
            if (v.paymentRequired() && v.paymentInHand()) {
                v.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
                v.setRejectionReason(Visitor.UNREVIEWED_NOTE);
                v.setQrCodeUrl(null);
                v.setReminderSent(false);
                repo.save(v);
                count++;
                try {
                    emailService.sendRescheduleNeeded(v, false);
                } catch (RuntimeException e) {
                    System.err.println("❌ Reschedule email failed for visitor #" + v.getId() + ": " + e.getMessage());
                }
                if (notificationService != null) notificationService.unreviewedPaidRequest(v); // never throws
                continue;
            }
            v.setStatus("REJECTED");
            v.setRejectionReason(EXPIRED_UNREVIEWED_REASON);
            repo.save(v);
            count++;
        }
        return count;
    }

    // 🧬 GAP 2: a temporary face ban whose last day has passed is deleted with its mugshot copy (uploads/ban-<uuid>.jpg),
    // which removes the stored face vector too, exactly like "Lift ban" on the Banned Visitors page. Uses the same
    // BlacklistedFace.activeOn rule as the booking and gate checks, so a ban is enforced through its last day.
    // Permanent bans (no expiry date) are never touched. A mugshot that can't be deleted now keeps its ban for a retry
    // next night (an expired ban no longer blocks anyone). Returns the bans lifted.
    int liftExpiredBans(LocalDate today, Path uploadDir) {
        if (faceBans == null) return 0;
        int lifted = 0;
        for (com.paf.aerovisit.model.BlacklistedFace ban : faceBans.findAll()) {
            if (ban.getExpiresAt() == null || ban.activeOn(today)) continue;
            if (ban.getImageUrl() != null && !removeFile(uploadDir, ban.getImageUrl())) continue;
            faceBans.delete(ban);
            lifted++;
        }
        return lifted;
    }

    // Deletes each expired visit's selfie, ID close-up and QR files from uploadDir, clears those fields and the face
    // vector, and anonymizes the personal details; returns the visits changed.
    // A field is only cleared once its file is really gone (deleted now, or already missing), so a locked file is retried next night.
    int purgeExpiredFiles(LocalDate today, Path uploadDir) {
        LocalDate cutoff = today.minusDays(RETENTION_DAYS);
        int count = 0;
        for (Visitor v : repo.findExpiredWithFiles(PURGEABLE_STATUSES, cutoff)) {
            if (!PURGEABLE_STATUSES.contains(v.getStatus()) || v.getVisitDate() == null || !v.getVisitDate().isBefore(cutoff)) continue;
            boolean changed = anonymize(v);
            if (v.getIdImageUrl() != null && removeFile(uploadDir, v.getIdImageUrl())) {
                v.setIdImageUrl(null);
                changed = true;
            }
            if (v.getQrCodeUrl() != null && removeFile(uploadDir, v.getQrCodeUrl())) {
                v.setQrCodeUrl(null);
                changed = true;
            }
            if (v.getIdCardImageUrl() != null && removeFile(uploadDir, v.getIdCardImageUrl())) {
                v.setIdCardImageUrl(null);
                changed = true;
            }
            if (v.getIdFaceCropUrl() != null && removeFile(uploadDir, v.getIdFaceCropUrl())) {
                v.setIdFaceCropUrl(null);
                changed = true;
            }
            // 🛂 A Foreign visitor's passport copy is their ID: it goes with the ID photos
            if (v.getPassportFileUrl() != null && removeFile(uploadDir, v.getPassportFileUrl())) {
                v.setPassportFileUrl(null);
                changed = true;
            }
            // 💳 The payment receipt screenshot goes with the ID photos (the billing number and amount stay for audit)
            if (v.getReceiptImageUrl() != null && removeFile(uploadDir, v.getReceiptImageUrl())) {
                v.setReceiptImageUrl(null);
                changed = true;
            }
            // 🧾 and so do the earlier receipts a re-upload kept (cleared only once every file is gone)
            if (v.getEarlierReceiptUrls() != null) {
                boolean allGone = true;
                for (String earlier : v.earlierReceiptList()) allGone &= removeFile(uploadDir, earlier);
                if (allGone) {
                    v.setEarlierReceiptUrls(null);
                    changed = true;
                }
            }
            // Legacy face vector (bookings made before KYC was removed): biometric data, so it goes with the photos
            if (v.getFaceEncoding() != null) {
                v.setFaceEncoding(null);
                changed = true;
            }
            if (changed) {
                repo.save(v);
                count++;
            }
        }
        return count;
    }

    // 🛂 Passport reviews (Foreign visitors, before an advance booking) no longer waiting for the Director, with nothing
    // done to them for RETENTION_DAYS days (rejected and never re-sent, an approval link never used or long expired, or
    // used for a booking): the passport file goes (local + S3; a USED review's file already belongs to its booking, which
    // the booking purge above removes), the name, email and phone are overwritten, and the emailed link stops working.
    // A PENDING review is never touched. Optional repository: test slices without it purge nothing. Returns the reviews changed.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.paf.aerovisit.repository.PassportReviewRepository passportReviews;

    int purgePassportReviews(LocalDateTime now, Path uploadDir) {
        if (passportReviews == null) return 0;
        LocalDateTime cutoff = now.minusDays(RETENTION_DAYS);
        int count = 0;
        for (com.paf.aerovisit.model.PassportReview r : passportReviews.findPurgeCandidates()) {
            if (com.paf.aerovisit.model.PassportReview.PENDING.equals(r.getStatus())) continue;
            LocalDateTime last = r.lastActivity();
            if (last != null && last.isAfter(cutoff)) continue;
            if (r.getFileName() != null && !com.paf.aerovisit.model.PassportReview.USED.equals(r.getStatus())
                    && !removeFile(uploadDir, r.getFileName())) continue; // retried next night
            r.setFileName(null);
            r.setFullName(REDACTED);
            r.setEmail(REDACTED);
            r.setContactNumber(redacted(r.getContactNumber()));
            r.setPossibleBanMatch(null);
            r.setToken(null);
            r.setTokenExpiresAt(null);
            passportReviews.save(r);
            count++;
        }
        return count;
    }

    // 🕶️ GAP 3 (storage limitation, R.A. 10173): overwrites everything that identifies the visitor (names, contact
    // details, address, free-text notes) and drops the companion list. Kept for analytics and audit: date, time window,
    // status, group size, sex, age, affiliation, visitor type, purpose, rating, feedback and the reference ID.
    // Returns false when the row was already anonymized.
    private static boolean anonymize(Visitor v) {
        if (REDACTED.equals(v.getFullName())) return false;
        v.setName(REDACTED);
        v.setFullName(REDACTED);
        v.setEmail(REDACTED);
        v.setPhone(redacted(v.getPhone()));
        v.setContactNumber(redacted(v.getContactNumber()));
        v.setAddress(redacted(v.getAddress()));
        v.setSpecialConsideration(redacted(v.getSpecialConsideration()));
        v.setEventJustification(redacted(v.getEventJustification()));
        v.setCompanionNames(null); // the head count stays in groupSize
        v.setPossibleBanMatch(null); // names a banned person
        return true;
    }

    private static String redacted(String value) {
        return value == null || value.isBlank() ? value : REDACTED;
    }

    // ☁️ The local file and, when S3 is on, its S3 copy. True only when both are gone, so a failed S3 delete keeps the
    // field (and the ban) for a retry next night, exactly like a locked local file.
    private boolean removeFile(Path uploadDir, String fileName) {
        return deleteUpload(uploadDir, fileName) && (uploadMirror == null || uploadMirror.deleteUpload(fileName));
    }

    // True when the file is gone afterwards. Only a plain file name directly inside uploadDir is touched, so a bad
    // value in the database (e.g. "../x" or "exhibits/y.jpg") can never delete anything else; such a value is just cleared.
    private static boolean deleteUpload(Path uploadDir, String fileName) {
        Path target;
        try {
            target = uploadDir.resolve(fileName).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            target = null;
        }
        if (target == null || !uploadDir.normalize().equals(target.getParent()) || !fileName.equals(String.valueOf(target.getFileName()))) {
            System.err.println("⚠️ Purge skipped an unexpected file path (field cleared): " + fileName);
            return true;
        }
        try {
            Files.deleteIfExists(target);
            return true;
        } catch (Exception e) {
            System.err.println("❌ Purge could not delete " + fileName + ": " + e.getMessage());
            return false;
        }
    }

    // 🧹 AUTO CHECK-OUT: CHECKED-IN visitors whose visit is over become CHECKED-OUT and get the feedback email.
    // Earlier dates always; today only once the booked time window has ended (same end-minute rule as the gate and the
    // no-show sweep). With endOfDay (the 11:59 PM sweep) every visit dated today is over, so night windows and
    // unreadable labels (e.g. an older booking's "Master Booking Bypass") are checked out then. Future dates and missing dates are never touched.
    int autoCheckOut(LocalDateTime now, boolean endOfDay) {
        LocalDate today = now.toLocalDate();
        int count = 0;
        for (Visitor v : repo.findByStatus("CHECKED-IN")) {
            if (v.getVisitDate() == null || v.getVisitDate().isAfter(today)) continue;
            boolean over = endOfDay || v.getVisitDate().isBefore(today);
            if (!over) {
                java.time.LocalTime[] window = com.paf.aerovisit.controller.GuardOperationController.parseTimeWindow(v.getTimeWindow());
                over = window != null && now.toLocalTime().isAfter(window[1].withSecond(59).withNano(999_999_999));
            }
            if (!over) continue;
            v.setStatus("CHECKED-OUT");
            v.setCheckOutTime(now);
            v.setCheckedInBy(v.getCheckedInBy() + " (Auto-Sweep)");
            repo.save(v);

            // The feedback email as they are swept out, unless the 15-minute sweep (or a guard) already sent it
            FeedbackEmails.sendOnce(feedbackEmails, emailService, v, now);
            count++;
        }
        return count;
    }

    // 🚫 NO-SHOWS: an APPROVED booking whose visit is over without a check-in becomes NO_SHOW, so it stops looking like
    // a live pass forever. Earlier dates always; today only once the booked time window has ended (same end-minute rule
    // as the gate). An unreadable label (e.g. an older booking's "Master Booking Bypass") waits for the next night's sweep. Never emails anyone.
    int markNoShows(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int count = 0;
        for (Visitor v : repo.findByStatus("APPROVED")) {
            if (v.getVisitDate() == null || v.getVisitDate().isAfter(today)) continue;
            boolean over = v.getVisitDate().isBefore(today);
            if (!over) {
                java.time.LocalTime[] window = com.paf.aerovisit.controller.GuardOperationController.parseTimeWindow(v.getTimeWindow());
                over = window != null && now.toLocalTime().isAfter(window[1].withSecond(59).withNano(999_999_999));
            }
            // 🔒 Only if still APPROVED in the database (a last-minute check-in wins)
            if (over && repo.releaseIfStatus(v.getId(), "APPROVED", "NO_SHOW") == 1) {
                v.setStatus("NO_SHOW");
                v.setQrCodeUrl(null);
                count++;
            }
        }
        return count;
    }
}