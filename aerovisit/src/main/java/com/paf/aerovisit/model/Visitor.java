package com.paf.aerovisit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import com.paf.aerovisit.util.ReferenceIdGenerator;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime; 

@Entity
public class Visitor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Basic Info (Legacy) ---
    private String name;
    private String email;
    private String phone; 

    // 🔥 NEW: PAF Admin Security Requirements 🔥
    private String visitorType;
    private String fullName; // Format: Last Name, First Name MI (Rank/Serial for AFP)
    private String sex;
    private Integer age;
    private String address;
    private String affiliation;
    private String contactNumber;
    private String specialConsideration;

    // --- Visit Details ---
    private LocalDate visitDate; 
    private LocalTime visitTime; 
    
    // 🔥 Time Window field 🔥
    private String timeWindow; 
    
    private String purpose;      

    // --- Admin Control ---
    private String status = "PENDING"; // Values: PENDING, APPROVED, REJECTED, CHECKED-IN, CHECKED-OUT, VERIFIED, PENDING_MANUAL_REVIEW, DENIED_ENTRY
    private String referenceNumber;    
    
    // 🔥 Anti-Forgery Secure Token 🔥
    @Column(unique = true)
    private String secureToken = java.util.UUID.randomUUID().toString();
    
    // 🔥 Rejection Reason field 🔥
    @Column(length = 500)
    private String rejectionReason;
    
    // --- ID Verification ---
    private String idImageUrl;

    // Two-step capture: the close-up photo of the ID alone (idImageUrl keeps the liveness selfie holding the ID)
    private String idCardImageUrl;

    // The live face's SFace vector (128 numbers, JSON array) from the AI service, kept so staff can add this face to
    // the biometric blacklist (BlacklistedFace). Never bound from the form; cleared by the 30-day retention purge.
    @Column(columnDefinition = "TEXT")
    private String faceEncoding;

    // True only when Tesseract read both the first and last name on the ID (KycService or the ID re-check); drives the
    // admin table's "ID Name Verified" badge. Never bound from the form. Existing rows default to false.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean ocrNameMatch = false;

    // --- QR Code Storage ---
    private String qrCodeUrl;  

    // --- Security Audit Trail ---
    private String checkedInBy;        
    private LocalDateTime checkInTime; 
    
    // Time they left the base
    private LocalDateTime checkOutTime;

    // Gate denial (DENIED_ENTRY): the check-in is voided but checkedInBy / checkInTime are kept for the audit trail
    private String entryDeniedBy;
    private LocalDateTime entryDeniedAt;
    @Column(length = 500)
    private String entryDenialReason;

    // --- Automated Reminder Tracking ---
    private boolean reminderSent = false; 

    // --- Feedback System ---
    private Integer rating; // 1 to 5 Stars
    
    @Column(length = 500) // Allows for longer comments
    private String feedback; 

    // Root Cause Matrix: the area the visitor reviewed (zone name kept as text so disabling a zone keeps history),
    // and the topic + sentiment label returned by the AI service's /analyze (null when the AI was offline)
    @Column(length = 60)
    private String zoneName;
    @Column(length = 30)
    private String topicCategory;
    @Column(length = 20)
    private String feedbackSentiment;

    // --- Group / Educational Tour Tracking ---
    private int groupSize = 1; // Defaults to 1 person

    // Approved headcount for a Master Booking, kept when groupSize is overwritten with the guard's actual count at the gate
    private Integer bookedGroupSize;

    // Purpose the server stamps on Master Booking (VIP buyout) requests; also used to recognise them later
    public static final String MASTER_BOOKING_PURPOSE = "Educational Tour (Master Booking Request)";

    // Statuses whose seats count against a time slot's capacity; PENDING requests wait for approval without holding seats
    public static final java.util.Set<String> SEAT_HOLDING_STATUSES = java.util.Set.of("APPROVED", "CHECKED-IN", "CHECKED-OUT");

    // 🔒 STATE MACHINE: the only statuses a booking may be approved (or ID-verified) from. Anything later in the
    // lifecycle (APPROVED, CHECKED-IN, CHECKED-OUT, REJECTED, CANCELLED*, DENIED_ENTRY) can never be moved back.
    public static final java.util.Set<String> APPROVABLE_STATUSES = java.util.Set.of("PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED");

    // Official justification a Master Booking (VIP buyout) requester must give for reserving the whole facility
    @Column(length = 1000)
    private String eventJustification;

    // Group Manifest: full names of everyone in the party besides the lead booker, one per line (null for solo visits)
    @Column(columnDefinition = "TEXT")
    private String companionNames;

    // Longest single companion name accepted from the public forms
    public static final int MAX_COMPANION_NAME_LENGTH = 100;

    // --- Booking Reference ID (used by the 'Manage Booking' tab) ---
    @Column(unique = true, length = 20)
    private String referenceId;

    // --- Waitlist FIFO: when the request was submitted (null on rows created before this field existed) ---
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public Visitor() {}

    // Safety net: any visitor inserted without a Reference ID (e.g. mock data) still gets one
    @PrePersist
    protected void assignReferenceId() {
        if (referenceId == null || referenceId.isBlank()) {
            referenceId = ReferenceIdGenerator.generate();
        }
    }

    // --- Getters and Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    // 🔥 HELPER GETTERS FOR OCR ID MATCHING 🔥
    // Updated to use the new 'fullName' field if it exists, otherwise falls back to 'name'
    public String getFirstName() {
        String targetName = (fullName != null && !fullName.isEmpty()) ? fullName : name;
        if (targetName == null || targetName.trim().isEmpty()) return "";
        
        // If formatted as "Last Name, First Name", split by comma to grab the first name
        if (targetName.contains(",")) {
            String[] commaParts = targetName.split(",");
            return commaParts.length > 1 ? commaParts[1].trim().split("\\s+")[0] : targetName;
        }
        
        String[] parts = targetName.trim().split("\\s+");
        return parts[0];
    }

    public String getLastName() {
        String targetName = (fullName != null && !fullName.isEmpty()) ? fullName : name;
        if (targetName == null || targetName.trim().isEmpty()) return "";
        
        // If formatted as "Last Name, First Name", grab the part before the comma
        if (targetName.contains(",")) {
            return targetName.split(",")[0].trim();
        }
        
        String[] parts = targetName.trim().split("\\s+");
        return parts.length > 1 ? parts[parts.length - 1] : parts[0];
    }

    // --- Getters & Setters for New PAF Fields ---
    public String getVisitorType() { return visitorType; }
    public void setVisitorType(String visitorType) { this.visitorType = visitorType; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getAffiliation() { return affiliation; }
    public void setAffiliation(String affiliation) { this.affiliation = affiliation; }

    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }

    public String getSpecialConsideration() { return specialConsideration; }
    public void setSpecialConsideration(String specialConsideration) { this.specialConsideration = specialConsideration; }

    // --- Existing Getters & Setters ---
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; } 
    public void setPhone(String phone) { this.phone = phone; } 

    public LocalDate getVisitDate() { return visitDate; }
    public void setVisitDate(LocalDate visitDate) { this.visitDate = visitDate; }

    public LocalTime getVisitTime() { return visitTime; }
    public void setVisitTime(LocalTime visitTime) { this.visitTime = visitTime; }

    public String getTimeWindow() { return timeWindow; }
    public void setTimeWindow(String timeWindow) { this.timeWindow = timeWindow; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getSecureToken() { return secureToken; }
    public void setSecureToken(String secureToken) { this.secureToken = secureToken; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getIdImageUrl() { return idImageUrl; }
    public void setIdImageUrl(String idImageUrl) { this.idImageUrl = idImageUrl; }

    public String getIdCardImageUrl() { return idCardImageUrl; }
    public void setIdCardImageUrl(String idCardImageUrl) { this.idCardImageUrl = idCardImageUrl; }

    public String getFaceEncoding() { return faceEncoding; }
    public void setFaceEncoding(String faceEncoding) { this.faceEncoding = faceEncoding; }

    public boolean isOcrNameMatch() { return ocrNameMatch; }
    public void setOcrNameMatch(boolean ocrNameMatch) { this.ocrNameMatch = ocrNameMatch; }

    public String getQrCodeUrl() { return qrCodeUrl; }
    public void setQrCodeUrl(String qrCodeUrl) { this.qrCodeUrl = qrCodeUrl; }

    public String getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(String checkedInBy) { this.checkedInBy = checkedInBy; }

    public LocalDateTime getCheckInTime() { return checkInTime; }
    public void setCheckInTime(LocalDateTime checkInTime) { this.checkInTime = checkInTime; }

    public LocalDateTime getCheckOutTime() { return checkOutTime; }
    public void setCheckOutTime(LocalDateTime checkOutTime) { this.checkOutTime = checkOutTime; }

    public String getEntryDeniedBy() { return entryDeniedBy; }
    public void setEntryDeniedBy(String entryDeniedBy) { this.entryDeniedBy = entryDeniedBy; }

    public LocalDateTime getEntryDeniedAt() { return entryDeniedAt; }
    public void setEntryDeniedAt(LocalDateTime entryDeniedAt) { this.entryDeniedAt = entryDeniedAt; }

    public String getEntryDenialReason() { return entryDenialReason; }
    public void setEntryDenialReason(String entryDenialReason) { this.entryDenialReason = entryDenialReason; }

    public boolean isReminderSent() { return reminderSent; }
    public void setReminderSent(boolean reminderSent) { this.reminderSent = reminderSent; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }

    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }

    public String getTopicCategory() { return topicCategory; }
    public void setTopicCategory(String topicCategory) { this.topicCategory = topicCategory; }

    public String getFeedbackSentiment() { return feedbackSentiment; }
    public void setFeedbackSentiment(String feedbackSentiment) { this.feedbackSentiment = feedbackSentiment; }

    public int getGroupSize() { return groupSize; }
    public void setGroupSize(int groupSize) { this.groupSize = groupSize; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public Integer getBookedGroupSize() { return bookedGroupSize; }
    public void setBookedGroupSize(Integer bookedGroupSize) { this.bookedGroupSize = bookedGroupSize; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getEventJustification() { return eventJustification; }
    public void setEventJustification(String eventJustification) { this.eventJustification = eventJustification; }

    public String getCompanionNames() { return companionNames; }
    public void setCompanionNames(String companionNames) { this.companionNames = companionNames; }

    // Splits the raw manifest into trimmed, non-blank names (one per line)
    public static java.util.List<String> parseCompanionNames(String raw) {
        if (raw == null) return java.util.List.of();
        return raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // Named without "get"/"is" so it never shows up as a property (Jackson, Thymeleaf field binding)
    public java.util.List<String> companionList() { return parseCompanionNames(companionNames); }

    public boolean isMasterBooking() { return MASTER_BOOKING_PURPOSE.equals(purpose); }

    // Named without "get"/"is" so it never shows up as a property (Jackson, Thymeleaf field binding)
    public boolean holdsSeat() { return status != null && SEAT_HOLDING_STATUSES.contains(status); }

    // Null-safe (Set.of(...).contains(null) throws), named like holdsSeat so it never becomes a bound property
    public boolean awaitsApproval() { return status != null && APPROVABLE_STATUSES.contains(status); }

    // 🛑 Inside the base, visit completed, or missed: the record is history, so it can no longer be rejected
    public static final java.util.Set<String> VISIT_UNDERWAY_OR_OVER_STATUSES = java.util.Set.of("CHECKED-IN", "CHECKED-OUT", "NO_SHOW");
    public static final String CANNOT_REJECT_MESSAGE = "Cannot reject a visitor who is currently inside or has already completed their visit.";

    public boolean visitUnderwayOrOver() { return status != null && VISIT_UNDERWAY_OR_OVER_STATUSES.contains(status); }
}