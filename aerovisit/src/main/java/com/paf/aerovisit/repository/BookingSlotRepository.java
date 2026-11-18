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

    // Slots from start to end, both dates included (the Bulk Slot Generator skips a date + window that already has one)
    List<BookingSlot> findBySlotDateBetween(LocalDate start, LocalDate end);

    // Capacity of one date + time window (used by the just-in-time approval check)
    List<BookingSlot> findBySlotDateAndTimeWindow(LocalDate slotDate, String timeWindow);

    // 🔒 Same slots, row-locked (SELECT ... FOR UPDATE) until the caller's transaction ends, so concurrent bookings
    // for one date + time window take turns. Ordered by id so two lockers always lock in the same order (no deadlock).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BookingSlot s WHERE s.slotDate = :slotDate AND s.timeWindow = :timeWindow ORDER BY s.id")
    List<BookingSlot> lockBySlotDateAndTimeWindow(@Param("slotDate") LocalDate slotDate, @Param("timeWindow") String timeWindow);

    // 🗑️ Delete Slots (SlotRangeDeletion): every slot from start to end, row-locked in the same id order as above, so a
    // booking for one of them waits until the delete is done (and then finds no slot)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BookingSlot s WHERE s.slotDate BETWEEN :from AND :to ORDER BY s.id")
    List<BookingSlot> lockBySlotDateBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}