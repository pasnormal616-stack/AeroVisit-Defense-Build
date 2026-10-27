package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 🧬 VISITOR BAN: a banned visitor's face vector (SFace, 128 numbers as a JSON array) when the booking had one, plus
// their email and phone number. While the ban is active:
//   - a booking whose face is the same person (cosine similarity >= FaceEncodings.SAME_PERSON_COSINE) is refused
//     whatever email it uses (needs the AI service);
//   - a booking with the same email or phone number is refused (IdentityBans; works with the AI service off);
//   - a booking whose name resembles the banned name is only flagged for staff (IdentityBans.nameBan), never refused;
// and approval / check-in refuse a face or email/phone match too. A ban is permanent (expiresAt null) or temporary
// (e.g. a 30-day suspension for repeated no-shows); an expired ban is simply ignored and stays as a record.
// Added from an existing booking by POST /admin/biometric-ban/{visitorId}.
@Entity
@Table(name = "blacklisted_faces")
public class BlacklistedFace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Null for an identity ban made while the AI service was off (or for a booking with no face found). The column is
    // made nullable on databases created before identity bans by BanTableUpgrade at start-up.
    @Column(columnDefinition = "TEXT")
    private String faceEncoding;

    // The banned booking's email, lower case (IdentityBans.email), and phone number as digits in the local 09... form
    // (IdentityBans.phone). Null on bans made before these columns existed or when the booking had none.
    @Column(length = 255)
    private String email;

    @Column(length = 30)
    private String phone;

    private String reason;

    // When the ban was made (shown on the Banned Visitors page); null for bans made before this column existed
    @org.hibernate.annotations.CreationTimestamp
    @Column(updatable = false)
    private java.time.LocalDateTime bannedAt;

    // Last day the ban applies; null = permanent. Same rule as the old email block: it lifts the day after this date.
    private java.time.LocalDate expiresAt;

    // The name on the banned booking (Banned Visitors page, and the fuzzy name flag on new bookings, which only warns
    // staff) and a "mugshot", the file name in uploads/ of the ban's OWN copy of that booking's liveness selfie (the
    // booking's photo is purged after 30 days). Null on bans made before these columns existed.
    private String name;

    private String imageUrl;

    // Is the ban in force on this day? Named without "is"/"get" so it never shows up as a bean property.
    public boolean activeOn(java.time.LocalDate day) {
        return expiresAt == null || !day.isAfter(expiresAt);
    }

    // Banned Visitors page (guards see it too): enough to recognise a booking, not the full contact details.
    // "juan.delacruz@gmail.com" -> "ju***@gmail.com", "09171234567" -> "0917***4567". Null when not recorded.
    public String maskedEmail() {
        if (email == null || !email.contains("@")) return null;
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    public String maskedPhone() {
        if (phone == null || phone.length() < 8) return phone == null ? null : "***";
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 4);
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

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}
