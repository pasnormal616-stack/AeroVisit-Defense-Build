package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import java.time.LocalDateTime;
import java.util.List;

// One item in the header bell's dropdown (see NotificationService for what creates them)
@Entity
public class AdminNotification {

    // A booking with more than NotificationService.LARGE_GROUP_THRESHOLD visitors (Guard + Director)
    public static final String TYPE_LARGE_GROUP = "LARGE_GROUP";
    // Feedback the AI labelled NEGATIVE; links to the Director-only Analytics page, so Director only
    public static final String TYPE_NEGATIVE_FEEDBACK = "NEGATIVE_FEEDBACK";

    // Types each role sees (and marks as read) in the bell
    public static final List<String> GUARD_TYPES = List.of(TYPE_LARGE_GROUP);
    public static final List<String> DIRECTOR_TYPES = List.of(TYPE_LARGE_GROUP, TYPE_NEGATIVE_FEEDBACK);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String message;

    @Column(nullable = false, length = 40)
    private String type;

    // Where clicking the item goes (an /admin page)
    @Column(length = 255)
    private String targetUrl;

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public AdminNotification() {}

    public AdminNotification(String type, String message, String targetUrl) {
        this.type = type;
        this.message = message;
        this.targetUrl = targetUrl;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { this.isRead = read; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
