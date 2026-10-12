package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.StaffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

public interface StaffScheduleRepository extends JpaRepository<StaffSchedule, Long> {

    // Custom command to easily find who is working on a specific day!
    List<StaffSchedule> findByDutyDateOrderByStaffNameAsc(LocalDate dutyDate);

    // Active (non-archived) shifts only, for the duty roster and calendar views.
    List<StaffSchedule> findByArchivedFalseOrderByDutyDateAscStaffNameAsc();

    // Active shifts from start to end, both dates included ("Clear Date Range" on the Master Duty Roster).
    List<StaffSchedule> findByArchivedFalseAndDutyDateBetween(LocalDate start, LocalDate end);

    // Tour Guides on the roster for the given day, skipping unfilled AI placeholder shifts.
    @Query("SELECT s FROM StaffSchedule s WHERE s.dutyDate = :date AND s.archived = false "
         + "AND LOWER(s.role) LIKE '%guide%' AND s.staffName NOT LIKE '%OPEN SHIFT%' ORDER BY s.staffName ASC")
    List<StaffSchedule> findTourGuidesOnDuty(@Param("date") LocalDate date);

    // Bulk-archives every active shift dated before the given day in a single SQL statement. Returns the number of rows archived.
    @Modifying
    @Transactional
    @Query("UPDATE StaffSchedule s SET s.archived = true WHERE s.dutyDate < :cutoff AND s.archived = false")
    int archiveByDutyDateBefore(@Param("cutoff") LocalDate cutoff);

}
