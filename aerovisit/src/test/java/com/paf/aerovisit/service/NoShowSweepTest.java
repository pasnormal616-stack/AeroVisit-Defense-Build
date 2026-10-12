package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/** The 11:59 PM no-show sweep turns approved bookings whose visit is over (never checked in) into NO_SHOW. Fixed clock, no database. */
class NoShowSweepTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime SWEEP_TIME = LocalDateTime.of(TODAY, LocalTime.of(23, 59));

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);

    private static Visitor approved(long id, LocalDate date, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus("APPROVED");
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setQrCodeUrl("QR_" + id + ".png");
        return v;
    }

    @Test
    void approvedBookingsWhoseVisitIsOverBecomeNoShows() {
        Visitor yesterday = approved(1, TODAY.minusDays(1), "Morning (08:00 AM - 12:00 PM)");
        Visitor lastMonth = approved(2, TODAY.minusDays(30), null);                              // any label, long past
        Visitor morningEnded = approved(3, TODAY, "Morning (08:00 AM - 12:00 PM)");
        Visitor eveningEnded = approved(4, TODAY, "Evening (04:00 PM - 08:00 PM)");             // after 5:30 PM, over by 11:59 PM
        Visitor nightEnded = approved(5, TODAY, "Night (08:00 PM - 11:58 PM)");                 // end minute 23:58 is over
        Visitor endsThisMinute = approved(6, TODAY, "Late Night (09:00 PM - 11:59 PM)");        // 23:59 itself is still open
        Visitor masterToday = approved(7, TODAY, "Master Booking Bypass");                      // unreadable: waits a day
        Visitor tomorrow = approved(8, TODAY.plusDays(1), "Morning (08:00 AM - 12:00 PM)");
        Visitor noDate = approved(9, null, "Morning (08:00 AM - 12:00 PM)");
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(yesterday, lastMonth, morningEnded, eveningEnded,
                nightEnded, endsThisMinute, masterToday, tomorrow, noDate));

        int marked = engine.markNoShows(SWEEP_TIME);

        assertThat(marked).isEqualTo(5);
        for (Visitor v : List.of(yesterday, lastMonth, morningEnded, eveningEnded, nightEnded)) {
            assertThat(v.getStatus()).as("visitor " + v.getId()).isEqualTo("NO_SHOW");
            assertThat(v.getQrCodeUrl()).as("visitor " + v.getId()).isNull();
            verify(repo).save(v);
        }
        for (Visitor v : List.of(endsThisMinute, masterToday, tomorrow, noDate)) {
            assertThat(v.getStatus()).as("visitor " + v.getId()).isEqualTo("APPROVED");
            verify(repo, never()).save(v);
        }
        verifyNoInteractions(emailService); // a no-show is not emailed
    }

    @Test
    void aMasterBookingIsMarkedTheDayAfter() {
        Visitor master = approved(7, TODAY.minusDays(1), "Master Booking Bypass");
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(master));

        assertThat(engine.markNoShows(SWEEP_TIME)).isEqualTo(1);
        assertThat(master.getStatus()).isEqualTo("NO_SHOW");
    }

    @Test
    void aNightWindowIsNotDueAt530PmButIsAt1159Pm() {
        Visitor night = approved(12, TODAY, "Night (06:00 PM - 10:00 PM)");
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(night));

        assertThat(engine.markNoShows(LocalDateTime.of(TODAY, LocalTime.of(17, 30)))).isZero();   // old sweep time: still upcoming
        assertThat(night.getStatus()).isEqualTo("APPROVED");

        assertThat(engine.markNoShows(SWEEP_TIME)).isEqualTo(1);                                  // new sweep time, same day
        assertThat(night.getStatus()).isEqualTo("NO_SHOW");
    }

    @Test
    void theNoShowSweepRunsAt1159PmApartFromThe530PmCheckOutSweep() throws Exception {
        assertThat(AeroVisitAutomationEngine.class.getMethod("executeNoShowSweep").getAnnotation(Scheduled.class).cron())
            .isEqualTo("0 59 23 * * *");
        assertThat(AeroVisitAutomationEngine.class.getMethod("executeBaseSweep").getAnnotation(Scheduled.class).cron())
            .isEqualTo("0 30 17 * * ?");                                                          // check-out sweep unchanged
    }

    @Test
    void theCheckOutSweepNoLongerMarksNoShowsButTheNightSweepDoes() {
        Visitor inside = approved(10, LocalDate.now().minusDays(1), null);     // a past visit: checked out whatever the time
        inside.setStatus("CHECKED-IN");
        inside.setCheckedInBy("Sgt. Ramos");
        Visitor missed = approved(11, LocalDate.now().minusDays(1), null);
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(inside));
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(missed));

        engine.executeBaseSweep();

        assertThat(inside.getStatus()).isEqualTo("CHECKED-OUT");                 // unchanged behaviour
        verify(emailService).sendCheckOutFeedback(anyString(), anyString(), any());
        assertThat(missed.getStatus()).isEqualTo("APPROVED");                     // left for the 11:59 PM sweep

        engine.executeNoShowSweep();

        assertThat(missed.getStatus()).isEqualTo("NO_SHOW");
        assertThat(missed.getQrCodeUrl()).isNull();
    }
}
