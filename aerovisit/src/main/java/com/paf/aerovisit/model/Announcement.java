package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

// An official museum update (closure, special event, advisory) shown on the public portal.
// Managed by the Director on the Website Content page; only active ones are shown to visitors.
@Entity
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    // Plain text; line breaks are kept when displayed
    @Column(nullable = false, length = 2000)
    private String message;

    @Column(nullable = false)
    private LocalDate announcementDate;

    @Column(nullable = false)
    private boolean active = true;

    public Announcement() {}

    public Announcement(String title, String message, LocalDate announcementDate, boolean active) {
        this.title = title;
        this.message = message;
        this.announcementDate = announcementDate;
        this.active = active;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDate getAnnouncementDate() { return announcementDate; }
    public void setAnnouncementDate(LocalDate announcementDate) { this.announcementDate = announcementDate; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
