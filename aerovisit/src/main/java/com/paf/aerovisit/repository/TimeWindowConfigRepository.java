package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.TimeWindowConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TimeWindowConfigRepository extends JpaRepository<TimeWindowConfig, Long> {

    // Earliest window first, for the admin list and the Bulk Slot Generator dropdown
    List<TimeWindowConfig> findAllByOrderByStartTimeAsc();
}
