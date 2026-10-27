package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
import java.util.List;

// One item in the header bell (see NotificationService for what creates them). Shown like a social media feed:
// the actor in bold (usually the visitor), then what happened. "seen" = the bell was opened since it arrived (drives
// the red badge); "read" = it was clicked or marked read (drives the blue dot); "resolved" = a booking request that has
// since been approved, rejected, cancelled or has expired. Rows are removed with their visitor, and after 30 days.
@Entity
public class AdminNotification {

    // --- Director: bookings ---
    public static final String TYPE_BOOKING_REQUEST = "BOOKING_REQUEST";
    public static final String TYPE_MASTER_BOOKING_REQUEST = "MASTER_BOOKING_REQUEST";
    public static final String TYPE_BOOKING_RESCHEDULED = "BOOKING_RESCHEDULED";
    public static final String TYPE_BOOKING_CANCELLED = "BOOKING_CANCELLED";
    public static final String TYPE_SLOT_FILLING = "SLOT_FILLING";
    // --- Director: security and system ---
    public static final String TYPE_GATE_DENIED = "GATE_DENIED";
    public static final String TYPE_BANNED_FACE_ATTEMPT = "BANNED_FACE_ATTEMPT";
    public static final String TYPE_EMAIL_FAILED = "EMAIL_FAILED";
    public static final String TYPE_AI_OFFLINE = "AI_OFFLINE";
    // Feedback the AI labelled NEGATIVE; links to the Director-only Analytics page
    public static final String TYPE_NEGATIVE_FEEDBACK = "NEGATIVE_FEEDBACK";
    // --- Guard + Director ---
    // A large group or Master Booking arriving today (6:30 AM)
    public static final String TYPE_ARRIVING_TODAY = "ARRIVING_TODAY";
    // Older rows only: large group at booking time (replaced by booking requests and arriving-today)
    public static final String TYPE_LARGE_GROUP = "LARGE_GROUP";

    // Booking requests: resolved by themselves once the booking leaves the approval queue
    public static final List<String> REQUEST_TYPES = List.of(TYPE_BOOKING_REQUEST, TYPE_MASTER_BOOKING_REQUEST, TYPE_BOOKING_RESCHEDULED);

    // Types each role sees (and marks as seen / read) in the bell
    public static final List<String> GUARD_TYPES = List.of(TYPE_ARRIVING_TODAY, TYPE_LARGE_GROUP);
    public static final List<String> DIRECTOR_TYPES = List.of(TYPE_BOOKING_REQUEST, TYPE_MASTER_BOOKING_REQUEST,
            TYPE_BOOKING_RESCHEDULED, TYPE_BOOKING_CANCELLED, TYPE_SLOT_FILLING, TYPE_GATE_DENIED, TYPE_BANNED_FACE_ATTEMPT,
            TYPE_EMAIL_FAILED, TYPE_AI_OFFLINE, TYPE_NEGATIVE_FEEDBACK, TYPE_ARRIVING_TODAY, TYPE_LARGE_GROUP);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Who it is about, shown in bold first (a visitor's name, "Morning slot", "AI service"); may be empty
    @Column(length = 120)
    private String actor;

    // What happened, read after the actor ("requested a visit on Oct 22.")
    @Column(nullable = false, length = 255)
    private String message;

    @Column(nullable = false, length = 40)
    private String type;

    // Where clicking the item goes (an /admin page)
    @Column(length = 255)
    private String targetUrl;

    // The booking it is about: its notifications go when the booking is deleted, and requests resolve with it
    private Long visitorId;

    // One notification per event that could repeat (a slot filling up, the AI going offline this hour)
    @Column(length = 160, unique = true)
    private String dedupeKey;

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean seen = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean resolved = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public AdminNotification() {}

    public AdminNotification(String type, String message, String targetUrl) {
        this.type = type;
        this.message = message;
        this.targetUrl = targetUrl;
    }

    public AdminNotification(String type, String actor, String message, String targetUrl, Long visitorId) {
        this(type, message, targetUrl);
        this.actor = actor;
        this.visitorId = visitorId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public Long getVisitorId() { return visitorId; }
    public void setVisitorId(Long visitorId) { this.visitorId = visitorId; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { this.isRead = read; }

    public boolean isSeen() { return seen; }
    public void setSeen(boolean seen) { this.seen = seen; }

    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
