package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 🧬 BIOMETRIC BAN (the only ban in the system): a banned visitor's face vector (SFace, 128 numbers as a JSON array).
// While the ban is active, a booking whose face is the same person (cosine similarity >= FaceEncodings.SAME_PERSON_COSINE)
// is refused whatever email it uses, and approval / check-in refuse it too. A ban is permanent (expiresAt null) or
// temporary (e.g. a 30-day suspension for repeated no-shows); an expired ban is simply ignored and stays as a record.
// Added from an existing booking by POST /admin/biometric-ban/{visitorId}.
@Entity
@Table(name = "blacklisted_faces")
public class BlacklistedFace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String faceEncoding;

    private String reason;

    // When the ban was made (shown on the Banned Visitors page); null for bans made before this column existed
    @org.hibernate.annotations.CreationTimestamp
    @Column(updatable = false)
    private java.time.LocalDateTime bannedAt;

    // Last day the ban applies; null = permanent. Same rule as the old email block: it lifts the day after this date.
    private java.time.LocalDate expiresAt;

    // For the guards only (Banned Visitors page): the name on the banned booking and a "mugshot", the file name in
    // uploads/ of the ban's OWN copy of that booking's liveness selfie (the booking's photo is purged after 30 days).
    // Never used to enforce the ban: that is the face vector alone. Null on bans made before these columns existed.
    private String name;

    private String imageUrl;

    // Is the ban in force on this day? Named without "is"/"get" so it never shows up as a bean property.
    public boolean activeOn(java.time.LocalDate day) {
        return expiresAt == null || !day.isAfter(expiresAt);
    }

    // --- GETTERS AND SETTERS ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFaceEncoding() { return faceEncoding; }
    public void setFaceEncoding(String faceEncoding) { this.faceEncoding = faceEncoding; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public java.time.LocalDateTime getBannedAt() { return bannedAt; }
    public void setBannedAt(java.time.LocalDateTime bannedAt) { this.bannedAt = bannedAt; }

    public java.time.LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(java.time.LocalDate expiresAt) { this.expiresAt = expiresAt; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
}
