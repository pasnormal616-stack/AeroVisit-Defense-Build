package com.paf.aerovisit.service;

import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        verify(emailService).sendDirectorDailyReport(eq("alerts@museum.test"), any(EmailService.DailyReport.class));
    }

    @Test
    void withNoInboxConfiguredTheReportIsSkippedNotSentSomewhereElse() {
        for (String blank : new String[] {null, "", "   "}) {
            engine.alertEmail = blank;
            engine.executeDirectorReport();
        }
        verify(emailService, never()).sendDirectorDailyReport(anyString(), any(EmailService.DailyReport.class));
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

    private static Visitor booking(String status, int groupSize) {
        Visitor v = new Visitor();
        v.setStatus(status);
        v.setGroupSize(groupSize);
        return v;
    }

    private EmailService.DailyReport reportFor(LocalDate day) {
        engine.alertEmail = "alerts@museum.test";
        engine.directorReport(LocalDateTime.of(day.plusDays(1), LocalTime.MIDNIGHT));
        ArgumentCaptor<EmailService.DailyReport> sent = ArgumentCaptor.forClass(EmailService.DailyReport.class);
        verify(emailService).sendDirectorDailyReport(eq("alerts@museum.test"), sent.capture());
        return sent.getValue();
    }

    @Test
    void figuresAreTheDaysApprovedDemandSplitIntoCheckInsNoShowsAndStillOpenInBookingsAndGuests() {
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(
            booking("CHECKED-OUT", 3), booking("CHECKED-OUT", 1),  // swept out at 11:59 PM: still a check-in, never a no-show
            booking("CHECKED-IN", 0),                              // groupSize 0 counts as one guest
            booking("NO_SHOW", 4),
            booking("APPROVED", 40),                               // Master Booking: unreadable label, swept next night
            booking("REJECTED", 2), booking("CANCELLED_BY_VISITOR", 2), booking("DENIED_ENTRY", 1),
            booking("PENDING", 1), booking(null, 1)));             // none of these were expected
        when(repo.countTotalNoShows()).thenReturn(148L);

        EmailService.DailyReport report = reportFor(DAY);

        assertThat(report).usingRecursiveComparison().ignoringFields("insights").isEqualTo(new EmailService.DailyReport("2026-10-12",
            5, 49, 3, 5, 1, 4, 1, 148, "2026-10-13", 0, null));
        assertThat(report.checkInRate()).isEqualTo(60L);
    }

    @Test
    void pendingApprovalsAreCountedForTheDayTheReportArrives() {
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(booking("PENDING", 1)));  // the report day itself: not counted
        when(repo.findByVisitDate(DAY.plusDays(1))).thenReturn(List.of(
            booking("PENDING", 2), booking("PENDING_MANUAL_REVIEW", 1), booking("VERIFIED", 1),
            booking("APPROVED", 1), booking("REJECTED", 1), booking(null, 1)));

        EmailService.DailyReport report = reportFor(DAY);

        assertThat(report.nextDate()).isEqualTo("2026-10-13");
        assertThat(report.pendingNextDay()).isEqualTo(3);
    }

    @Test
    void aDayWithNoExpectedVisitorsHasNoCheckInRate() {
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(booking("REJECTED", 1)));

        EmailService.DailyReport report = reportFor(DAY);

        assertThat(report.expected()).isZero();
        assertThat(report.checkInRate()).isNull();
    }

    // --- Visit insights: only data the Visitor row already holds ---

    private static Visitor visit(int groupSize, LocalTime in, LocalTime out, String checkedInBy) {
        Visitor v = booking(out == null ? "CHECKED-IN" : "CHECKED-OUT", groupSize);
        if (in != null) v.setCheckInTime(LocalDateTime.of(DAY, in));
        if (out != null) v.setCheckOutTime(LocalDateTime.of(DAY, out));
        v.setCheckedInBy(checkedInBy);
        return v;
    }

    @Test
    void peakHourSumsGuestsAndATieGoesToTheEarlierHour() {
        EmailService.Insights insights = AeroVisitAutomationEngine.insights(List.of(
            visit(1, LocalTime.of(9, 5), null, "Guard"), visit(3, LocalTime.of(9, 50), null, "Guard"),
            visit(4, LocalTime.of(10, 0), null, "Guard"), visit(2, null, null, "Guard")));

        assertThat(insights.peakHour()).isEqualTo("9:00 AM - 10:00 AM");
        assertThat(insights.peakHourGuests()).isEqualTo(4);
        assertThat(AeroVisitAutomationEngine.insights(List.of(visit(1, null, null, "Guard"))).peakHour()).isNull();
    }

    @Test
    void visitDurationUsesGuardCheckOutsOnly() {
        EmailService.Insights insights = AeroVisitAutomationEngine.insights(List.of(
            visit(1, LocalTime.of(9, 0), LocalTime.of(10, 0), "Alpha Guard"),                  // 60 min
            visit(1, LocalTime.of(13, 0), LocalTime.of(14, 31), "Bravo Guard (Manual)"),       // 91 min
            visit(1, LocalTime.of(9, 0), LocalTime.of(23, 59), "Alpha Guard (Auto-Sweep)"),    // sweep time, not a real exit
            visit(1, LocalTime.of(15, 0), null, "Alpha Guard"),                                // still inside
            visit(1, LocalTime.of(16, 0), LocalTime.of(15, 0), "Alpha Guard")));               // impossible clock

        assertThat(insights.avgVisitMinutes()).isEqualTo(76L);
        assertThat(insights.timedVisits()).isEqualTo(2);
        assertThat(insights.avgVisit()).isEqualTo("1 h 16 min");
        assertThat(new EmailService.Insights(null, 0, 45L, 1, Map.of(), Map.of(), null, 0).avgVisit()).isEqualTo("45 min");
        assertThat(EmailService.Insights.NONE.avgVisit()).isNull();
    }

    @Test
    void demographicsCountLeadVisitorsLargestFirstBlankAsNotStated() {
        Visitor master = visit(40, LocalTime.of(9, 0), null, "Guard");    // a 40-guest Master Booking is one lead visitor
        master.setVisitorType("Other Government Agencies");
        master.setSex("Female");
        Visitor a = visit(1, LocalTime.of(9, 0), null, "Guard");
        a.setVisitorType("Active Military Personnel");
        a.setSex("Male");
        Visitor b = visit(1, LocalTime.of(9, 0), null, "Guard");
        b.setVisitorType("Active Military Personnel");
        b.setSex("Male");
        Visitor blank = visit(1, LocalTime.of(9, 0), null, "Guard");
        blank.setVisitorType("  ");

        EmailService.Insights insights = AeroVisitAutomationEngine.insights(List.of(master, a, blank, b));

        assertThat(insights.visitorTypes()).containsExactly(
            Map.entry("Active Military Personnel", 2L), Map.entry("Not stated", 1L), Map.entry("Other Government Agencies", 1L));
        assertThat(insights.sexes()).containsExactly(Map.entry("Male", 2L), Map.entry("Female", 1L), Map.entry("Not stated", 1L));
    }

    @Test
    void satisfactionAveragesReceivedRatingsToOneDecimal() {
        List<Visitor> visits = List.of(visit(1, null, null, "Guard"), visit(1, null, null, "Guard"),
            visit(1, null, null, "Guard"), visit(1, null, null, "Guard"));
        visits.get(0).setRating(5);
        visits.get(1).setRating(4);
        visits.get(2).setRating(4);                       // the fourth visitor has not rated yet

        EmailService.Insights insights = AeroVisitAutomationEngine.insights(visits);

        assertThat(insights.avgRating()).isEqualTo(4.3);
        assertThat(insights.ratingCount()).isEqualTo(3);
        EmailService.Insights none = AeroVisitAutomationEngine.insights(List.of());
        assertThat(none.avgRating()).isNull();
        assertThat(none.ratingCount()).isZero();
    }

    @Test
    void insightsUseActualAttendeesOnly() {
        Visitor attended = visit(2, LocalTime.of(10, 15), null, "Guard");
        attended.setVisitorType("AFP Reservist");
        attended.setRating(5);
        Visitor missed = booking("NO_SHOW", 6);
        missed.setVisitorType("Active Military Personnel");
        missed.setRating(1);
        Visitor open = booking("APPROVED", 3);
        open.setVisitorType("Active Military Personnel");
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(attended, missed, open));

        EmailService.Insights insights = reportFor(DAY).insights();

        assertThat(insights.visitorTypes()).containsExactly(Map.entry("AFP Reservist", 1L));
        assertThat(insights.avgRating()).isEqualTo(5.0);
        assertThat(insights.peakHour()).isEqualTo("10:00 AM - 11:00 AM");
        assertThat(insights.peakHourGuests()).isEqualTo(2);
    }
}
