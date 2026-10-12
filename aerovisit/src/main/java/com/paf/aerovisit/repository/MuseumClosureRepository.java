package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.MuseumClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MuseumClosureRepository extends JpaRepository<MuseumClosure, Long> {

    // One closure per date: declaring it again re-runs the cancellation sweep instead of adding a duplicate
    Optional<MuseumClosure> findByClosureDate(LocalDate closureDate);

    // Upcoming closures listed on the Schedule & Capacity page
    List<MuseumClosure> findByClosureDateGreaterThanEqualOrderByClosureDateAsc(LocalDate date);
}
