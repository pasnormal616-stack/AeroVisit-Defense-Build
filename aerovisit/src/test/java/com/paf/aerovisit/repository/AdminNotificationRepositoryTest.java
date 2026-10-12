package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.AdminNotification;

/** Header bell queries: newest 10 per role, unread count, mark-read per role. Runs on in-memory H2, never the real database. */
@DataJpaTest
class AdminNotificationRepositoryTest {

    @Autowired
    private AdminNotificationRepository repo;

    private AdminNotification save(String type, String message, LocalDateTime createdAt, boolean read) {
        AdminNotification n = new AdminNotification(type, message, "/admin");
        n.setCreatedAt(createdAt);
        n.setRead(read);
        return repo.save(n);
    }

    @Test
    void latestTenAreNewestFirstAndOnlyOfTheRolesTypes() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 9, 0);
        for (int i = 0; i < 12; i++) {
            save(AdminNotification.TYPE_LARGE_GROUP, "group " + i, base.plusMinutes(i), false);
        }
        save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "feedback", base.plusHours(5), false);

        List<String> guard = repo.findTop10ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.GUARD_TYPES)
            .stream().map(AdminNotification::getMessage).toList();
        assertThat(guard).hasSize(10).startsWith("group 11", "group 10").doesNotContain("feedback", "group 1", "group 0");

        List<String> director = repo.findTop10ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.DIRECTOR_TYPES)
            .stream().map(AdminNotification::getMessage).toList();
        assertThat(director).hasSize(10).startsWith("feedback", "group 11");
    }

    @Test
    void markReadOnlyTouchesTheRolesUnreadNotifications() {
        LocalDateTime now = LocalDateTime.now();
        save(AdminNotification.TYPE_LARGE_GROUP, "a", now, false);
        save(AdminNotification.TYPE_LARGE_GROUP, "b", now, true);
        save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "c", now, false);

        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.GUARD_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(2);

        // a Guard opening the bell does not clear the Director's feedback alert
        assertThat(repo.markAllReadByTypeIn(AdminNotification.GUARD_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(1);

        assertThat(repo.markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isZero();
    }

    @Test
    void createdAtIsFilledInOnSave() {
        AdminNotification n = repo.save(new AdminNotification(AdminNotification.TYPE_LARGE_GROUP, "x", "/admin"));
        assertThat(n.getCreatedAt()).isNotNull();
        assertThat(n.isRead()).isFalse();
    }
}
