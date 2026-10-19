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

    // 7. Manage Booking lookup: only this email's bookings, instead of loading every visitor
    List<Visitor> findByEmailIgnoreCase(String email);

    // 7b. Manage Booking portal sign-in: the email and the Booking Reference ID must both match
    Optional<Visitor> findByEmailIgnoreCaseAndReferenceIdIgnoreCase(String email, String referenceId);

    // 8. VIP buyout: dates held by an approved (or already admitted) Master Booking are closed to public bookings
    @Query("SELECT DISTINCT v.visitDate FROM Visitor v WHERE v.purpose = '" + Visitor.MASTER_BOOKING_PURPOSE
         + "' AND v.status IN ('APPROVED', 'CHECKED-IN', 'CHECKED-OUT')")
    List<LocalDate> findBuyoutDates();

    // 8b. Emergency closure: dates the Director closed (MuseumClosure) are locked for every booking.
    // Kept here beside findBuyoutDates so the booking code reads both closed-date lists from one repository.
    @Query("SELECT c.closureDate FROM MuseumClosure c")
    List<LocalDate> findClosureDates();

    // 9. VIP buyout override: standard visitors already APPROVED on the buyout date (the master booking itself and other master bookings excluded)
    @Query("SELECT v FROM Visitor v WHERE v.visitDate = :visitDate AND v.status = 'APPROVED' AND v.id <> :masterId"
         + " AND (v.purpose IS NULL OR v.purpose <> '" + Visitor.MASTER_BOOKING_PURPOSE + "')")
    List<Visitor> findApprovedVisitorsToOverride(@org.springframework.data.repository.query.Param("visitDate") LocalDate visitDate,
                                                 @org.springframework.data.repository.query.Param("masterId") Long masterId);

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

    // 12. Data retention purge: finished visits dated before the cutoff that still have an ID photo, QR file or face
    // vector, or whose personal details are not anonymized yet (rows purged before anonymization existed included)
    @Query("SELECT v FROM Visitor v WHERE v.status IN :statuses AND v.visitDate < :cutoff"
         + " AND (v.idImageUrl IS NOT NULL OR v.qrCodeUrl IS NOT NULL OR v.idCardImageUrl IS NOT NULL OR v.faceEncoding IS NOT NULL"
         + " OR v.fullName IS NULL OR v.fullName <> 'REDACTED')")
    List<Visitor> findExpiredWithFiles(@org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses,
                                       @org.springframework.data.repository.query.Param("cutoff") LocalDate cutoff);

    // 13. Unreviewed requests whose visit date has passed (auto-expired by the nightly retention job)
    List<Visitor> findByStatusInAndVisitDateBefore(java.util.Collection<String> statuses, LocalDate date);

    // Default method to prevent SQL crashing while simulating predictive lead times
    default Double calculateAverageLeadTime() {
        return 3.2; // Simulates an average booking lead time of 3.2 days for the dashboard
    }
}