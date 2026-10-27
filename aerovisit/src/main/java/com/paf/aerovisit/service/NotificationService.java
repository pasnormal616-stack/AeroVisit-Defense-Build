package com.paf.aerovisit.service;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

// Creates the header bell's notifications, social-media style: an actor (shown in bold) and what happened.
// Every method swallows its own errors: a notification problem must never fail the booking, approval, gate action
// or feedback submission that triggered it.
@Service
public class NotificationService {

    // A booking with more visitors than this counts as a large group (bell, Pending Approval chip)
    public static final int LARGE_GROUP_THRESHOLD = 15;
    // A time slot with this many seats left or fewer raises "almost full"
    public static final int SLOT_LOW_SEATS = 5;

    private static final int FEEDBACK_PREVIEW_CHARS = 90;
    private static final int REASON_PREVIEW_CHARS = 80;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    private final AdminNotificationRepository notificationRepo;

    public NotificationService(AdminNotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    // ---------- Bookings ----------

    // A new public booking waiting for approval; resolves itself once approved, rejected, cancelled or expired
    public void bookingRequest(Visitor visitor) {
        try {
            if (visitor == null || !visitor.awaitsApproval()) return;
            String message = visitor.isMasterBooking()
                    ? "requested a Master Booking for " + day(visitor.getVisitDate()) + " · group of " + group(visitor) + "."
                    : "requested a visit on " + when(visitor) + " · " + groupText(visitor) + "."
                      + (visitor.getGroupSize() > LARGE_GROUP_THRESHOLD ? " Large group." : "");
            save(visitor.isMasterBooking() ? AdminNotification.TYPE_MASTER_BOOKING_REQUEST : AdminNotification.TYPE_BOOKING_REQUEST,
                    name(visitor), message, pendingUrl(visitor), visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Booking request notification skipped: " + e.getMessage());
        }
    }

    // A visitor changed the date, time, group size or guest list online: it waits for approval again
    public void bookingRescheduled(Visitor visitor) {
        try {
            if (visitor == null) return;
            if (visitor.getId() != null) {
                // The older request for this booking is replaced by this one
                List<AdminNotification> open = notificationRepo.findByVisitorIdAndTypeInAndResolvedFalse(visitor.getId(), AdminNotification.REQUEST_TYPES);
                open.forEach(n -> { n.setResolved(true); n.setRead(true); n.setSeen(true); });
                notificationRepo.saveAll(open);
            }
            save(AdminNotification.TYPE_BOOKING_RESCHEDULED, name(visitor),
                    "changed their booking to " + when(visitor) + " · " + groupText(visitor) + ". It needs approval again.",
                    pendingUrl(visitor), visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Reschedule notification skipped: " + e.getMessage());
        }
    }

    // A visitor cancelled online (their seats are free again)
    public void bookingCancelled(Visitor visitor) {
        try {
            if (visitor == null) return;
            save(AdminNotification.TYPE_BOOKING_CANCELLED, name(visitor),
                    "cancelled their visit on " + when(visitor) + " · " + groupText(visitor) + ".",
                    dayUrl(visitor.getVisitDate()), visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Cancellation notification skipped: " + e.getMessage());
        }
    }

    // After an approval: a time slot with SLOT_LOW_SEATS seats left or fewer, or none; once per slot and level
    public void slotFilling(LocalDate date, String timeWindow, int remaining, int capacity) {
        try {
            if (date == null || timeWindow == null || capacity <= 0 || remaining > SLOT_LOW_SEATS) return;
            boolean full = remaining <= 0;
            String message = full
                    ? "on " + day(date) + " is now full (" + capacity + " of " + capacity + " seats taken)."
                    : "on " + day(date) + " is almost full: " + remaining + (remaining == 1 ? " seat" : " seats") + " left of " + capacity + ".";
            save(AdminNotification.TYPE_SLOT_FILLING, windowName(timeWindow) + " slot", message,
                    "/admin?capacityDate=" + date, null, "SLOT:" + date + ":" + timeWindow + ":" + (full ? "FULL" : "LOW"));
        } catch (RuntimeException e) {
            System.err.println("⚠️ Slot notification skipped: " + e.getMessage());
        }
    }

    // ---------- Gate, security and system ----------

    public void gateDenied(Visitor visitor, String reason) {
        try {
            if (visitor == null) return;
            save(AdminNotification.TYPE_GATE_DENIED, name(visitor),
                    "was denied entry at the gate" + (reason == null || reason.isBlank() ? "." : ": " + preview(reason, REASON_PREVIEW_CHARS) + "."),
                    dayUrl(visitor.getVisitDate()), visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Gate denial notification skipped: " + e.getMessage());
        }
    }

    // 6:30 AM: a large group or a Master Booking arriving today (Guard + Director); once per booking and day
    public void arrivingToday(Visitor visitor) {
        try {
            if (visitor == null || visitor.getVisitDate() == null) return;
            if (!visitor.isMasterBooking() && visitor.getGroupSize() <= LARGE_GROUP_THRESHOLD) return;
            String what = visitor.isMasterBooking() ? "Master Booking, group of " + group(visitor) : "group of " + group(visitor);
            save(AdminNotification.TYPE_ARRIVING_TODAY, name(visitor),
                    "arrives today" + (visitor.getTimeWindow() == null ? "" : " · " + windowName(visitor.getTimeWindow())) + " · " + what + ".",
                    "/admin", visitor.getId(), "ARRIVING:" + visitor.getId() + ":" + visitor.getVisitDate());
        } catch (RuntimeException e) {
            System.err.println("⚠️ Arrival notification skipped: " + e.getMessage());
        }
    }

    // A booking refused because the selfie matches a banned face (nothing was saved, so no booking link)
    public void bannedFaceAttempt(Visitor attempted) {
        try {
            if (attempted == null) return;
            save(AdminNotification.TYPE_BANNED_FACE_ATTEMPT, name(attempted),
                    "tried to book " + (attempted.getVisitDate() == null ? "a visit" : day(attempted.getVisitDate()))
                    + " but their face matches a ban. The booking was refused.",
                    "/admin/blacklist", null, null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Banned face notification skipped: " + e.getMessage());
        }
    }

    // A booking refused because its email or phone number belongs to a banned visitor (IdentityBans; nothing was saved)
    public void bannedContactAttempt(Visitor attempted) {
        try {
            if (attempted == null) return;
            save(AdminNotification.TYPE_BANNED_FACE_ATTEMPT, name(attempted),
                    "tried to book " + (attempted.getVisitDate() == null ? "a visit" : day(attempted.getVisitDate()))
                    + " but their email or phone number matches a ban. The booking was refused.",
                    "/admin/blacklist", null, null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Banned contact notification skipped: " + e.getMessage());
        }
    }

    // An approval was saved but its email (with the QR code) could not be sent
    public void approvalEmailFailed(Visitor visitor) {
        try {
            if (visitor == null) return;
            save(AdminNotification.TYPE_EMAIL_FAILED, name(visitor),
                    "is approved, but the approval email with their QR code could not be sent. Check the email settings.",
                    dayUrl(visitor.getVisitDate()), visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Email failure notification skipped: " + e.getMessage());
        }
    }

    // The AI service did not answer a booking's identity check; at most once an hour
    public void aiServiceOffline() {
        try {
            save(AdminNotification.TYPE_AI_OFFLINE, "AI service",
                    "is not answering. New bookings go to manual photo review until it is back.",
                    "/admin/settings#aiFeatures", null, "AI_OFFLINE:" + LocalDateTime.now().format(HOUR));
        } catch (RuntimeException e) {
            System.err.println("⚠️ AI offline notification skipped: " + e.getMessage());
        }
    }

    // ---------- Feedback ----------

    // Called after a feedback submission has been analysed; only a NEGATIVE label raises a notification
    public void negativeFeedback(Visitor visitor) {
        try {
            String sentiment = visitor == null ? null : visitor.getFeedbackSentiment();
            if (sentiment == null || !sentiment.toUpperCase(Locale.ROOT).contains("NEGATIVE")) {
                return;
            }
            String comment = preview(visitor.getFeedback(), FEEDBACK_PREVIEW_CHARS);
            String message = "left negative feedback"
                    + (visitor.getRating() != null ? " (" + visitor.getRating() + "/5)" : "")
                    + (comment.isEmpty() ? "." : ": \"" + comment + "\"");
            save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, name(visitor), message, "/admin/analytics#feedbackFeed", visitor.getId(), null);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Negative feedback notification skipped: " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private void save(String type, String actor, String message, String targetUrl, Long visitorId, String dedupeKey) {
        if (dedupeKey != null && notificationRepo.existsByDedupeKey(dedupeKey)) return;
        AdminNotification n = new AdminNotification(type, cut(actor, 120), cut(message, 255), targetUrl, visitorId);
        n.setDedupeKey(dedupeKey);
        notificationRepo.save(n);
    }

    private static String cut(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String preview(String text, int max) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() > max ? clean.substring(0, max).trim() + "…" : clean;
    }

    static String name(Visitor visitor) {
        String name = visitor.getName() != null && !visitor.getName().isBlank() ? visitor.getName() : visitor.getFullName();
        return name == null || name.isBlank() ? "A visitor" : name.trim();
    }

    private static String day(LocalDate date) {
        return date == null ? "an unknown date" : date.format(DAY);
    }

    private static String when(Visitor visitor) {
        String window = visitor.getTimeWindow() == null || visitor.getTimeWindow().isBlank() ? "" : " · " + windowName(visitor.getTimeWindow());
        return day(visitor.getVisitDate()) + window;
    }

    private static int group(Visitor visitor) {
        return Math.max(1, visitor.getGroupSize());
    }

    private static String groupText(Visitor visitor) {
        return group(visitor) == 1 ? "1 visitor" : "group of " + group(visitor);
    }

    // "Morning (08:00 AM - 12:00 PM)" -> "Morning"
    static String windowName(String window) {
        int paren = window.indexOf(" (");
        return paren > 0 ? window.substring(0, paren) : window;
    }

    private static String pendingUrl(Visitor visitor) {
        return "/admin?filter=pending&sort=desc" + (visitor.getVisitDate() == null ? "" : "&dateFilter=" + visitor.getVisitDate());
    }

    private static String dayUrl(LocalDate date) {
        return "/admin?filter=all" + (date == null ? "" : "&dateFilter=" + date);
    }
}
