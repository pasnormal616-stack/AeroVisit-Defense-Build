package com.paf.aerovisit.config;

import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

// First start only: fills the time window table with the three windows that used to be hardcoded
// in the Bulk Slot Generator, so their labels match the booking slots that already exist.
@Component
public class TimeWindowSeeder implements ApplicationRunner {

    private final TimeWindowConfigRepository windowRepo;

    public TimeWindowSeeder(TimeWindowConfigRepository windowRepo) {
        this.windowRepo = windowRepo;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (windowRepo.count() > 0) return;
        windowRepo.saveAll(List.of(
            new TimeWindowConfig("Morning", LocalTime.of(8, 0), LocalTime.of(12, 0)),
            new TimeWindowConfig("Afternoon", LocalTime.of(13, 0), LocalTime.of(17, 0)),
            new TimeWindowConfig("Full Day", LocalTime.of(8, 0), LocalTime.of(17, 0))
        ));
    }
}
