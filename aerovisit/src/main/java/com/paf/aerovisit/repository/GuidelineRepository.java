package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.Guideline;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GuidelineRepository extends JpaRepository<Guideline, Long> {

    // Display order for the admin list and the public Guidelines tab
    List<Guideline> findAllByOrderByDisplayOrderAscIdAsc();
}
