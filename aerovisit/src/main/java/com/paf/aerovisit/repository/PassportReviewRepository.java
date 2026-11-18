package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.PassportReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PassportReviewRepository extends JpaRepository<PassportReview, Long> {

    Optional<PassportReview> findByToken(String token);

    // The Director's Passport Review list, oldest first
    List<PassportReview> findByStatusOrderBySubmittedAtAsc(String status);

    // An open review (waiting, or rejected and waiting for a new file) for this email: a new submission replaces it
    List<PassportReview> findByEmailIgnoreCaseAndStatusIn(String email, Collection<String> statuses);

    // 🔒 The approval link works once: only the request whose UPDATE changes the row may book (two tabs, one booking)
    @Modifying
    @Transactional
    @Query("UPDATE PassportReview r SET r.status = 'USED' WHERE r.id = :id"
         + " AND r.status = 'APPROVED' AND r.tokenExpiresAt > :now")
    int claimApproval(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 💾 The date and time picked so far from an unused approval link (resumed when the link is opened again). Only these
    // two columns, and only while still APPROVED: a whole-row save could undo a booking's claim made meanwhile.
    @Modifying
    @Transactional
    @Query("UPDATE PassportReview r SET r.draftVisitDate = :date, r.draftTimeWindow = :window"
         + " WHERE r.id = :id AND r.status = 'APPROVED'")
    int saveDraft(@Param("id") Long id, @Param("date") java.time.LocalDate date, @Param("window") String window);

    // The booking could not be saved after the claim (slot full, lock busy): the link works again
    @Modifying
    @Transactional
    @Query("UPDATE PassportReview r SET r.status = 'APPROVED', r.visitorId = NULL WHERE r.id = :id AND r.status = 'USED'")
    int releaseApproval(@Param("id") Long id);

    // 🗑️ Retention: reviews no longer waiting for the Director, not yet anonymized
    @Query("SELECT r FROM PassportReview r WHERE r.status <> 'PENDING'"
         + " AND (r.fileName IS NOT NULL OR r.fullName IS NULL OR r.fullName <> 'REDACTED')")
    List<PassportReview> findPurgeCandidates();
}
