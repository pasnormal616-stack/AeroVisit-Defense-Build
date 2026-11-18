package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 🛂 A Foreign visitor's passport check before an advance booking (Book a Visit > Nationality: Foreign).
 *
 * The visitor sends a passport PDF and contact details (POST /passport/submit); the Director approves or rejects it on
 * the Passport Review page (/admin/passports). Approved: an emailed link (token, valid APPROVAL_LINK_DAYS days, books
 * one visit) opens the normal booking form; opening it again resumes: the date and time picked so far (draftVisitDate,
 * draftTimeWindow), or the booking's payment page while it still needs a receipt. Rejected: an emailed link (same token field) lets the visitor upload a new PDF,
 * which replaces the file on this same row. Nothing here is a booking: the booking is an ordinary Visitor row, made by
 * /save once the approved link is used (then status USED and visitorId point to it, and the file belongs to the booking).
 */
@Entity
@Table(name = "passport_review", indexes = {
        @Index(name = "idx_passport_review_token", columnList = "token"),
        @Index(name = "idx_passport_review_status", columnList = "status"),
        @Index(name = "idx_passport_review_email", columnList = "email")})
public class PassportReview {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String USED = "USED";

    // An approval link expires this many days after approval, whatever happened to it (the visitor submits the passport
    // again). Passport review itself takes about two weeks, so the visitor gets as long again to come back and book.
    public static final int APPROVAL_LINK_DAYS = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String fullName;
    @Column(length = 200)
    private String email;
    @Column(length = 40)
    private String contactNumber;

    // passport-<uuid>.pdf under uploads/ (PassportStorage); null once handed to the booking or purged
    @Column(length = 80)
    private String fileName;

    @Column(length = 20, nullable = false)
    private String status = PENDING;

    @Column(length = 500)
    private String rejectionReason;

    // The emailed link's secret (approval link or re-upload link, set at each decision; null while PENDING)
    @Column(length = 64, unique = true)
    private String token;
    // Approval link only: unusable after this time
    private LocalDateTime tokenExpiresAt;

    // 🪪 The name resembles an active ban (IdentityBans.nameBan): a warning for the Director, never a refusal
    @Column(length = 300)
    private String possibleBanMatch;

    private LocalDateTime submittedAt;
    private LocalDateTime decidedAt;
    @Column(length = 120)
    private String decidedBy;

    // The booking made with the approval link (status USED)
    private Long visitorId;

    // 💾 Progress before a booking exists: the date and time window last picked from this link, so reopening the link
    // (another tab, another device) resumes there. Stored on the review, not in the browser session.
    private java.time.LocalDate draftVisitDate;
    @Column(length = 120)
    private String draftTimeWindow;

    public boolean approvalLinkUsable(LocalDateTime now) {
        return APPROVED.equals(status) && withinLinkWindow(now);
    }

    // The link's own lifetime (APPROVAL_LINK_DAYS after approval), whether or not a booking was made with it
    public boolean withinLinkWindow(LocalDateTime now) {
        return token != null && tokenExpiresAt != null && now.isBefore(tokenExpiresAt);
    }

    // When anything last happened to this review (the retention purge counts from here)
    public LocalDateTime lastActivity() {
        if (decidedAt != null && (submittedAt == null || decidedAt.isAfter(submittedAt))) return decidedAt;
        return submittedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public String getPossibleBanMatch() { return possibleBanMatch; }
    public void setPossibleBanMatch(String possibleBanMatch) { this.possibleBanMatch = possibleBanMatch; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public Long getVisitorId() { return visitorId; }
    public void setVisitorId(Long visitorId) { this.visitorId = visitorId; }
    public java.time.LocalDate getDraftVisitDate() { return draftVisitDate; }
    public void setDraftVisitDate(java.time.LocalDate draftVisitDate) { this.draftVisitDate = draftVisitDate; }
    public String getDraftTimeWindow() { return draftTimeWindow; }
    public void setDraftTimeWindow(String draftTimeWindow) { this.draftTimeWindow = draftTimeWindow; }
}
