package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 📝 The feedback email goes out G minutes after the booked window ends (default 30), once per booking, for advance
 * bookings and walk-ins, only for visits that were checked in, and never changes the visitor's status. Fixed clock;
 * the repository is mocked and its claim UPDATE behaves like the real one (1 only while no time is stamped).
 */
class FeedbackEmailSweepTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)"; // ends 12:00

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final PortalSettingsRepository settingsRepo = mock(PortalSettingsRepository.class);
    private final FeedbackEmails feedback = new FeedbackEmails(repo, emailService);
    private final List<Visitor> today = new ArrayList<>();
    private final Set<Long> claimed = new HashSet<>();

    @BeforeEach
    void wire() {
        feedback.settingsRepo = settingsRepo;
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        // like the real query: today's CHECKED-IN / CHECKED-OUT visits without a feedback email yet
        when(repo.findByVisitDateAndStatusInAndFeedbackEmailSentAtIsNull(eq(TODAY), any())).thenAnswer(inv ->
                today.stream().filter(v -> FeedbackEmails.ATTENDED_STATUSES.contains(v.getStatus()) && !claimed.contains(v.getId())).toList());
        // like the real UPDATE ... WHERE feedbackEmailSentAt IS NULL: only the first claim of a booking gets 1
        when(repo.claimFeedbackEmail(anyLong(), any())).thenAnswer(inv -> claimed.add(inv.getArgument(0)) ? 1 : 0);
    }

    private static LocalDateTime at(int hour, int minute) {
        return TODAY.atTime(hour, minute);
    }

    private Visitor visit(long id, String status, String window, boolean walkIn) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setSecureToken("token-" + id);
        v.setStatus(status);
        v.setVisitDate(TODAY);
        v.setTimeWindow(window);
        if (walkIn) v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        today.add(v);
        return v;
    }

    @Test
    void runsEveryFifteenMinutes() throws Exception {
        Method sweep = AeroVisitAutomationEngine.class.getMethod("executeFeedbackEmailSweep");
        assertThat(sweep.getAnnotation(Scheduled.class).cron()).isEqualTo("0 */15 * * * *");
    }

    @Test
    void sentOnceThirtyMinutesAfterTheWindowEndsForAnAdvanceBookingAndAWalkIn() {
        Visitor advance = visit(1, "CHECKED-IN", MORNING, false);   // left without being checked out
        Visitor walkIn = visit(2, "CHECKED-OUT", MORNING, true);    // checked out at the gate (an older email-less row)

        assertThat(feedback.sendDue(at(12, 0))).isZero();            // window just ended
        assertThat(feedback.sendDue(at(12, 29))).isZero();           // not before end + 30
        verify(emailService, never()).sendCheckOutFeedback(anyString(), anyString(), anyString());

        assertThat(feedback.sendDue(at(12, 30))).isEqualTo(2);
        verify(emailService).sendCheckOutFeedback("v1@example.com", "Visitor 1", "token-1");
        verify(emailService).sendCheckOutFeedback("v2@example.com", "Visitor 2", "token-2");
        assertThat(advance.getFeedbackEmailSentAt()).isEqualTo(at(12, 30));
        assertThat(walkIn.getFeedbackEmailSentAt()).isEqualTo(at(12, 30));

        // later rounds send nothing again
        assertThat(feedback.sendDue(at(12, 45))).isZero();
        assertThat(feedback.sendDue(at(13, 0))).isZero();
        verify(emailService, times(2)).sendCheckOutFeedback(anyString(), anyString(), anyString());

        // Inside Now is unchanged: the email never checks anyone out
        assertThat(advance.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(advance.getCheckOutTime()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void theDelayFollowsTheSettingAndZeroSendsAtTheWindowEnd() {
        visit(3, "CHECKED-IN", MORNING, false);
        PortalSettings settings = new PortalSettings();
        settings.setFeedbackDelayMinutes(0);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        assertThat(feedback.sendDue(at(11, 59))).isZero();
        assertThat(feedback.sendDue(at(12, 0))).isEqualTo(1);

        visit(4, "CHECKED-IN", MORNING, false);
        settings.setFeedbackDelayMinutes(90);
        assertThat(feedback.sendDue(at(13, 15))).isZero();
        assertThat(feedback.sendDue(at(13, 30))).isEqualTo(1);
    }

    @Test
    void neverSentForVisitsThatDidNotHappen() {
        for (String status : List.of("NO_SHOW", "CANCELLED", "CANCELLED_BY_VISITOR", "CANCELLED_OVERRIDE", "DENIED_ENTRY",
                Visitor.STATUS_PAYMENT_EXPIRED, "APPROVED", "PENDING", "REJECTED")) {
            visit(100 + today.size(), status, MORNING, false);
        }
        assertThat(feedback.sendDue(at(18, 0))).isZero();
        verify(repo, never()).claimFeedbackEmail(anyLong(), any());
        verify(emailService, never()).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void anUnreadableWindowIsLeftToTheCheckOutAndOneFailureNeverStopsTheRest() {
        visit(5, "CHECKED-IN", "Master Booking Bypass", false);     // older label: the end-of-day path sends it
        Visitor broken = visit(6, "CHECKED-IN", MORNING, false);
        Visitor fine = visit(7, "CHECKED-IN", MORNING, false);
        when(repo.claimFeedbackEmail(eq(6L), any())).thenThrow(new RuntimeException("db hiccup"));

        assertThat(feedback.sendDue(at(18, 0))).isEqualTo(1);
        assertThat(broken.getFeedbackEmailSentAt()).isNull();
        assertThat(fine.getFeedbackEmailSentAt()).isNotNull();
        verify(emailService, never()).sendCheckOutFeedback(eq("v5@example.com"), anyString(), anyString());
    }

    @Test
    void aFailedEmailIsLoggedAndTheSweepCarriesOn() {
        visit(8, "CHECKED-IN", MORNING, false);
        visit(9, "CHECKED-IN", MORNING, false);
        doThrow(new RuntimeException("SMTP down")).when(emailService).sendCheckOutFeedback(eq("v8@example.com"), anyString(), anyString());
        assertThat(feedback.sendDue(at(18, 0))).isEqualTo(2); // both claimed; the outbox reports the failed send
        verify(emailService).sendCheckOutFeedback("v9@example.com", "Visitor 9", "token-9");
    }

    @Test
    void theAutoCheckOutAfterTheSweepSendsNothingAgainButStillChecksOut() {
        Visitor v = visit(10, "CHECKED-IN", MORNING, false);
        v.setCheckedInBy("guard");
        assertThat(feedback.sendDue(at(12, 30))).isEqualTo(1);

        AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);
        engine.feedbackEmails = feedback;
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(v));
        assertThat(engine.autoCheckOut(at(17, 30), false)).isEqualTo(1);

        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        verify(emailService, times(1)).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void anAutoCheckOutBeforeTheSweepSendsItAndTheSweepThenSendsNothing() {
        Visitor v = visit(11, "CHECKED-IN", MORNING, false);
        v.setCheckedInBy("guard");
        AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);
        engine.feedbackEmails = feedback;
        when(repo.findByStatus("CHECKED-IN")).thenReturn(List.of(v));
        engine.autoCheckOut(at(23, 59), true);

        assertThat(feedback.sendDue(at(23, 59))).isZero();
        verify(emailService, times(1)).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void theDelaySettingIsChecked() {
        assertThat(FeedbackEmails.delayError(0)).isNull();
        assertThat(FeedbackEmails.delayError(180)).isNull();
        assertThat(FeedbackEmails.delayError(-1)).isNotNull();
        assertThat(FeedbackEmails.delayError(181)).isNotNull();
    }
}
