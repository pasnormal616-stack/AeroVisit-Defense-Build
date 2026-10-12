package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.BookingSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface BookingSlotRepository extends JpaRepository<BookingSlot, Long> {
    
    // This custom query finds all slots that are TODAY or in the FUTURE, 
    // and orders them so the closest dates appear first in the dropdown!
    List<BookingSlot> findBySlotDateGreaterThanEqualOrderBySlotDateAsc(LocalDate date);

    // Capacity of one date + time window (used by the just-in-time approval check)
    List<BookingSlot> findBySlotDateAndTimeWindow(LocalDate slotDate, String timeWindow);

    // 🔒 Same slots, row-locked (SELECT ... FOR UPDATE) until the caller's transaction ends, so concurrent bookings
    // for one date + time window take turns. Ordered by id so two lockers always lock in the same order (no deadlock).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BookingSlot s WHERE s.slotDate = :slotDate AND s.timeWindow = :timeWindow ORDER BY s.id")
    List<BookingSlot> lockBySlotDateAndTimeWindow(@Param("slotDate") LocalDate slotDate, @Param("timeWindow") String timeWindow);
}