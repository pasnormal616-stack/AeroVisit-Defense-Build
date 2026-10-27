package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;

/** What raises a header bell notification, how it reads, and that a failure never escapes. No database. */
class NotificationServiceTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";

    private final AdminNotificationRepository repo = mock(AdminNotificationRepository.class);
    private final NotificationService service = new NotificationService(repo);

    private AdminNotification saved() {
        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    private static Visitor visitor(int groupSize) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Santos, Maria");
        v.setGroupSize(groupSize);
        v.setStatus("PENDING");
        v.setVisitDate(LocalDate.of(2026, 10, 12));
        v.setTimeWindow(MORNING);
        return v;
    }

    // ---------- bookings ----------

    @Test
    void aPendingBookingRaisesARequestWithTheVisitorAsActor() {
        service.bookingRequest(visitor(3));
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_BOOKING_REQUEST);
        assertThat(n.getActor()).isEqualTo("Santos, Maria");
        assertThat(n.getMessage()).isEqualTo("requested a visit on Mon, Oct 12 · Morning · group of 3.");
        assertThat(n.getTargetUrl()).isEqualTo("/admin?filter=pending&sort=desc&dateFilter=2026-10-12");
        assertThat(n.getVisitorId()).isEqualTo(7L);
        assertThat(n.isRead()).isFalse();
        assertThat(n.isSeen()).isFalse();
        assertThat(n.isResolved()).isFalse();
    }

    @Test
    void largeGroupsAndMasterBookingsAreCalledOut() {
        service.bookingRequest(visitor(16));
        assertThat(saved().getMessage()).endsWith("group of 16. Large group.");

        AdminNotificationRepository repo2 = mock(AdminNotificationRepository.class);
        Visitor master = visitor(80);
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        new NotificationService(repo2).bookingRequest(master);
        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repo2).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AdminNotification.TYPE_MASTER_BOOKING_REQUEST);
        assertThat(captor.getValue().getMessage()).isEqualTo("requested a Master Booking for Mon, Oct 12 · group of 80.");
    }

    @Test
    void aBookingThatIsNotWaitingForApprovalRaisesNoRequest() {
        Visitor approved = visitor(2);
        approved.setStatus("APPROVED");
        service.bookingRequest(approved);
        service.bookingRequest(null);
        verify(repo, never()).save(any());
    }

    @Test
    void aRescheduleResolvesTheOlderRequestAndRaisesANewOne() {
        AdminNotification older = new AdminNotification(AdminNotification.TYPE_BOOKING_REQUEST, "Santos, Maria", "requested…", "/admin", 7L);
        when(repo.findByVisitorIdAndTypeInAndResolvedFalse(7L, AdminNotification.REQUEST_TYPES)).thenReturn(List.of(older));
        service.bookingRescheduled(visitor(4));
        assertThat(older.isResolved()).isTrue();
        assertThat(older.isRead()).isTrue();
        verify(repo).saveAll(List.of(older));
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_BOOKING_RESCHEDULED);
        assertThat(n.getMessage()).isEqualTo("changed their booking to Mon, Oct 12 · Morning · group of 4. It needs approval again.");
    }

    @Test
    void aCancellationLinksToThatDay() {
        service.bookingCancelled(visitor(1));
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_BOOKING_CANCELLED);
        assertThat(n.getMessage()).isEqualTo("cancelled their visit on Mon, Oct 12 · Morning · 1 visitor.");
        assertThat(n.getTargetUrl()).isEqualTo("/admin?filter=all&dateFilter=2026-10-12");
    }

    @Test
    void aSlotWithFiveSeatsOrFewerLeftIsAlmostFullAndZeroIsFull() {
        service.slotFilling(LocalDate.of(2026, 10, 12), MORNING, 5, 50);
        AdminNotification low = saved();
        assertThat(low.getType()).isEqualTo(AdminNotification.TYPE_SLOT_FILLING);
        assertThat(low.getActor()).isEqualTo("Morning slot");
        assertThat(low.getMessage()).isEqualTo("on Mon, Oct 12 is almost full: 5 seats left of 50.");
        assertThat(low.getDedupeKey()).isEqualTo("SLOT:2026-10-12:" + MORNING + ":LOW");
        assertThat(low.getTargetUrl()).isEqualTo("/admin?capacityDate=2026-10-12");

        AdminNotificationRepository repo2 = mock(AdminNotificationRepository.class);
        new NotificationService(repo2).slotFilling(LocalDate.of(2026, 10, 12), MORNING, 0, 50);
        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repo2).save(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("on Mon, Oct 12 is now full (50 of 50 seats taken).");
        assertThat(captor.getValue().getDedupeKey()).endsWith(":FULL");
    }

    @Test
    void aSlotWithRoomLeftOrAlreadyNotifiedRaisesNothing() {
        service.slotFilling(LocalDate.of(2026, 10, 12), MORNING, 6, 50);
        when(repo.existsByDedupeKey(anyString())).thenReturn(true);
        service.slotFilling(LocalDate.of(2026, 10, 12), MORNING, 2, 50);
        service.slotFilling(null, MORNING, 0, 50);
        verify(repo, never()).save(any());
    }

    // ---------- gate, security, system ----------

    @Test
    void aGateDenialCarriesTheReason() {
        service.gateDenied(visitor(1), "ID does not match");
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_GATE_DENIED);
        assertThat(n.getMessage()).isEqualTo("was denied entry at the gate: ID does not match.");
    }

    @Test
    void onlyBigGroupsAndMasterBookingsAreAnnouncedOnTheirDay() {
        service.arrivingToday(visitor(15));
        verify(repo, never()).save(any());
        service.arrivingToday(visitor(16));
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_ARRIVING_TODAY);
        assertThat(n.getMessage()).isEqualTo("arrives today · Morning · group of 16.");
        assertThat(n.getDedupeKey()).isEqualTo("ARRIVING:7:2026-10-12");
        assertThat(AdminNotification.GUARD_TYPES).contains(AdminNotification.TYPE_ARRIVING_TODAY);
    }

    @Test
    void aBannedFaceAttemptLinksToTheBanListWithoutABooking() {
        Visitor attempt = visitor(1);
        attempt.setId(null);
        service.bannedFaceAttempt(attempt);
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_BANNED_FACE_ATTEMPT);
        assertThat(n.getMessage()).isEqualTo("tried to book Mon, Oct 12 but their face matches a ban. The booking was refused.");
        assertThat(n.getTargetUrl()).isEqualTo("/admin/blacklist");
        assertThat(n.getVisitorId()).isNull();
    }

    @Test
    void aFailedApprovalEmailAndAnOfflineAiServiceAreReported() {
        service.approvalEmailFailed(visitor(1));
        assertThat(saved().getType()).isEqualTo(AdminNotification.TYPE_EMAIL_FAILED);

        AdminNotificationRepository repo2 = mock(AdminNotificationRepository.class);
        new NotificationService(repo2).aiServiceOffline();
        ArgumentCaptor<AdminNotification> captor = ArgumentCaptor.forClass(AdminNotification.class);
        verify(repo2).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AdminNotification.TYPE_AI_OFFLINE);
        assertThat(captor.getValue().getActor()).isEqualTo("AI service");
        assertThat(captor.getValue().getDedupeKey()).startsWith("AI_OFFLINE:");
    }

    @Test
    void theAiOfflineNoticeIsRaisedAtMostOnceAnHour() {
        when(repo.existsByDedupeKey(anyString())).thenReturn(true);
        service.aiServiceOffline();
        verify(repo, never()).save(any());
    }

    // ---------- feedback ----------

    @Test
    void negativeSentimentRaisesAFeedbackNotificationWithAShortPreview() {
        Visitor v = visitor(1);
        v.setRating(1);
        v.setFeedbackSentiment("NEGATIVE");
        v.setFeedback("Mabaho ang CR,\n walang tubig. " + "x".repeat(200));
        service.negativeFeedback(v);
        AdminNotification n = saved();
        assertThat(n.getType()).isEqualTo(AdminNotification.TYPE_NEGATIVE_FEEDBACK);
        assertThat(n.getActor()).isEqualTo("Santos, Maria");
        assertThat(n.getMessage()).startsWith("left negative feedback (1/5): \"Mabaho ang CR, walang tubig. xxx").endsWith("…\"");
        assertThat(n.getMessage().length()).isLessThanOrEqualTo(255);
        assertThat(n.getTargetUrl()).isEqualTo("/admin/analytics#feedbackFeed");
        assertThat(n.getVisitorId()).isEqualTo(7L); // removed with the booking
    }

    @Test
    void lowercaseNegativeLabelAndEmptyCommentStillCount() {
        Visitor v = visitor(1);
        v.setName(null);
        v.setFeedbackSentiment("negative");
        service.negativeFeedback(v);
        AdminNotification n = saved();
        assertThat(n.getActor()).isEqualTo("A visitor");
        assertThat(n.getMessage()).isEqualTo("left negative feedback.");
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
    void aDatabaseErrorNeverReachesTheFlowThatRaisedIt() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        when(repo.findByVisitorIdAndTypeInAndResolvedFalse(anyLong(), eq(AdminNotification.REQUEST_TYPES))).thenThrow(new RuntimeException("db down"));
        Visitor v = visitor(40);
        v.setFeedbackSentiment("NEGATIVE");
        assertThatCode(() -> service.bookingRequest(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.bookingRescheduled(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.bookingCancelled(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.slotFilling(v.getVisitDate(), MORNING, 0, 40)).doesNotThrowAnyException();
        assertThatCode(() -> service.gateDenied(v, "x")).doesNotThrowAnyException();
        assertThatCode(() -> service.arrivingToday(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.bannedFaceAttempt(v)).doesNotThrowAnyException();
        assertThatCode(() -> service.approvalEmailFailed(v)).doesNotThrowAnyException();
        assertThatCode(service::aiServiceOffline).doesNotThrowAnyException();
        assertThatCode(() -> service.negativeFeedback(v)).doesNotThrowAnyException();
    }
}
