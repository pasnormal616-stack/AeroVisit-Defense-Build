package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;

/** What raises a header bell notification, and that a failure never escapes. No database. */
class NotificationServiceTest {

    private final AdminNotificationRepository repo = mock(AdminNotificationRepository.class);
    private final NotificationService service = new NotificationService(repo);

    private AdminNotification saved() {
        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    private static Visitor visitor(int groupSize) {
        Visitor v = new Visitor();
        v.setName("Santos, Maria");
        v.setGroupSize(groupSize);
        v.setVisitDate(LocalDate.of(2026, 10, 12));
        return v;
    }

    @Test
    void groupsAboveFifteenRaiseALargeGroupNotification() {
        service.largeGroupBooking(visitor(16));
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_LARGE_GROUP);
        assertThat(n.getMessage()).isEqualTo("Large group booking: Santos, Maria, 16 visitors on 2026-10-12.");
        assertThat(n.getTargetUrl()).isEqualTo("/admin?filter=pending&sort=desc");
        assertThat(n.isRead()).isFalse();
    }

    @Test
    void groupsOfFifteenOrFewerDoNot() {
        service.largeGroupBooking(visitor(15));
        service.largeGroupBooking(visitor(1));
        service.largeGroupBooking(null);
        verify(repo, never()).save(any());
    }

    @Test
    void negativeSentimentRaisesAFeedbackNotificationWithAShortPreview() {
        Visitor v = visitor(1);
        v.setRating(1);
        v.setFeedbackSentiment("NEGATIVE");
        v.setFeedback("Mabaho ang CR,\n walang tubig. " + "x".repeat(200));
        service.negativeFeedback(v);
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_NEGATIVE_FEEDBACK);
        assertThat(n.getMessage()).startsWith("Negative feedback from Santos, Maria (1/5): \"Mabaho ang CR, walang tubig. xxx").endsWith("…\"");
        assertThat(n.getMessage().length()).isLessThanOrEqualTo(255);
        assertThat(n.getTargetUrl()).isEqualTo("/admin/analytics#feedbackFeed");
    }

    @Test
    void lowercaseNegativeLabelAndEmptyCommentStillCount() {
        Visitor v = visitor(1);
        v.setName(null);
        v.setFeedbackSentiment("negative");
        service.negativeFeedback(v);
        assertThat(saved().getMessage()).isEqualTo("Negative feedback from a visitor.");
    }

    @Test
    void positiveNeutralOrMissingSentimentDoesNot() {
        for (String label : new String[] {"POSITIVE", "NEUTRAL", null, ""}) {
            Visitor v = visitor(1);
            v.setFeedbackSentiment(label);
            service.negativeFeedback(v);
        }
        service.negativeFeedback(null);
        verify(repo, never()).save(any());
    }

    @Test
    void aDatabaseErrorNeverReachesTheBookingOrFeedbackFlow() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        Visitor v = visitor(40);
        v.setFeedbackSentiment("NEGATIVE");
        assertThatCode(() -> service.largeGroupBooking(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.negativeFeedback(v)).doesNotThrowAnyException();
    }
}
