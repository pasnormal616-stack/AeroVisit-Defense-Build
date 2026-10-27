package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/** Header bell API: feed + badge / unread / pending counts, seen, read, remove, handled requests, deleted bookings,
 *  per-role types, staff only. No database. */
@WebMvcTest(NotificationApiController.class)
@Import(SecurityConfig.class)
class NotificationApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AdminNotificationRepository notificationRepo;
    @MockitoBean private VisitorRepository visitorRepo;

    private static AdminNotification notification(long id, String type, String actor, String message, boolean read, LocalDateTime at, Long visitorId) {
        AdminNotification n = new AdminNotification(type, actor, message, "/admin?filter=pending&sort=desc", visitorId);
        n.setId(id);
        n.setRead(read);
        n.setCreatedAt(at);
        return n;
    }

    private static Visitor booking(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setStatus(status);
        return v;
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void directorGetsTheFeedWithBadgeUnreadAndPendingCountsUncached() throws Exception {
        when(notificationRepo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.DIRECTOR_TYPES)).thenReturn(List.of(
            notification(2, AdminNotification.TYPE_NEGATIVE_FEEDBACK, "<b>Ana</b>", "left negative feedback.", false, LocalDateTime.now().minusMinutes(5), null),
            notification(1, AdminNotification.TYPE_ARRIVING_TODAY, "Juan", "arrives today · group of 20.", true, LocalDateTime.now().minusDays(2), null)));
        when(notificationRepo.countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.DIRECTOR_TYPES)).thenReturn(1L);
        when(notificationRepo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).thenReturn(1L);
        when(visitorRepo.countByStatusIn(AdminDashboardController.AWAITING_REVIEW_STATUSES)).thenReturn(4L);

        mvc.perform(get("/api/notifications"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.badgeCount").value(1))
            .andExpect(jsonPath("$.unreadCount").value(1))
            .andExpect(jsonPath("$.pendingCount").value(4))
            .andExpect(jsonPath("$.notifications.length()").value(2))
            .andExpect(jsonPath("$.notifications[0].type").value("NEGATIVE_FEEDBACK"))
            .andExpect(jsonPath("$.notifications[0].actor").value("<b>Ana</b>")) // raw text; the page uses textContent
            .andExpect(jsonPath("$.notifications[0].message").value("left negative feedback."))
            .andExpect(jsonPath("$.notifications[0].read").value(false))
            .andExpect(jsonPath("$.notifications[0].seen").value(false))
            .andExpect(jsonPath("$.notifications[0].resolved").value(false))
            .andExpect(jsonPath("$.notifications[0].ago").value("5m ago"))
            .andExpect(jsonPath("$.notifications[0].today").value(true))
            .andExpect(jsonPath("$.notifications[1].read").value(true))
            .andExpect(jsonPath("$.notifications[1].ago").value("2d ago"))
            .andExpect(jsonPath("$.notifications[1].today").value(false))
            .andExpect(jsonPath("$.notifications[1].targetUrl").value("/admin?filter=pending&sort=desc"));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void requestsWhoseBookingWasApprovedOrDeletedAreResolvedByThemselves() throws Exception {
        AdminNotification approved = notification(1, AdminNotification.TYPE_BOOKING_REQUEST, "Ana", "requested", false, LocalDateTime.now(), 10L);
        AdminNotification waiting = notification(2, AdminNotification.TYPE_BOOKING_REQUEST, "Ben", "requested", false, LocalDateTime.now(), 11L);
        AdminNotification gone = notification(3, AdminNotification.TYPE_MASTER_BOOKING_REQUEST, "Cara", "requested", false, LocalDateTime.now(), 12L);
        when(notificationRepo.findByTypeInAndResolvedFalseAndVisitorIdIsNotNull(AdminNotification.REQUEST_TYPES)).thenReturn(List.of(approved, waiting, gone));
        when(visitorRepo.findAllById(List.of(10L, 11L, 12L))).thenReturn(List.of(booking(10, "APPROVED"), booking(11, "PENDING")));

        mvc.perform(get("/api/notifications")).andExpect(status().isOk());

        assertThat(approved.isResolved()).isTrue();
        assertThat(approved.isRead()).isTrue();
        assertThat(approved.isSeen()).isTrue();
        assertThat(waiting.isResolved()).isFalse();
        assertThat(waiting.isRead()).isFalse();
        assertThat(gone.isResolved()).isTrue();
        verify(notificationRepo).saveAll(List.of(approved, gone));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void notificationsOfADeletedBookingAreDeletedNotShown() throws Exception {
        AdminNotification orphan = notification(5, AdminNotification.TYPE_NEGATIVE_FEEDBACK, "Ana", "left negative feedback.", false, LocalDateTime.now(), 20L);
        AdminNotification kept = notification(4, AdminNotification.TYPE_NEGATIVE_FEEDBACK, "Ben", "left negative feedback.", false, LocalDateTime.now(), 21L);
        AdminNotification system = notification(3, AdminNotification.TYPE_AI_OFFLINE, "AI service", "is not answering.", false, LocalDateTime.now(), null);
        when(notificationRepo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.DIRECTOR_TYPES)).thenReturn(List.of(orphan, kept, system));
        when(visitorRepo.findAllById(List.of(20L, 21L))).thenReturn(List.of(booking(21, "CHECKED-OUT")));

        mvc.perform(get("/api/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.notifications.length()").value(2))
            .andExpect(jsonPath("$.notifications[0].actor").value("Ben"))
            .andExpect(jsonPath("$.notifications[1].actor").value("AI service"));
        verify(notificationRepo).deleteAll(List.of(orphan));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardOnlySeesAndClearsGuardTypes() throws Exception {
        when(notificationRepo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.GUARD_TYPES)).thenReturn(List.of());
        mvc.perform(get("/api/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.notifications.length()").value(0))
            .andExpect(jsonPath("$.pendingCount").value(0));
        // Booking review is Director-only: the guard's bell never counts the pending queue or resolves requests
        verify(visitorRepo, never()).countByStatusIn(anyCollection());
        verify(notificationRepo, never()).findByTypeInAndResolvedFalseAndVisitorIdIsNotNull(anyCollection());
        verify(notificationRepo).countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.GUARD_TYPES);

        when(notificationRepo.markAllSeenByTypeIn(AdminNotification.GUARD_TYPES)).thenReturn(2);
        mvc.perform(post("/api/notifications/mark-seen").with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.marked").value(2))
            .andExpect(jsonPath("$.badgeCount").value(0));
        when(notificationRepo.markAllReadByTypeIn(AdminNotification.GUARD_TYPES)).thenReturn(3);
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.marked").value(3))
            .andExpect(jsonPath("$.unreadCount").value(0));
        verify(notificationRepo, never()).markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES);
        verify(notificationRepo, never()).markAllSeenByTypeIn(AdminNotification.DIRECTOR_TYPES);
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void directorMarkReadCoversEveryType() throws Exception {
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isOk());
        verify(notificationRepo).markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES);
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void clickingOneMarksItReadAndRemoveDeletesIt() throws Exception {
        AdminNotification n = notification(9, AdminNotification.TYPE_GATE_DENIED, "Ana", "was denied entry.", false, LocalDateTime.now(), null);
        when(notificationRepo.findById(9L)).thenReturn(Optional.of(n));

        mvc.perform(post("/api/notifications/9/read").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertThat(n.isRead()).isTrue();
        assertThat(n.isSeen()).isTrue();
        verify(notificationRepo).save(n);

        mvc.perform(post("/api/notifications/9/dismiss").with(csrf())).andExpect(status().isOk());
        verify(notificationRepo).delete(n);

        mvc.perform(post("/api/notifications/404/dismiss").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGuardCannotReadOrRemoveTheDirectorsNotifications() throws Exception {
        AdminNotification n = notification(9, AdminNotification.TYPE_NEGATIVE_FEEDBACK, "Ana", "left negative feedback.", false, LocalDateTime.now(), null);
        when(notificationRepo.findById(9L)).thenReturn(Optional.of(n));
        mvc.perform(post("/api/notifications/9/read").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/9/dismiss").with(csrf())).andExpect(status().isNotFound());
        assertThat(n.isRead()).isFalse();
        verify(notificationRepo, never()).delete(any(AdminNotification.class));
    }

    @Test
    void visitorsAndSignedOutUsersCannotReadOrClearNotifications() throws Exception {
        mvc.perform(get("/api/notifications").with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/api/notifications/mark-read").with(csrf()).with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/api/notifications/mark-seen").with(csrf()).with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/api/notifications/1/dismiss").with(csrf()).with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        verify(notificationRepo, never()).findTop30ByTypeInOrderByCreatedAtDescIdDesc(anyCollection());
        verify(notificationRepo, never()).markAllReadByTypeIn(any());
        verify(notificationRepo, never()).deleteAll(anyIterable());
        verify(visitorRepo, never()).countByStatusIn(anyCollection());
    }

    @Test
    @WithMockUser(username = "someone", roles = "VISITOR")
    void otherRolesAreForbidden() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/1/dismiss").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void agoLabels() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 5, 12, 0);
        assertThat(NotificationApiController.ago(now.minusSeconds(20), now)).isEqualTo("Just now");
        assertThat(NotificationApiController.ago(now.minusMinutes(59), now)).isEqualTo("59m ago");
        assertThat(NotificationApiController.ago(now.minusHours(23), now)).isEqualTo("23h ago");
        assertThat(NotificationApiController.ago(now.minusDays(3), now)).isEqualTo("3d ago");
        assertThat(NotificationApiController.ago(now.plusMinutes(2), now)).isEqualTo("Just now"); // clock skew
        assertThat(NotificationApiController.ago(null, now)).isEmpty();
    }
}
