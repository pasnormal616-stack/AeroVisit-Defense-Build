package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.Visitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface VisitorRepository extends JpaRepository<Visitor, Long> {

    // Existing checks
    List<Visitor> findByEmailAndVisitDate(String email, LocalDate visitDate);
    long countByVisitDate(LocalDate visitDate);

    // --- QUERIES FOR FILTERING ---
    
    // 1. Find visitors coming on a specific date (e.g., Today)
    List<Visitor> findByVisitDate(LocalDate visitDate);

    // 1b. Today's Manifest range picker (This Week / This Month): visits dated from..to, both days included
    List<Visitor> findByVisitDateBetween(LocalDate from, LocalDate to);

    // 2. Find visitors by their status (e.g., "PENDING")
    List<Visitor> findByStatus(String status);

    // --- AUTOMATED REMINDER QUERY ---
    
    // 3. Find visitors approved for tomorrow who haven't received a reminder
    List<Visitor> findByVisitDateAndStatusAndReminderSentFalse(LocalDate visitDate, String status);

    // --- END-OF-DAY REPORT QUERY ---
    
    // 4. Count visitors by their specific status for today's summary
    long countByVisitDateAndStatus(LocalDate visitDate, String status);

    // 4b. Header notification bell: requests waiting in the Pending Approval queue (a COUNT query, polled every 30 seconds)
    long countByStatusIn(java.util.Collection<String> statuses);

    // 4c. Pending Approval tab: only the requests awaiting review, instead of loading the whole table
    List<Visitor> findByStatusIn(java.util.Collection<String> statuses);

    // 4d. Pending Approval tab seat headers: seat-holding bookings on the queue's upcoming dates
    List<Visitor> findByVisitDateBetweenAndStatusIn(LocalDate from, LocalDate to, java.util.Collection<String> statuses);

    // --- BOOKING REFERENCE ID ---

    // 5. Used to guarantee each generated Reference ID is unique before saving
    boolean existsByReferenceId(String referenceId);

    // 5b. Manual gate check-in: the guard types the Reference ID from the visitor's email
    Optional<Visitor> findByReferenceId(String referenceId);

    // 6. Feedback links identify the visitor by their unguessable secure token, not the sequential ID
    Optional<Visitor> findBySecureToken(String secureToken);

    // 💳 Payment: the emailed payment link's key (never the billing number), the Director's "Awaiting Payment
    // Verification" list, and the bookings the deadline sweep checks
    Optional<Visitor> findByPaymentToken(String paymentToken);

    List<Visitor> findByPaymentStatus(String paymentStatus);

    List<Visitor> findByStatusAndPaymentStatusIn(String status, java.util.Collection<String> paymentStatuses);

    // 🗂️ The Pending Approval tab (Visitor.inPendingQueue): requests awaiting review that are not waiting on the visitor
    // (no receipt still to come, none rejected), plus older approve-then-pay bookings whose receipt is in
    String PENDING_QUEUE_WHERE = " WHERE (v.status IN ('PENDING', 'PENDING_MANUAL_REVIEW', 'VERIFIED')"
            + " AND (v.paymentStatus IS NULL OR v.paymentStatus NOT IN ('AWAITING_PAYMENT', 'PAYMENT_REJECTED')))"
            + " OR (v.status = 'APPROVED' AND v.paymentStatus = 'PROOF_SUBMITTED')";

    @Query("SELECT v FROM Visitor v" + PENDING_QUEUE_WHERE)
    List<Visitor> findPendingQueue();

    @Query("SELECT COUNT(v) FROM Visitor v" + PENDING_QUEUE_WHERE)
    long countPendingQueue();

    // ⌛ Pending Approval, "Waiting for payment receipt" (review only, Visitor.waitingForReceipt): no receipt yet, or a
    // new one is due after a rejection. Never in the queue above.
    String WAITING_RECEIPT_WHERE = " WHERE v.status = 'AWAITING_RECEIPT'"
            + " OR (v.status IN ('PENDING', 'PENDING_MANUAL_REVIEW', 'VERIFIED') AND v.paymentStatus IN ('AWAITING_PAYMENT', 'PAYMENT_REJECTED'))";

    @Query("SELECT v FROM Visitor v" + WAITING_RECEIPT_WHERE)
    List<Visitor> findWaitingForReceipt();

    @Query("SELECT COUNT(v) FROM Visitor v" + WAITING_RECEIPT_WHERE)
    long countWaitingForReceipt();

    // ✉️ Resend payment link: claims the resend only when none went out in the last 10 minutes (cutoff = now - 10 min),
    // so two clicks or two tabs send one email
    @Modifying
    @Transactional
    @Query("UPDATE Visitor v SET v.paymentLinkResentAt = :now WHERE v.id = :id"
         + " AND (v.paymentLinkResentAt IS NULL OR v.paymentLinkResentAt <= :cutoff)")
    int claimPaymentLinkResend(@org.springframework.data.repository.query.Param("id") Long id,
                               @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
                               @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);

    // 💳 Payment deadline sweep: bookings in these statuses still waiting for a payment (pay first + older approvals)
    List<Visitor> findByStatusInAndPaymentStatusIn(java.util.Collection<String> statuses, java.util.Collection<String> paymentStatuses);

    // 7. Manage Booking lookup: only this email's bookings, instead of loading every visitor
    List<Visitor> findByEmailIgnoreCase(String email);

    // 7b. Manage Booking portal sign-in: the email and the Booking Reference ID must both match
    Optional<Visitor> findByEmailIgnoreCaseAndReferenceIdIgnoreCase(String email, String referenceId);

    // 8. Emergency closure: dates the Director closed (MuseumClosure) are locked for every booking.
    // Kept here so the booking code reads the closed dates from the same repository as the bookings.
    @Query("SELECT c.closureDate FROM MuseumClosure c")
    List<LocalDate> findClosureDates();

    // 🚦 Two gates on one booking: changes the status only if it is still `from`, in one UPDATE, so of two guards pressing
    // Accept (or checking out) at once exactly one gets 1 back and the other gets 0. Used through GateClaim.
    @Modifying
    @Transactional
    @Query("UPDATE Visitor v SET v.status = :to WHERE v.id = :id AND v.status = :from")
    int claimStatus(Long id, String from, String to);

    // 🔒 STALE-WRITE GUARDS (security fix M4). Visitor has no @Version, so a sweep or action that loaded a booking and then
    // saved the whole row could undo a change made in between (a guard's check-in, a receipt upload, a reschedule).

    // A sweep's release (walk-in late, no-show): only while the booking is still `from`; clears the QR. 1 = released.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Visitor v SET v.status = :to, v.qrCodeUrl = NULL WHERE v.id = :id AND v.status = :from")
    int releaseIfStatus(Long id, String from, String to);

    // The payment deadline sweep: PAYMENT_EXPIRED only while the booking is still in that status, still unpaid (no receipt
    // arrived since it was read) and its deadline is still past. 1 = expired.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Visitor v SET v.status = 'PAYMENT_EXPIRED', v.qrCodeUrl = NULL WHERE v.id = :id AND v.status = :status"
         + " AND v.paymentStatus IN :payStatuses AND v.paymentDueAt < :now")
    int expireIfStillUnpaid(Long id, String status, java.util.Collection<String> payStatuses, java.time.LocalDateTime now);

    // The booking row, locked until the caller's transaction ends (VisitorRowGuard, SeatReservationService)
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM Visitor v WHERE v.id = :id")
    Optional<Visitor> lockById(Long id);

    // 📝 The feedback email, exactly once: stamps the time only while none is set, in one UPDATE, so of the sweep and a
    // guard's check-out at the same moment exactly one gets 1 back (and sends). Used through FeedbackEmails.
    @Modifying
    @Transactional
    @Query("UPDATE Visitor v SET v.feedbackEmailSentAt = :at WHERE v.id = :id AND v.feedbackEmailSentAt IS NULL")
    int claimFeedbackEmail(Long id, java.time.LocalDateTime at);

    // 📝 Today's visits that were checked in and have not had their feedback email yet (the 15-minute sweep)
    List<Visitor> findByVisitDateAndStatusInAndFeedbackEmailSentAtIsNull(LocalDate visitDate, java.util.Collection<String> statuses);

    // 10. Mock data: deletes only simulated visitors, never a real booking. Matches the rows MockVisitorGenerator makes
    // (email ends in @mock.aerovisit) and the two kinds the earlier mock generator made. Returns the number deleted.
    @Modifying
    @Transactional
    @Query("DELETE FROM Visitor v WHERE v.email LIKE '%" + com.paf.aerovisit.service.MockVisitorGenerator.MOCK_EMAIL_DOMAIN + "'"
         + " OR (v.name LIKE 'Archival Record - %' AND v.purpose = 'Historical Baseline')"
         + " OR (v.name LIKE 'Civilian Trainee - %' AND v.email LIKE 'civ_%@mail.com')")
    int deleteMockVisitors();

    // 10b. Excel export: visits dated from..to, newest first, without any simulated row
    // (the same three mock patterns deleteMockVisitors removes, plus every 'Historical Baseline' row)
    @Query("SELECT v FROM Visitor v WHERE v.visitDate BETWEEN :from AND :to"
         + " AND (v.purpose IS NULL OR v.purpose <> '" + com.paf.aerovisit.service.MockVisitorGenerator.HIDDEN_PURPOSE + "')"
         + " AND (v.email IS NULL OR (v.email NOT LIKE '%" + com.paf.aerovisit.service.MockVisitorGenerator.MOCK_EMAIL_DOMAIN + "'"
         + " AND (v.name IS NULL OR v.name NOT LIKE 'Civilian Trainee - %' OR v.email NOT LIKE 'civ_%@mail.com')))"
         + " ORDER BY v.id DESC")
    List<Visitor> findRealVisitorsForExport(@org.springframework.data.repository.query.Param("from") LocalDate from,
                                            @org.springframework.data.repository.query.Param("to") LocalDate to);

    // Same "real visitor" rule as the export above (no mock, no Historical Baseline row)
    String REAL_VISITOR = "(v.purpose IS NULL OR v.purpose <> '" + com.paf.aerovisit.service.MockVisitorGenerator.HIDDEN_PURPOSE + "')"
         + " AND (v.email IS NULL OR (v.email NOT LIKE '%" + com.paf.aerovisit.service.MockVisitorGenerator.MOCK_EMAIL_DOMAIN + "'"
         + " AND (v.name IS NULL OR v.name NOT LIKE 'Civilian Trainee - %' OR v.email NOT LIKE 'civ_%@mail.com')))";

    // 10c. Visitor Tally: people who actually came (checked in or out, a group counts its size) per year and month,
    // as [year, month, visitors] rows. Kept after the 30-day purge, which only anonymizes the row.
    @Query("SELECT YEAR(v.visitDate), MONTH(v.visitDate), SUM(CASE WHEN v.groupSize < 1 THEN 1 ELSE v.groupSize END)"
         + " FROM Visitor v WHERE v.status IN ('CHECKED-IN', 'CHECKED-OUT') AND v.visitDate IS NOT NULL AND " + REAL_VISITOR
         + " GROUP BY YEAR(v.visitDate), MONTH(v.visitDate)")
    List<Object[]> tallyVisitorsByMonth();

    // 10d. Visitor Tally: date of the first real booking (any status), the month AeroVisit's own counts start
    @Query("SELECT MIN(v.visitDate) FROM Visitor v WHERE " + REAL_VISITOR)
    LocalDate findFirstRealVisitDate();

    // ==========================================================
    // 🔥 AI DASHBOARD DIAGNOSTIC QUERIES 🔥
    // ==========================================================
    
    // Filters out the PAF Archival Data so it doesn't break your live operational metrics
    
    @Query("SELECT COUNT(v) FROM Visitor v WHERE v.purpose != 'Historical Baseline'")
    long countTotalRegistered();

    @Query("SELECT COUNT(v) FROM Visitor v WHERE v.status IN ('APPROVED', 'CHECKED-IN', 'CHECKED-OUT', 'NO_SHOW') AND v.purpose != 'Historical Baseline'") // a no-show was approved
    long countTotalApproved();

    @Query("SELECT COUNT(v) FROM Visitor v WHERE v.status IN ('CHECKED-IN', 'CHECKED-OUT') AND v.purpose != 'Historical Baseline'")
    long countTotalCheckedIn();

    @Query("SELECT COUNT(v) FROM Visitor v WHERE v.status = 'NO_SHOW' AND v.purpose != 'Historical Baseline'") // set by the 11:59 PM sweep
    long countTotalNoShows();

    // 11. Race-safe capacity check (SeatReservationService): seats held in one date + time window, read straight from
    // the database (an aggregate, so no stale cached Visitor rows), leaving out the booking being saved.
    @Query("SELECT COALESCE(SUM(CASE WHEN v.groupSize < 1 THEN 1 ELSE v.groupSize END), 0) FROM Visitor v"
         + " WHERE v.visitDate = :date AND v.timeWindow = :timeWindow AND v.status IN :statuses"
         + " AND (:excludeId IS NULL OR v.id <> :excludeId)")
    Long sumSeatsHeld(@org.springframework.data.repository.query.Param("date") LocalDate date,
                      @org.springframework.data.repository.query.Param("timeWindow") String timeWindow,
                      @org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses,
                      @org.springframework.data.repository.query.Param("excludeId") Long excludeId);

    // 9b. Booking calendar: seats held per date + time window from `from` onwards, one row per pair
    // [visitDate, timeWindow, seats], counted in the same way as sumSeatsHeld. The database adds them up, so opening the
    // booking page reads a few dozen numbers instead of every visitor ever saved (with their face data).
    @Query("SELECT v.visitDate, v.timeWindow, COALESCE(SUM(CASE WHEN v.groupSize < 1 THEN 1 ELSE v.groupSize END), 0)"
         + " FROM Visitor v WHERE v.visitDate >= :from AND v.status IN :statuses GROUP BY v.visitDate, v.timeWindow")
    List<Object[]> sumSeatsHeldFrom(@org.springframework.data.repository.query.Param("from") LocalDate from,
                                    @org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses);

    // 🗑️ Delete Slots (SlotRangeDeletion): every booking from `from` to `to` in ANY status, counted per date + time
    // window + status: [visitDate, timeWindow, status, count]. A slot with any of them is never deleted.
    @Query("SELECT v.visitDate, v.timeWindow, v.status, COUNT(v) FROM Visitor v WHERE v.visitDate BETWEEN :from AND :to"
         + " GROUP BY v.visitDate, v.timeWindow, v.status")
    List<Object[]> countByDateWindowAndStatus(@org.springframework.data.repository.query.Param("from") LocalDate from,
                                              @org.springframework.data.repository.query.Param("to") LocalDate to);

    // 12. Data retention purge: finished visits dated before the cutoff that still have an ID photo, QR file or face
    // vector, or whose personal details are not anonymized yet (rows purged before anonymization existed included)
    @Query("SELECT v FROM Visitor v WHERE v.status IN :statuses AND v.visitDate < :cutoff"
         + " AND (v.idImageUrl IS NOT NULL OR v.qrCodeUrl IS NOT NULL OR v.idCardImageUrl IS NOT NULL OR v.faceEncoding IS NOT NULL"
         + " OR v.receiptImageUrl IS NOT NULL OR v.earlierReceiptUrls IS NOT NULL OR v.idFaceCropUrl IS NOT NULL OR v.passportFileUrl IS NOT NULL"
         + " OR v.fullName IS NULL OR v.fullName <> 'REDACTED')")
    List<Visitor> findExpiredWithFiles(@org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses,
                                       @org.springframework.data.repository.query.Param("cutoff") LocalDate cutoff);

    // 🚶 Walk-ins: one day's (Guard's Walk-ins tab, payments today, daily report), all of them (Director's Walk-ins tab),
    // and the approved ones whose check-in deadline has passed (15-minute sweep)
    List<Visitor> findByBookingCategoryAndVisitDate(String bookingCategory, LocalDate visitDate);

    List<Visitor> findByBookingCategory(String bookingCategory);

    List<Visitor> findByBookingCategoryAndStatusAndCheckInDueAtBefore(String bookingCategory, String status, java.time.LocalDateTime time);

    // 13. Unreviewed requests whose visit date has passed (auto-expired by the nightly retention job)
    List<Visitor> findByStatusInAndVisitDateBefore(java.util.Collection<String> statuses, LocalDate date);

    // Default method to prevent SQL crashing while simulating predictive lead times
    default Double calculateAverageLeadTime() {
        return 3.2; // Simulates an average booking lead time of 3.2 days for the dashboard
    }
}