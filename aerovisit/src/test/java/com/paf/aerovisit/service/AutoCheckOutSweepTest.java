package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * Auto check-out: the 5:30 PM sweep only checks out visitors whose time window has ended, so a night-window visitor
 * still inside stays CHECKED-IN; the 11:59 PM sweep checks out everyone left for the day. Fixed clock, no database.
 */
class AutoCheckOutSweepTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);
    private static final LocalDateTime FIVE_THIRTY = LocalDateTime.of(TODAY, LocalTime.of(17, 30));
    private static final LocalDateTime ELEVEN_FIFTY_NINE = LocalDateTime.of(TODAY, LocalTime.of(23, 59));

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);

    private static Visitor inside(long id, LocalDate date, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setSecureToken("token-" + id);
        v.setStatus("CHECKED-IN");
        v.setCheckedInBy("Sgt. Ramos");
        v.setVisitDate(date);
        v.setTimeWindow(window);
        return v;
    }

    private void assertCheckedOut(Visitor v, LocalDateTime at) {
        assertThat(v.getStatus()).as("visitor " + v.getId()).isEqualTo("CHECKED-OUT");
        assertThat(v.getCheckOutTime()).isEqualTo(at);
        assertThat(v.getCheckedInBy()).isEqualTo("Sgt. Ramos (Auto-Sweep)");
        verify(repo).save(v);
        verify(emailService).sendCheckOutFeedback(v.getEmail(), v.getName(), v.getSecureToken());
    }

    private void assertStillInside(Visitor v) {
        assertThat(v.getStatus()).as("visitor " + v.getId()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckOutTime()).isNull();
        assertThat(v.getCheckedInBy()).isEqualTo("Sgt. Ramos");
        verify(repo, never()).save(v);
        verify(emailService, never()).sendCheckOutFeedback(v.getEmail(), v.getName(), v.getSecureToken());
    }

    @Test
    void atFiveThirtyOnlyVisitorsWhoseWindowHasEndedAreCheckedOut() {
        Visitor morning = inside(1, TODAY, "Morning (08:00 AM - 12:00 PM)");
        Visitor endedLastMinute = inside(2, TODAY, "Afternoon (01:00 PM - 05:29 PM)");      // end minute 17:29 is over
        Visitor yesterdayNight = inside(3, TODAY.minusDays(1), "Night (06:00 PM - 10:00 PM)"); // a past date is always over
        Visitor endsThisMinute = inside(4, TODAY, "Late (01:00 PM - 05:30 PM)");            // 17:30 itself is still open
        Visitor evening = inside(5, TODAY, "Evening (04:00 PM - 08:00 PM)");                // active right now
        Visitor nightShift = inside(6, TODAY, "Night (06:00 PM - 11:00 PM)");               // hasn't started yet
        Visitor master = inside(7, TODAY, "Master Booking Bypass");                         // unreadable: waits for 11:59 PM
        Visitor noWindow = inside(8, TODAY, null);
        Visitor tomorrow = inside(9, TODAY.plusDays(1), "Morning (08:00 AM - 12:00 PM)");
        Visitor noDate = inside(10, null, "Morning (08:00 AM - 12:00 PM)");
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(morning, endedLastMinute, yesterdayNight, endsThisMinute,
                evening, nightShift, master, noWindow, tomorrow, noDate));

        assertThat(engine.autoCheckOut(FIVE_THIRTY, false)).isEqualTo(3);

        for (Visitor v : List.of(morning, endedLastMinute, yesterdayNight)) assertCheckedOut(v, FIVE_THIRTY);
        for (Visitor v : List.of(endsThisMinute, evening, nightShift, master, noWindow, tomorrow, noDate)) assertStillInside(v);
    }

    @Test
    void atElevenFiftyNineEveryoneLeftForTheDayIsCheckedOut() {
        Visitor nightShift = inside(1, TODAY, "Night (06:00 PM - 11:00 PM)");
        Visitor untilMidnight = inside(2, TODAY, "Late Night (09:00 PM - 11:59 PM)");
        Visitor master = inside(3, TODAY, "Master Booking Bypass");
        Visitor yesterday = inside(4, TODAY.minusDays(1), null);
        Visitor tomorrow = inside(5, TODAY.plusDays(1), "Morning (08:00 AM - 12:00 PM)");
        Visitor noDate = inside(6, null, null);
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(nightShift, untilMidnight, master, yesterday, tomorrow, noDate));

        assertThat(engine.autoCheckOut(ELEVEN_FIFTY_NINE, true)).isEqualTo(4);

        for (Visitor v : List.of(nightShift, untilMidnight, master, yesterday)) assertCheckedOut(v, ELEVEN_FIFTY_NINE);
        for (Visitor v : List.of(tomorrow, noDate)) assertStillInside(v);
    }

    @Test
    void aNightVisitorSkippedAtFiveThirtyIsCheckedOutOnceTheirWindowEnds() {
        Visitor night = inside(1, TODAY, "Night (06:00 PM - 10:00 PM)");
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(night));

        assertThat(engine.autoCheckOut(FIVE_THIRTY, false)).isZero();
        assertThat(engine.autoCheckOut(LocalDateTime.of(TODAY, LocalTime.of(21, 0)), false)).isZero();   // window still active
        assertStillInside(night);

        LocalDateTime afterWindow = LocalDateTime.of(TODAY, LocalTime.of(22, 1));
        assertThat(engine.autoCheckOut(afterWindow, false)).isEqualTo(1);
        assertCheckedOut(night, afterWindow);
    }

    @Test
    void theElevenFiftyNineSweepChecksOutBeforeMarkingNoShows() {
        Visitor stillInside = inside(1, LocalDate.now(), "Night (06:00 PM - 11:00 PM)");
        Visitor missed = inside(2, LocalDate.now().minusDays(1), null);
        missed.setStatus("APPROVED");
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(stillInside));
        when(repo.findByStatus("APPROVED")).thenReturn(List.of(missed));

        engine.executeNoShowSweep();

        assertThat(stillInside.getStatus()).isEqualTo("CHECKED-OUT");     // endOfDay: today is over whatever the clock says
        verify(emailService).sendCheckOutFeedback(anyString(), anyString(), anyString());
        assertThat(missed.getStatus()).isEqualTo("NO_SHOW");               // a checked-in visitor is never a no-show
    }
}
