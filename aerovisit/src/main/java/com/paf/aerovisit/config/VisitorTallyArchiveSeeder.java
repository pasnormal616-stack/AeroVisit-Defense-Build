package com.paf.aerovisit.config;

import com.paf.aerovisit.model.VisitorTallyArchive;
import com.paf.aerovisit.repository.VisitorTallyArchiveRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// First start only: fills the Visitor Tally archive with the museum's paper records (visitors per month, 2022-2025).
// Every month of those years gets a row; a month missing below was closed, so it is stored as 0.
@Component
public class VisitorTallyArchiveSeeder implements ApplicationRunner {

    static final int FIRST_YEAR = 2022;
    static final int LAST_YEAR = 2025;

    // year -> {month -> visitors}, copied from the museum's tally sheet (totals 467 / 1,095 / 724 / 4,538 = 6,824)
    static final Map<Integer, Map<Integer, Integer>> PAPER_RECORDS = Map.of(
        2022, Map.of(12, 467),
        2023, Map.of(6, 206, 7, 426, 8, 459, 9, 4),
        2024, Map.of(1, 260, 5, 77, 9, 300, 10, 44, 11, 23, 12, 20),
        2025, Map.of(2, 21, 3, 1508, 4, 198, 5, 2536, 8, 40, 10, 164, 11, 71));

    private final VisitorTallyArchiveRepository archiveRepo;

    public VisitorTallyArchiveSeeder(VisitorTallyArchiveRepository archiveRepo) {
        this.archiveRepo = archiveRepo;
    }

    static List<VisitorTallyArchive> paperRecords() {
        List<VisitorTallyArchive> rows = new ArrayList<>();
        for (int year = FIRST_YEAR; year <= LAST_YEAR; year++) {
            for (int month = 1; month <= 12; month++) {
                rows.add(new VisitorTallyArchive(year, month, PAPER_RECORDS.get(year).getOrDefault(month, 0)));
            }
        }
        return rows;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (archiveRepo.count() > 0) return;
        archiveRepo.saveAll(paperRecords());
    }
}
