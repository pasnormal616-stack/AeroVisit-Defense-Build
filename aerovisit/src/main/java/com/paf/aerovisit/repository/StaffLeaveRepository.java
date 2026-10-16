package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.StaffLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface StaffLeaveRepository extends JpaRepository<StaffLeave, Long> {

    // Leave blocks touching [from, to]: start <= to and end >= from
    List<StaffLeave> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(LocalDate to, LocalDate from);

    // For the Staff Directory list: leave not yet over, soonest first
    List<StaffLeave> findByEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate today);
}
