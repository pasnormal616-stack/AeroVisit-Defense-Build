package com.paf.aerovisit.service;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;

// Creates the header bell's notifications. Every method swallows its own errors: a notification
// problem must never fail the booking or feedback submission that triggered it.
@Service
public class NotificationService {

    // A booking with more visitors than this raises a "Large group booking" notification
    public static final int LARGE_GROUP_THRESHOLD = 15;

    private static final int FEEDBACK_PREVIEW_CHARS = 90;

    private final AdminNotificationRepository notificationRepo;

    public NotificationService(AdminNotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    // Called after a new public booking is saved
    public void largeGroupBooking(Visitor visitor) {
        try {
            if (visitor == null || visitor.getGroupSize() <= LARGE_GROUP_THRESHOLD) {
                return;
            }
            String message = "Large group booking: " + name(visitor) + ", " + visitor.getGroupSize() + " visitors"
                    + (visitor.getVisitDate() != null ? " on " + visitor.getVisitDate() : "") + ".";
            save(AdminNotification.TYPE_LARGE_GROUP, message, "/admin?filter=pending&sort=desc");
        } catch (RuntimeException e) {
            System.err.println("⚠️ Large group notification skipped: " + e.getMessage());
        }
    }

    // Called after a feedback submission has been analysed; only a NEGATIVE label raises a notification
    public void negativeFeedback(Visitor visitor) {
        try {
            String sentiment = visitor == null ? null : visitor.getFeedbackSentiment();
            if (sentiment == null || !sentiment.toUpperCase(Locale.ROOT).contains("NEGATIVE")) {
                return;
            }
            String comment = visitor.getFeedback() == null ? "" : visitor.getFeedback().replaceAll("\\s+", " ").trim();
            if (comment.length() > FEEDBACK_PREVIEW_CHARS) {
                comment = comment.substring(0, FEEDBACK_PREVIEW_CHARS).trim() + "…";
            }
            String message = "Negative feedback from " + name(visitor)
                    + (visitor.getRating() != null ? " (" + visitor.getRating() + "/5)" : "")
                    + (comment.isEmpty() ? "." : ": \"" + comment + "\"");
            save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, message, "/admin/analytics#feedbackFeed");
        } catch (RuntimeException e) {
            System.err.println("⚠️ Negative feedback notification skipped: " + e.getMessage());
        }
    }

    private void save(String type, String message, String targetUrl) {
        if (message.length() > 255) {
            message = message.substring(0, 254) + "…";
        }
        notificationRepo.save(new AdminNotification(type, message, targetUrl));
    }

    private static String name(Visitor visitor) {
        String name = visitor.getName() != null && !visitor.getName().isBlank() ? visitor.getName() : visitor.getFullName();
        return name == null || name.isBlank() ? "a visitor" : name.trim();
    }
}
