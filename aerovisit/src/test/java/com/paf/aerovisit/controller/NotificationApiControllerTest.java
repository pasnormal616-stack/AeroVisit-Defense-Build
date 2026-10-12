package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/** Header bell API: latest notifications + unread + pending counts, mark-read, per-role types, staff only. No database. */
@WebMvcTest(NotificationApiController.class)
@Import(SecurityConfig.class)
class NotificationApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AdminNotificationRepository notificationRepo;
    @MockitoBean private VisitorRepository visitorRepo;

    private static AdminNotification notification(long id, String type, String message, boolean read, LocalDateTime at) {
        AdminNotification n = new AdminNotification(type, message, "/admin?filter=pending&sort=desc");
        n.setId(id);
        n.setRead(read);
        n.setCreatedAt(at);
        return n;
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void directorGetsAllTypesWithUnreadAndPendingCountsUncached() throws Exception {
        when(notificationRepo.findTop10ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.DIRECTOR_TYPES)).thenReturn(List.of(
            notification(2, AdminNotification.TYPE_NEGATIVE_FEEDBACK, "Negative feedback from <b>Ana</b>.", false, LocalDateTime.now().minusMinutes(5)),
            notification(1, AdminNotification.TYPE_LARGE_GROUP, "Large group booking: Juan, 20 visitors.", true, LocalDateTime.now().minusHours(3))));
        when(notificationRepo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).thenReturn(1L);
        when(visitorRepo.countByStatusIn(AdminDashboardController.AWAITING_REVIEW_STATUSES)).thenReturn(4L);

        mvc.perform(get("/api/notifications"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.unreadCount").value(1))
            .andExpect(jsonPath("$.pendingCount").value(4))
            .andExpect(jsonPath("$.notifications.length()").value(2))
            .andExpect(jsonPath("$.notifications[0].type").value("NEGATIVE_FEEDBACK"))
            .andExpect(jsonPath("$.notifications[0].message").value("Negative feedback from <b>Ana</b>.")) // raw text; the page uses textContent
            .andExpect(jsonPath("$.notifications[0].read").value(false))
            .andExpect(jsonPath("$.notifications[0].ago").value("5m ago"))
            .andExpect(jsonPath("$.notifications[1].read").value(true))
            .andExpect(jsonPath("$.notifications[1].ago").value("3h ago"))
            .andExpect(jsonPath("$.notifications[1].targetUrl").value("/admin?filter=pending&sort=desc"));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardOnlySeesAndClearsGuardTypes() throws Exception {
        when(notificationRepo.findTop10ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.GUARD_TYPES)).thenReturn(List.of());
        mvc.perform(get("/api/notifications")).andExpect(status().isOk())
            .andExpect(jsonPath("$.notifications.length()").value(0))
            .andExpect(jsonPath("$.pendingCount").value(0));
        // Booking review is Director-only: the guard's bell never even counts the pending queue
        verify(visitorRepo, never()).countByStatusIn(org.mockito.ArgumentMatchers.anyCollection());
        verify(notificationRepo).findTop10ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.GUARD_TYPES);
        verify(notificationRepo).countByTypeInAndIsReadFalse(AdminNotification.GUARD_TYPES);

        when(notificationRepo.markAllReadByTypeIn(AdminNotification.GUARD_TYPES)).thenReturn(3);
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.marked").value(3))
            .andExpect(jsonPath("$.unreadCount").value(0));
        verify(notificationRepo, never()).markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES);
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void directorMarkReadCoversEveryType() throws Exception {
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isOk());
        verify(notificationRepo).markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES);
    }

    @Test
    void visitorsAndSignedOutUsersCannotReadOrClearNotifications() throws Exception {
        mvc.perform(get("/api/notifications").with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/api/notifications/mark-read").with(csrf()).with(anonymous())).andExpect(redirectedUrlPattern("**/login"));
        verify(notificationRepo, never()).findTop10ByTypeInOrderByCreatedAtDescIdDesc(anyCollection());
        verify(notificationRepo, never()).markAllReadByTypeIn(any());
        verify(visitorRepo, never()).countByStatusIn(anyCollection());
    }

    @Test
    @WithMockUser(username = "someone", roles = "VISITOR")
    void otherRolesAreForbidden() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/mark-read").with(csrf())).andExpect(status().isForbidden());
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
