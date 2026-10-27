package com.paf.aerovisit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/** The 6:30 AM arrivals alert and the nightly 30-day bell clean-up. No database. */
class BellNotificationJobsTest {

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, mock(EmailService.class));
    private final NotificationService notifications = mock(NotificationService.class);
    private final AdminNotificationRepository notificationRepo = mock(AdminNotificationRepository.class);

    private static Visitor booking(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        return v;
    }

    @Test
    void onlyTodaysApprovedBookingsAreOfferedToTheArrivalsAlert() {
        engine.notificationService = notifications;
        Visitor approved = booking(1, "APPROVED");
        Visitor pending = booking(2, "PENDING");
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(List.of(approved, pending));
        engine.executeArrivalAlerts();
        verify(notifications).arrivingToday(approved);
        verify(notifications, never()).arrivingToday(pending);
    }

    @Test
    void withoutTheBellTheArrivalsJobDoesNothing() {
        engine.executeArrivalAlerts();
        verify(repo, never()).findByVisitDate(any());
    }

    @Test
    void theNightlyPurgeDeletesBellNotificationsOlderThanThirtyDays() {
        engine.notificationRepo = notificationRepo;
        engine.executeDataRetentionPurge();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepo).deleteOlderThan(cutoff.capture());
        org.assertj.core.api.Assertions.assertThat(cutoff.getValue())
            .isBetween(LocalDateTime.now().minusDays(30).minusMinutes(1), LocalDateTime.now().minusDays(30).plusMinutes(1));
    }

    @Test
    void aBellCleanUpErrorDoesNotStopThePurge() {
        engine.notificationRepo = notificationRepo;
        when(notificationRepo.deleteOlderThan(any())).thenThrow(new RuntimeException("db down"));
        org.assertj.core.api.Assertions.assertThatCode(engine::executeDataRetentionPurge).doesNotThrowAnyException();
    }
}
