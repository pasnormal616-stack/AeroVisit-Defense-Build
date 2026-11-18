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

    // The photo of the visitor's valid ID (taken or uploaded). idImageUrl is the liveness selfie that bookings made before
    // 2026-11 have; new bookings have no selfie.
    private String idCardImageUrl;

    // 🗄️ LEGACY KYC DATA (KYC was removed in 2026-11): the four fields below are no longer set by anything. The columns and
    // the values older bookings have are kept (never drop data); the 30-day retention purge still clears them.
    // The face printed on the ID, cut out by the old AI check (faceid-<uuid>.png).
    private String idFaceCropUrl;

    // The old SFace vector (128 numbers, JSON array) of the face on the ID or selfie. Never read any more.
    @Column(columnDefinition = "TEXT")
    private String faceEncoding;

    // The old OCR result (both names read on the ID). Never read any more. Existing rows default to false.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean ocrNameMatch = false;

    // The old same-face duplicate flag (another booking's Reference ID). Never read any more.
    @Column(length = 30)
    private String possibleDuplicateOf;

    // 🪪 Set when the lead booker's name (or a companion's, on older bookings) resembles an active ban's name (IdentityBans.nameBan), e.g.
    // "Ban #12: Juan Dela Cruz". Only a warning: the Director (Pending Approval) or the guard (walk-in) compares the
    // visitor's ID with the ban. Set by /save only, never bound from the form; cleared by the 30-day retention purge.
    @Column(length = 300)
    private String possibleBanMatch;
    // 🚶 The id of the ban that possibleBanMatch names (the Walk-ins tab shows that ban's name and photo to the guard)
    private Long possibleBanId;
    // 🚶 A flagged walk-in is checked in only after the guard ticks "ID checked against the ban": who ticked it, and when
    @Column(length = 120)
    private String banCheckedBy;
    private java.time.LocalDateTime banCheckedAt;
    // 🪪 A group with Free or Discounted visitors is checked in only after the guard ticks "Checked the IDs of the Free
    // and Discounted visitors" (walk-in Process panel, QR Accept, manual check-in): who ticked it, and when
    @Column(length = 120)
    private String eligibilityCheckedBy;
    private java.time.LocalDateTime eligibilityCheckedAt;

    // 🚶 BOOKING CATEGORY: null = Advance Booking (every booking made before walk-ins existed), WALK_IN = booked at the
    // museum for today (no selfie, no Director approval: seats held at once). Set by the server only, never bound.
    public static final String CATEGORY_WALK_IN = "WALK_IN";
    @Column(length = 20)
    private String bookingCategory;
    // 🚶 A walk-in not checked in by this time is released by the 15-minute sweep (NO_SHOW), so it stops holding seats
    private java.time.LocalDateTime checkInDueAt;

    // 🛂 NATIONALITY (Book a Visit): null = Local (every booking made before the choice existed), LOCAL or FOREIGN. Set by
    // the server from the form's choice when the booking is made, never bound and never changed afterwards.
    public static final String NATIONALITY_LOCAL = "LOCAL";
    public static final String NATIONALITY_FOREIGN = "FOREIGN";
    @Column(length = 10)
    private String nationality;
    // 🛂 A Foreign visitor's passport copy (passport-<uuid>.pdf, PassportStorage), in place of the ID photo. Purged with it.
    @Column(length = 80)
    private String passportFileUrl;
    // 🛂 APPROVED: the Director approved the passport before this advance booking (Foreign visitors book in advance only);
    // who decided, and when
    public static final String PASSPORT_APPROVED = "APPROVED";
    @Column(length = 20)
    private String passportStatus;
    @Column(length = 120)
    private String passportReviewedBy;
    private java.time.LocalDateTime passportReviewedAt;

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

    // 📝 When the feedback email went out (exactly once per booking, FeedbackEmails). Written ONLY by the claim UPDATE
    // (VisitorRepository.claimFeedbackEmail), never by saving the entity, so a guard's save of a copy loaded a moment
    // earlier can't reset it to null and let a second email through.
    @Column(updatable = false)
    private java.time.LocalDateTime feedbackEmailSentAt; 

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

    // 💱 ADMISSION (set by the server at /save; null on bookings made before categories existed). groupSize is always
    // discounted + regular + free. The two unit prices and the total are the ones in force when the booking was made,
    // so a later price change never alters it. Whole centavos, never floating point.
    private Integer discountedCount;
    private Integer regularCount;
    private Integer freeCount;
    private Long discountedPriceCentavos;
    private Long regularPriceCentavos;
    private Long admissionTotalCentavos;

    // 💳 PAYMENT (Landbank Link.BizPortal, outside AeroVisit). Null on bookings from before payment existed. Set by the
    // server only (never bound from a form): see PaymentWorkflow. A booking whose total is 0 never needs payment.
    public static final String PAY_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String PAY_AWAITING = "AWAITING_PAYMENT";
    public static final String PAY_PROOF_SUBMITTED = "PROOF_SUBMITTED";
    public static final String PAY_REJECTED = "PAYMENT_REJECTED";
    public static final String PAY_PAID = "PAID";
    @Column(length = 20)
    private String paymentStatus;
    // 6-digit running number from BillingNumberService, assigned once; never grants access to anything
    @Column(length = 12, unique = true)
    private String billingNumber;
    // Unguessable key of the emailed payment link (never the QR's secureToken, never logged)
    @Column(length = 36, unique = true)
    private String paymentToken;
    private java.time.LocalDateTime paymentRequestedAt;
    private java.time.LocalDateTime paymentDueAt;
    // Newest receipt screenshot (receipt-<uuid>.<ext> under uploads/), served only through PaymentAdminController
    private String receiptImageUrl;
    private java.time.LocalDateTime receiptUploadedAt;
    // 🧾 Receipts this booking uploaded before the newest one, one file name per line, oldest first. A receipt is proof of
    // payment, so a re-upload never deletes the earlier file; only the retention purge removes them, with the newest one.
    @Column(columnDefinition = "TEXT")
    private String earlierReceiptUrls;
    @Column(length = 120)
    private String paymentVerifiedBy;
    private java.time.LocalDateTime paymentVerifiedAt;
    @Column(length = 300)
    private String paymentVerificationNote;
    @Column(length = 500)
    private String paymentRejectionReason;

    // 📝 The Terms and Conditions the visitor agreed to before booking: when (server time at /save) and which version
    private java.time.LocalDateTime termsAcceptedAt;
    @Column(length = 20)
    private String termsVersion;

    // Older bookings only: the approved headcount of a group whose groupSize the guard replaced with the count that arrived.
    // Nothing sets it any more; the column is kept for the records that have it.
    private Integer bookedGroupSize;

    // 💳 PAY FIRST: a paid advance booking waiting for its receipt. It holds its seats from submission, is not in the
    // Pending Approval tab and can't be approved; the receipt upload moves it to PENDING, the payment deadline sweep to
    // PAYMENT_EXPIRED (which frees the seats).
    public static final String STATUS_AWAITING_RECEIPT = "AWAITING_RECEIPT";

    // Statuses whose seats count against a time slot's capacity (seat counting only: reports and analytics use their own
    // lists). Pay first: a booking holds its seats from submission (awaiting its receipt, then pending approval).
    public static final java.util.Set<String> SEAT_HOLDING_STATUSES = java.util.Set.of(
            STATUS_AWAITING_RECEIPT, "PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED", "APPROVED", "CHECKED-IN", "CHECKED-OUT");

    // 💳 Booking statuses from the payment flow; neither holds seats. PAYMENT_EXPIRED: the payment deadline passed and the
    // seats were released (final). RESCHEDULE_REQUIRED: a paid booking on a date the museum closed; the visitor picks a
    // new date in Manage Booking and keeps the payment.
    public static final String STATUS_PAYMENT_EXPIRED = "PAYMENT_EXPIRED";
    public static final String STATUS_RESCHEDULE_REQUIRED = "RESCHEDULE_REQUIRED";
    // 📅 A RESCHEDULE_REQUIRED booking may pick its new date until this many days after its old date (the retention
    // period: the purge anonymizes it after that). Also set when a paid request was never reviewed before its date.
    public static final int RESCHEDULE_DAYS = 30;
    public static final String UNREVIEWED_NOTE = "Not reviewed before the visit date; the visitor picks a new date.";

    // The last day a RESCHEDULE_REQUIRED booking may pick a new date, or null for any other booking
    public java.time.LocalDate rescheduleDeadline() {
        return STATUS_RESCHEDULE_REQUIRED.equals(status) && visitDate != null ? visitDate.plusDays(RESCHEDULE_DAYS) : null;
    }
    // Shown (and confirmed) before staff reject or a visitor cancels a booking that is paid or whose receipt is in review
    public static final String PAID_NON_REFUNDABLE = "This visitor has paid; payments are non-refundable.";

    // 🔒 STATE MACHINE: the only statuses a booking may be approved (or ID-verified) from. Anything later in the
    // lifecycle (APPROVED, CHECKED-IN, CHECKED-OUT, REJECTED, CANCELLED*, DENIED_ENTRY) can never be moved back.
    public static final java.util.Set<String> APPROVABLE_STATUSES = java.util.Set.of("PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED");

    // Older bookings only: the event justification a group request once had to give. Never bound from the form any more.
    @Column(length = 1000)
    private String eventJustification;

    // Older bookings only: names of the party besides the lead booker, one per line. New bookings store null (the
    // representative gives only the group size); stored names are still shown read-only and checked against bans.
    @Column(columnDefinition = "TEXT")
    private String companionNames;

    // --- Booking Reference ID (used by the 'Manage Booking' tab) ---
    @Column(unique = true, length = 20)
    private String referenceId;

    // --- Waitlist FIFO: when the request was submitted (null on rows created before this field existed) ---
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // ♻️ REINSTATED (Director): a PAYMENT_EXPIRED booking the visitor had paid for, brought back as PAID + APPROVED.
    // Who, when, the Director's note, and the original "date · window" when the booking was moved to another slot.
    private String reinstatedBy;
    private LocalDateTime reinstatedAt;
    @Column(length = 300)
    private String reinstatementNote;
    @Column(length = 200)
    private String reinstatedFrom;

    public String getReinstatedBy() { return reinstatedBy; }
    public void setReinstatedBy(String reinstatedBy) { this.reinstatedBy = reinstatedBy; }
    public LocalDateTime getReinstatedAt() { return reinstatedAt; }
    public void setReinstatedAt(LocalDateTime reinstatedAt) { this.reinstatedAt = reinstatedAt; }
    public String getReinstatementNote() { return reinstatementNote; }
    public void setReinstatementNote(String reinstatementNote) { this.reinstatementNote = reinstatementNote; }
    public String getReinstatedFrom() { return reinstatedFrom; }
    public void setReinstatedFrom(String reinstatedFrom) { this.reinstatedFrom = reinstatedFrom; }

    // "Reinstated by director on Nov 5, 2026 02:10 PM: <note>", or null (named without "get": never a bound property)
    public String reinstatedText() {
        if (reinstatedAt == null) return null;
        return "Reinstated by " + reinstatedBy + " on "
                + reinstatedAt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy hh:mm a", java.util.Locale.US))
                + (reinstatementNote == null || reinstatementNote.isBlank() ? "" : ": " + reinstatementNote);
    }

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

    public String getPossibleDuplicateOf() { return possibleDuplicateOf; }
    public void setPossibleDuplicateOf(String possibleDuplicateOf) { this.possibleDuplicateOf = possibleDuplicateOf; }

    public String getPossibleBanMatch() { return possibleBanMatch; }
    public void setPossibleBanMatch(String possibleBanMatch) { this.possibleBanMatch = possibleBanMatch; }

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

    public Integer getDiscountedCount() { return discountedCount; }
    public void setDiscountedCount(Integer discountedCount) { this.discountedCount = discountedCount; }
    public Integer getRegularCount() { return regularCount; }
    public void setRegularCount(Integer regularCount) { this.regularCount = regularCount; }
    public Integer getFreeCount() { return freeCount; }
    public void setFreeCount(Integer freeCount) { this.freeCount = freeCount; }
    public Long getDiscountedPriceCentavos() { return discountedPriceCentavos; }
    public void setDiscountedPriceCentavos(Long discountedPriceCentavos) { this.discountedPriceCentavos = discountedPriceCentavos; }
    public Long getRegularPriceCentavos() { return regularPriceCentavos; }
    public void setRegularPriceCentavos(Long regularPriceCentavos) { this.regularPriceCentavos = regularPriceCentavos; }
    public Long getAdmissionTotalCentavos() { return admissionTotalCentavos; }
    public void setAdmissionTotalCentavos(Long admissionTotalCentavos) { this.admissionTotalCentavos = admissionTotalCentavos; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getBillingNumber() { return billingNumber; }
    public void setBillingNumber(String billingNumber) { this.billingNumber = billingNumber; }
    public String getPaymentToken() { return paymentToken; }
    public void setPaymentToken(String paymentToken) { this.paymentToken = paymentToken; }
    public java.time.LocalDateTime getPaymentRequestedAt() { return paymentRequestedAt; }
    public void setPaymentRequestedAt(java.time.LocalDateTime paymentRequestedAt) { this.paymentRequestedAt = paymentRequestedAt; }
    public java.time.LocalDateTime getPaymentDueAt() { return paymentDueAt; }
    public void setPaymentDueAt(java.time.LocalDateTime paymentDueAt) { this.paymentDueAt = paymentDueAt; }
    public String getReceiptImageUrl() { return receiptImageUrl; }
    public void setReceiptImageUrl(String receiptImageUrl) { this.receiptImageUrl = receiptImageUrl; }
    public java.time.LocalDateTime getReceiptUploadedAt() { return receiptUploadedAt; }
    public void setReceiptUploadedAt(java.time.LocalDateTime receiptUploadedAt) { this.receiptUploadedAt = receiptUploadedAt; }
    public String getEarlierReceiptUrls() { return earlierReceiptUrls; }
    public void setEarlierReceiptUrls(String earlierReceiptUrls) { this.earlierReceiptUrls = earlierReceiptUrls; }

    // 🧾 Keeps the current receipt's file name in earlierReceiptUrls before a new receipt replaces it
    public void keepEarlierReceipt() {
        if (receiptImageUrl == null || receiptImageUrl.isBlank()) return;
        earlierReceiptUrls = (earlierReceiptUrls == null || earlierReceiptUrls.isBlank() ? "" : earlierReceiptUrls + "\n") + receiptImageUrl;
    }

    // The earlier receipts' file names (named without "get" so it never becomes a bound property)
    public java.util.List<String> earlierReceiptList() {
        if (earlierReceiptUrls == null) return java.util.List.of();
        return earlierReceiptUrls.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    public String getPaymentVerifiedBy() { return paymentVerifiedBy; }
    public void setPaymentVerifiedBy(String paymentVerifiedBy) { this.paymentVerifiedBy = paymentVerifiedBy; }
    public java.time.LocalDateTime getPaymentVerifiedAt() { return paymentVerifiedAt; }
    public void setPaymentVerifiedAt(java.time.LocalDateTime paymentVerifiedAt) { this.paymentVerifiedAt = paymentVerifiedAt; }
    public String getPaymentVerificationNote() { return paymentVerificationNote; }
    public void setPaymentVerificationNote(String paymentVerificationNote) { this.paymentVerificationNote = paymentVerificationNote; }
    public String getPaymentRejectionReason() { return paymentRejectionReason; }
    public void setPaymentRejectionReason(String paymentRejectionReason) { this.paymentRejectionReason = paymentRejectionReason; }

    public String getIdFaceCropUrl() { return idFaceCropUrl; }
    public void setIdFaceCropUrl(String idFaceCropUrl) { this.idFaceCropUrl = idFaceCropUrl; }

    // The photo to show staff for this booking: an older booking's selfie, else the ID photo (named without "get")
    public String gatePhoto() { return idImageUrl != null ? idImageUrl : idCardImageUrl; }
    public String gatePhotoLabel() { return idImageUrl != null ? "Selfie" : "ID photo"; }

    public java.time.LocalDateTime getFeedbackEmailSentAt() { return feedbackEmailSentAt; }
    public void setFeedbackEmailSentAt(java.time.LocalDateTime feedbackEmailSentAt) { this.feedbackEmailSentAt = feedbackEmailSentAt; }

    public String getBookingCategory() { return bookingCategory; }
    public void setBookingCategory(String bookingCategory) { this.bookingCategory = bookingCategory; }
    public java.time.LocalDateTime getCheckInDueAt() { return checkInDueAt; }
    public void setCheckInDueAt(java.time.LocalDateTime checkInDueAt) { this.checkInDueAt = checkInDueAt; }
    public Long getPossibleBanId() { return possibleBanId; }
    public void setPossibleBanId(Long possibleBanId) { this.possibleBanId = possibleBanId; }
    public String getBanCheckedBy() { return banCheckedBy; }
    public void setBanCheckedBy(String banCheckedBy) { this.banCheckedBy = banCheckedBy; }
    public java.time.LocalDateTime getBanCheckedAt() { return banCheckedAt; }
    public void setBanCheckedAt(java.time.LocalDateTime banCheckedAt) { this.banCheckedAt = banCheckedAt; }

    // 🚶 Category helpers, named without "get"/"is" so they never show up as properties
    public boolean walkIn() { return CATEGORY_WALK_IN.equals(bookingCategory); }

    // "Walk-in" or "Advance" (staff screens, Excel)
    public String categoryLabel() { return walkIn() ? "Walk-in" : "Advance"; }

    // True when the representative's name resembles a ban and no guard has yet ticked "ID checked against the ban"
    public boolean banCheckPending() { return possibleBanMatch != null && banCheckedBy == null; }

    public String getEligibilityCheckedBy() { return eligibilityCheckedBy; }
    public void setEligibilityCheckedBy(String eligibilityCheckedBy) { this.eligibilityCheckedBy = eligibilityCheckedBy; }
    public java.time.LocalDateTime getEligibilityCheckedAt() { return eligibilityCheckedAt; }
    public void setEligibilityCheckedAt(java.time.LocalDateTime eligibilityCheckedAt) { this.eligibilityCheckedAt = eligibilityCheckedAt; }

    // 🪪 Free + Discounted visitors in the group (0 for older bookings without counts)
    public int freeOrDiscounted() {
        return (freeCount == null ? 0 : freeCount) + (discountedCount == null ? 0 : discountedCount);
    }

    // True while the group has Free or Discounted visitors whose IDs no guard has yet confirmed
    public boolean eligibilityCheckNeeded() { return freeOrDiscounted() > 0 && eligibilityCheckedBy == null; }

    // The guard's tick, e.g. "Checked the IDs of the 1 Free and 2 Discounted visitors (AFP ID, ...)"; empty when not needed
    public String eligibilityCheckText() {
        if (!eligibilityCheckNeeded()) return "";
        int free = freeCount == null ? 0 : freeCount, discounted = discountedCount == null ? 0 : discountedCount;
        String who = free > 0 && discounted > 0 ? free + " Free and " + discounted + " Discounted"
                : free > 0 ? free + " Free" : discounted + " Discounted";
        return "Checked the IDs of the " + who + " visitor" + (free + discounted == 1 ? "" : "s")
                + " (AFP ID, dependent's ID, student ID, reservist or retiree ID)";
    }

    // Payment helpers, named without "get"/"is" so they never show up as properties.
    // The amount to pay: the total stored when the booking was made (0 for older bookings without one)
    public long amountDueCentavos() { return admissionTotalCentavos == null ? 0L : admissionTotalCentavos; }

    // True when this booking must be paid before the visit (its stored total is above 0)
    public boolean paymentRequired() { return amountDueCentavos() > 0; }

    // True when nothing stops entry on payment grounds: no payment needed, or the Director verified it
    public boolean paymentCleared() { return !paymentRequired() || PAY_PAID.equals(paymentStatus); }

    // 🚶 The walk-in gate QR (its secure token) is shown only while it can still open the Process panel: a walk-in for
    // today that is booked and not yet inside (gone once inside, left, released, cancelled or denied)
    public boolean walkInPassUsable(java.time.LocalDate today) {
        return walkIn() && secureToken != null && today != null && today.equals(visitDate) && "APPROVED".equals(status);
    }

    // 💳 One line for the guard, next to the amount (scanner card and Process panel): who verified the payment and when,
    // "Free, no payment" when nothing is due, "" while a payment is still open
    public String paymentNote() {
        if (!paymentRequired()) return "Free, no payment";
        if (!PAY_PAID.equals(paymentStatus)) return "";
        String by = paymentVerifiedBy == null || paymentVerifiedBy.isBlank() ? "the museum" : paymentVerifiedBy;
        return "Verified by " + by + (paymentVerifiedAt == null ? ""
                : " on " + paymentVerifiedAt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)));
    }

    // True when the visitor has paid or a receipt is being checked: non-refundable warnings apply
    public boolean paymentInHand() { return PAY_PAID.equals(paymentStatus) || PAY_PROOF_SUBMITTED.equals(paymentStatus); }

    // 💳 Staff rejecting this booking see (and confirm) the non-refundable warning: paid, or a receipt was uploaded at all
    // (even one rejected since: the visitor may well have paid)
    public boolean receiptOnFile() {
        return paymentInHand() || (receiptImageUrl != null && !receiptImageUrl.isBlank()) || !earlierReceiptList().isEmpty();
    }

    // "Awaiting Payment", "Proof Submitted", ... for staff and visitor screens; empty when no payment is involved
    public String paymentStatusLabel() {
        if (paymentStatus == null) return "";
        return switch (paymentStatus) {
            case PAY_AWAITING -> "Awaiting Payment";
            case PAY_PROOF_SUBMITTED -> "Proof Submitted";
            case PAY_REJECTED -> "Receipt Rejected";
            case PAY_PAID -> "Paid";
            case PAY_NOT_REQUIRED -> "No Payment Needed";
            default -> paymentStatus;
        };
    }

    // e.g. "PHP 650.00" (the stored total, also for older bookings: PHP 0.00)
    public String amountDueText() { return com.paf.aerovisit.util.Peso.format(amountDueCentavos()); }

    // "1250.00": what the payment page's Copy button puts on the clipboard for Landbank's amount box
    public String amountDuePlain() { return com.paf.aerovisit.util.Peso.plain(amountDueCentavos()); }

    // 🗂️ Compact admin booking cell helpers (named without "get"/"is" so they never become bound properties)
    // "1 visitor" / "4 visitors"
    public String visitorsText() {
        int n = Math.max(1, groupSize);
        return n + (n == 1 ? " visitor" : " visitors");
    }

    // "₱650.00", "Free" for a counted booking with a total of 0, empty for older bookings without counts or a total
    public String totalShort() {
        if (admissionTotalCentavos == null) return admissionCounted() ? "Free" : "";
        if (admissionTotalCentavos == 0) return "Free";
        return com.paf.aerovisit.util.Peso.format(admissionTotalCentavos).replace("PHP ", "₱");
    }

    // The one payment/status chip on the admin booking cell; empty when there is nothing to say (older bookings)
    public String paymentChipLabel() {
        if (STATUS_PAYMENT_EXPIRED.equals(status)) return "Payment expired";
        if (STATUS_RESCHEDULE_REQUIRED.equals(status)) return "Reschedule needed";
        if (!paymentRequired()) return admissionCounted() ? "Free" : "";
        if (paymentStatus == null) return "";
        return switch (paymentStatus) {
            case PAY_PAID -> "Paid";
            case PAY_PROOF_SUBMITTED -> "Proof submitted";
            case PAY_REJECTED -> "Receipt rejected";
            case PAY_AWAITING -> "Awaiting receipt";
            default -> "";
        };
    }

    // The chip's colour by meaning: ok / info / warn / danger
    public String paymentChipTone() {
        if (STATUS_PAYMENT_EXPIRED.equals(status)) return "danger";
        if (STATUS_RESCHEDULE_REQUIRED.equals(status)) return "warn";
        if (!paymentRequired()) return "ok";
        if (paymentStatus == null) return "info";
        return switch (paymentStatus) {
            case PAY_PAID -> "ok";
            case PAY_REJECTED -> "danger";
            case PAY_AWAITING -> "warn";
            default -> "info";
        };
    }

    // ⏰ "Pay by <deadline>" still matters: a receipt is still awaited (none yet, or a rejected one to replace) and the
    // booking still waits on it (pay-first advance, a pending booking with a rejected receipt, or an unpaid walk-in that
    // is released at the deadline). Hidden for Proof submitted, Paid, Free and approved advance bookings.
    public boolean payByApplies() {
        if (paymentDueAt == null || !paymentRequired()) return false;
        if (!PAY_AWAITING.equals(paymentStatus) && !PAY_REJECTED.equals(paymentStatus)) return false;
        return awaitsReceipt() || awaitsApproval() || (walkIn() && "APPROVED".equals(status));
    }

    // Red: the deadline applies and is within 24 hours or already past
    public boolean payByUrgent(java.time.LocalDateTime now) {
        return payByApplies() && now != null && !paymentDueAt.isAfter(now.plusHours(24));
    }

    public java.time.LocalDateTime getTermsAcceptedAt() { return termsAcceptedAt; }
    public void setTermsAcceptedAt(java.time.LocalDateTime termsAcceptedAt) { this.termsAcceptedAt = termsAcceptedAt; }
    public String getTermsVersion() { return termsVersion; }
    public void setTermsVersion(String termsVersion) { this.termsVersion = termsVersion; }

    // Admission helpers, named without "get"/"is" so they never show up as properties (Jackson, Thymeleaf field binding).
    // True when the booking has admission counts (made after categories existed)
    public boolean admissionCounted() {
        return discountedCount != null && regularCount != null && freeCount != null;
    }

    // e.g. "2 Discounted · 3 Regular · 1 Free"; empty for older bookings
    public String admissionSummary() {
        if (!admissionCounted()) return "";
        return discountedCount + " Discounted · " + regularCount + " Regular · " + freeCount + " Free";
    }

    // The unit prices this booking was made at, e.g. "PHP 100.00"; empty when not recorded
    public String discountedPriceText() {
        return discountedPriceCentavos == null ? "" : com.paf.aerovisit.util.Peso.format(discountedPriceCentavos);
    }

    public String regularPriceText() {
        return regularPriceCentavos == null ? "" : com.paf.aerovisit.util.Peso.format(regularPriceCentavos);
    }

    // e.g. "PHP 650.00"; empty when the booking has no recorded total
    public String admissionTotalText() {
        return admissionTotalCentavos == null ? "" : com.paf.aerovisit.util.Peso.format(admissionTotalCentavos);
    }

    public String getCompanionNames() { return companionNames; }
    public void setCompanionNames(String companionNames) { this.companionNames = companionNames; }

    // Splits the raw manifest into trimmed, non-blank names (one per line)
    public static java.util.List<String> parseCompanionNames(String raw) {
        if (raw == null) return java.util.List.of();
        return raw.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // Named without "get"/"is" so it never shows up as a property (Jackson, Thymeleaf field binding)
    public java.util.List<String> companionList() { return parseCompanionNames(companionNames); }

    // Named without "get"/"is" so it never shows up as a property (Jackson, Thymeleaf field binding)
    public boolean holdsSeat() { return status != null && SEAT_HOLDING_STATUSES.contains(status); }

    // Null-safe (Set.of(...).contains(null) throws), named like holdsSeat so it never becomes a bound property
    public boolean awaitsApproval() { return status != null && APPROVABLE_STATUSES.contains(status); }

    // 💳 Pay first: a paid advance booking whose receipt hasn't been uploaded yet
    public boolean awaitsReceipt() { return STATUS_AWAITING_RECEIPT.equals(status); }

    // 🗂️ In the Director's Pending Approval tab: waiting for approval and not waiting on the visitor (no receipt still to
    // come, none rejected); plus older approve-then-pay bookings whose receipt is in.
    public boolean inPendingQueue() {
        if (awaitsApproval()) return !PAY_AWAITING.equals(paymentStatus) && !PAY_REJECTED.equals(paymentStatus);
        return "APPROVED".equals(status) && PAY_PROOF_SUBMITTED.equals(paymentStatus);
    }

    // ⌛ Still waiting for the visitor's payment receipt (none yet, or a new one after a rejection): shown in Pending
    // Approval for review only, never approvable. Exactly the requests inPendingQueue() leaves out.
    public boolean waitingForReceipt() {
        return awaitsReceipt() || (awaitsApproval() && (PAY_AWAITING.equals(paymentStatus) || PAY_REJECTED.equals(paymentStatus)));
    }

    // An older approve-then-pay booking still waiting for its payment (or its receipt was rejected)
    public boolean approvedAwaitingPayment() {
        return "APPROVED".equals(status) && paymentRequired()
                && (PAY_AWAITING.equals(paymentStatus) || PAY_REJECTED.equals(paymentStatus));
    }

    // 💳 "Mark as Paid" applies (Director, with a note): a payment checked in the Landbank report while no accepted receipt
    // is in, or an older approved booking's receipt
    public boolean markPaidApplies() {
        return paymentRequired()
                && ((PAY_PROOF_SUBMITTED.equals(paymentStatus) && "APPROVED".equals(status))
                    || PAY_AWAITING.equals(paymentStatus) || PAY_REJECTED.equals(paymentStatus))
                && ("APPROVED".equals(status) || awaitsApproval() || awaitsReceipt());
    }

    // 🗂️ The admin row's collapsed "Details" panel has something to show (admin.html, booking cell)
    public boolean hasBookingDetails() {
        boolean payBy = payByApplies();
        return admissionCounted() || billingNumber != null || (paymentDueAt != null && !payBy)
                || paymentVerifiedAt != null || (PAY_REJECTED.equals(paymentStatus) && paymentRejectionReason != null)
                || eligibilityCheckedAt != null || banCheckedAt != null || termsAcceptedAt != null
                || feedbackEmailSentAt != null || (eventJustification != null && !eventJustification.isEmpty())
                || reinstatedAt != null;
    }

    // 🔘 The one main button of an admin row (admin.html), by state:
    // approve | markPaid | waiting | reinstate | deny | none
    public String mainAction(java.time.LocalDate today) {
        if (waitingForReceipt()) return "waiting";
        if (awaitsApproval() && inPendingQueue()) return "approve";
        if ("APPROVED".equals(status) && paymentRequired() && PAY_PROOF_SUBMITTED.equals(paymentStatus)) return "markPaid";
        if (approvedAwaitingPayment()) return "markPaid";
        if (STATUS_PAYMENT_EXPIRED.equals(status)) return "reinstate";
        if ("CHECKED-IN".equals(status) && today != null && today.equals(visitDate)) return "deny";
        return "none";
    }

    // ✉️ When the Director last re-sent the payment link (Resend payment link: at most once every 10 minutes)
    private java.time.LocalDateTime paymentLinkResentAt;
    public java.time.LocalDateTime getPaymentLinkResentAt() { return paymentLinkResentAt; }
    public void setPaymentLinkResentAt(java.time.LocalDateTime paymentLinkResentAt) { this.paymentLinkResentAt = paymentLinkResentAt; }

    // 🛑 Inside the base, visit completed, or missed: the record is history, so it can no longer be rejected
    public static final java.util.Set<String> VISIT_UNDERWAY_OR_OVER_STATUSES = java.util.Set.of("CHECKED-IN", "CHECKED-OUT", "NO_SHOW");
    public static final String CANNOT_REJECT_MESSAGE = "Cannot reject a visitor who is currently inside or has already completed their visit.";

    public boolean visitUnderwayOrOver() { return status != null && VISIT_UNDERWAY_OR_OVER_STATUSES.contains(status); }

    // 🛂 Nationality and passport (Foreign visitors)
    public String getNationality() { return nationality; }
    public void setNationality(String nationality) { this.nationality = nationality; }
    public String getPassportFileUrl() { return passportFileUrl; }
    public void setPassportFileUrl(String passportFileUrl) { this.passportFileUrl = passportFileUrl; }
    public String getPassportStatus() { return passportStatus; }
    public void setPassportStatus(String passportStatus) { this.passportStatus = passportStatus; }
    public String getPassportReviewedBy() { return passportReviewedBy; }
    public void setPassportReviewedBy(String passportReviewedBy) { this.passportReviewedBy = passportReviewedBy; }
    public java.time.LocalDateTime getPassportReviewedAt() { return passportReviewedAt; }
    public void setPassportReviewedAt(java.time.LocalDateTime passportReviewedAt) { this.passportReviewedAt = passportReviewedAt; }

    public boolean foreign() { return NATIONALITY_FOREIGN.equals(nationality); }

    // 🔒 Security fix M4 (never stored, never bound from a form): the booking as the request read it. SeatReservationService
    // checks it against the locked row before saving an existing booking, so a change made meanwhile is never overwritten.
    @jakarta.persistence.Transient
    private transient com.paf.aerovisit.service.VisitorRowGuard.Snapshot expectedRow;
    public com.paf.aerovisit.service.VisitorRowGuard.Snapshot expectedRow() { return expectedRow; }
    public void expectRow(com.paf.aerovisit.service.VisitorRowGuard.Snapshot asRead) { this.expectedRow = asRead; }
}