package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.AdminNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    // Header bell: the 30 newest notifications of the types this role sees
    List<AdminNotification> findTop30ByTypeInOrderByCreatedAtDescIdDesc(Collection<String> types);

    // Red badge: arrived since the bell was last opened and not read yet
    long countByTypeInAndIsReadFalseAndSeenFalse(Collection<String> types);

    // Blue dots: not read yet
    long countByTypeInAndIsReadFalse(Collection<String> types);

    // Opening the bell: this role's notifications are seen (the badge clears; the blue dots stay until read)
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE AdminNotification n SET n.seen = true WHERE n.seen = false AND n.type IN :types")
    int markAllSeenByTypeIn(@Param("types") Collection<String> types);

    // "Mark all as read". Returns how many changed.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE AdminNotification n SET n.isRead = true, n.seen = true WHERE n.isRead = false AND n.type IN :types")
    int markAllReadByTypeIn(@Param("types") Collection<String> types);

    // Booking requests still waiting to be resolved (approved, rejected, cancelled, expired or deleted)
    List<AdminNotification> findByTypeInAndResolvedFalseAndVisitorIdIsNotNull(Collection<String> types);

    // A rescheduled booking: its older, still-open request notifications
    List<AdminNotification> findByVisitorIdAndTypeInAndResolvedFalse(Long visitorId, Collection<String> types);

    boolean existsByDedupeKey(String dedupeKey);

    // A deleted booking takes its notifications (names, feedback) with it
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM AdminNotification n WHERE n.visitorId = :visitorId")
    int deleteByVisitorId(@Param("visitorId") Long visitorId);

    // Nightly privacy clean-up: notifications older than the retention period
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("DELETE FROM AdminNotification n WHERE n.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
