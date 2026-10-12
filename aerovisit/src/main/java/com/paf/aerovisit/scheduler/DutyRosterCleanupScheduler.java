package com.paf.aerovisit.scheduler;

import com.paf.aerovisit.repository.StaffScheduleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class DutyRosterCleanupScheduler {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    private final StaffScheduleRepository staffRepo;

    public DutyRosterCleanupScheduler(StaffScheduleRepository staffRepo) {
        this.staffRepo = staffRepo;
    }

    // Runs automatically every night at 12:00 AM (midnight, Manila time)
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Manila")
    public void archivePastDutyShifts() {
        LocalDate today = LocalDate.now(MANILA);
        System.out.println("🗄️ [ROSTER ARCHIVE] Archiving duty shifts dated before: " + today);

        try {
            int archived = staffRepo.archiveByDutyDateBefore(today);
            System.out.println("✅ [ROSTER ARCHIVE] Archived " + archived + " past duty shift(s).");
        } catch (Exception e) {
            System.out.println("❌ [ROSTER ARCHIVE ERROR] Archiving failed: " + e.getMessage());
        }
    }
}
