package com.paf.aerovisit.config;

import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.repository.MuseumZoneRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

// First start only: fills the museum zone table with the default areas of the feedback form.
// After that the Director manages zones from the Analytics page.
@Component
public class MuseumZoneSeeder implements ApplicationRunner {

    static final List<String> DEFAULT_ZONES = List.of(
        "General Experience", "Indoor Main Gallery", "Aircraft Park", "Science Discovery Area", "Restrooms/Facilities");

    private final MuseumZoneRepository zoneRepo;

    public MuseumZoneSeeder(MuseumZoneRepository zoneRepo) {
        this.zoneRepo = zoneRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (zoneRepo.count() > 0) return;
        zoneRepo.saveAll(DEFAULT_ZONES.stream().map(MuseumZone::new).toList());
    }
}
