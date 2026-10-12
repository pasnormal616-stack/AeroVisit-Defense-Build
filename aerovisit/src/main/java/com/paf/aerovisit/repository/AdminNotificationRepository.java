package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.AdminNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;

public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    // Header bell dropdown: the 10 newest notifications of the types this role sees
    List<AdminNotification> findTop10ByTypeInOrderByCreatedAtDescIdDesc(Collection<String> types);

    // Unread count shown on the bell badge
    long countByTypeInAndIsReadFalse(Collection<String> types);

    // Opening the bell marks this role's unread notifications as read. Returns how many changed.
    @Modifying
    @Transactional
    @Query("UPDATE AdminNotification n SET n.isRead = true WHERE n.isRead = false AND n.type IN :types")
    int markAllReadByTypeIn(@Param("types") Collection<String> types);
}
