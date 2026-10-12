package com.paf.aerovisit.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import org.springframework.scheduling.annotation.Scheduled;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/** The midnight Director report: configured inbox (no address in the code), the day that just ended, real attendance counts. No database. */
class DirectorReportInboxTest {

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);

    @Test
    void theReportGoesToTheConfiguredInbox() {
        engine.alertEmail = "alerts@museum.test";

        engine.executeDirectorReport();

        verify(emailService).sendDirectorDailyReport(eq("alerts@museum.test"), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void withNoInboxConfiguredTheReportIsSkippedNotSentSomewhereElse() {
        for (String blank : new String[] {null, "", "   "}) {
            engine.alertEmail = blank;
            engine.executeDirectorReport();
        }
        verify(emailService, never()).sendDirectorDailyReport(anyString(), anyString(), anyLong(), anyLong(), anyLong());
    }

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    @Test
    void runsAtMidnight() throws Exception {
        assertThat(AeroVisitAutomationEngine.class.getMethod("executeDirectorReport").getAnnotation(Scheduled.class).cron())
            .isEqualTo("0 0 0 * * *");
    }

    @Test
    void theMidnightRunReportsTheDayThatJustEnded() {
        assertThat(AeroVisitAutomationEngine.reportDay(LocalDateTime.of(DAY.plusDays(1), LocalTime.MIDNIGHT))).isEqualTo(DAY);
        assertThat(AeroVisitAutomationEngine.reportDay(LocalDateTime.of(DAY.plusDays(1), LocalTime.of(0, 0, 30)))).isEqualTo(DAY);
        // a cron moved for testing to the afternoon or evening reports that same day
        assertThat(AeroVisitAutomationEngine.reportDay(LocalDateTime.of(DAY, LocalTime.of(17, 0)))).isEqualTo(DAY);
        assertThat(AeroVisitAutomationEngine.reportDay(LocalDateTime.of(DAY, LocalTime.NOON))).isEqualTo(DAY);
    }

    @Test
    void visitorsCheckedOutByTheNightSweepCountAsCheckedInNotAsNoShows() {
        engine.alertEmail = "alerts@museum.test";
        when(repo.findByVisitDate(DAY)).thenReturn(Collections.nCopies(10, new Visitor()));
        when(repo.countByVisitDateAndStatus(DAY, "CHECKED-IN")).thenReturn(0L);   // everyone was swept out at 11:59 PM
        when(repo.countByVisitDateAndStatus(DAY, "CHECKED-OUT")).thenReturn(6L);
        when(repo.countByVisitDateAndStatus(DAY, "REJECTED")).thenReturn(1L);

        engine.directorReport(LocalDateTime.of(DAY.plusDays(1), LocalTime.MIDNIGHT));

        verify(emailService).sendDirectorDailyReport("alerts@museum.test", "2026-10-12", 10L, 6L, 3L);
    }
}
