package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.MuseumZone;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MuseumZoneRepository extends JpaRepository<MuseumZone, Long> {

    // Zones offered in the visitor feedback dropdown
    List<MuseumZone> findByIsActiveTrueOrderByIdAsc();

    Optional<MuseumZone> findByNameIgnoreCase(String name);
}
