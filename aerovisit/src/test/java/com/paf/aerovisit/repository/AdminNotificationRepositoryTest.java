package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.AdminNotification;

/** Header bell queries: newest 30 per role, badge (unseen) and unread counts, seen / read per role, clean-up.
 *  Runs on in-memory H2, never the real database. */
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
    void latestThirtyAreNewestFirstAndOnlyOfTheRolesTypes() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 9, 0);
        for (int i = 0; i < 32; i++) {
            save(AdminNotification.TYPE_ARRIVING_TODAY, "group " + i, base.plusMinutes(i), false);
        }
        save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "feedback", base.plusHours(5), false);

        List<String> guard = repo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.GUARD_TYPES)
            .stream().map(AdminNotification::getMessage).toList();
        assertThat(guard).hasSize(30).startsWith("group 31", "group 30").doesNotContain("feedback", "group 1", "group 0");

        List<String> director = repo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(AdminNotification.DIRECTOR_TYPES)
            .stream().map(AdminNotification::getMessage).toList();
        assertThat(director).hasSize(30).startsWith("feedback", "group 31");
    }

    @Test
    void openingTheBellClearsTheBadgeButLeavesTheBlueDots() {
        LocalDateTime now = LocalDateTime.now();
        save(AdminNotification.TYPE_BOOKING_REQUEST, "a", now, false);
        save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "b", now, false);
        save(AdminNotification.TYPE_ARRIVING_TODAY, "c", now, false);

        assertThat(repo.countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(3);
        // a Guard opening the bell does not clear the Director's badge
        assertThat(repo.markAllSeenByTypeIn(AdminNotification.GUARD_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(2);

        assertThat(repo.markAllSeenByTypeIn(AdminNotification.DIRECTOR_TYPES)).isEqualTo(2);
        assertThat(repo.countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.DIRECTOR_TYPES)).isZero();
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(3);
    }

    @Test
    void markReadOnlyTouchesTheRolesUnreadNotifications() {
        LocalDateTime now = LocalDateTime.now();
        save(AdminNotification.TYPE_ARRIVING_TODAY, "a", now, false);
        save(AdminNotification.TYPE_ARRIVING_TODAY, "b", now, true);
        save(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "c", now, false);

        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.GUARD_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(2);

        assertThat(repo.markAllReadByTypeIn(AdminNotification.GUARD_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isEqualTo(1);

        assertThat(repo.markAllReadByTypeIn(AdminNotification.DIRECTOR_TYPES)).isEqualTo(1);
        assertThat(repo.countByTypeInAndIsReadFalse(AdminNotification.DIRECTOR_TYPES)).isZero();
        assertThat(repo.countByTypeInAndIsReadFalseAndSeenFalse(AdminNotification.DIRECTOR_TYPES)).isZero();
    }

    @Test
    void openRequestsAreFoundByBookingAndDeletedWithIt() {
        LocalDateTime now = LocalDateTime.now();
        AdminNotification request = repo.save(new AdminNotification(AdminNotification.TYPE_BOOKING_REQUEST, "Ana", "requested", "/admin", 5L));
        AdminNotification feedback = repo.save(new AdminNotification(AdminNotification.TYPE_NEGATIVE_FEEDBACK, "Ana", "left negative feedback", "/admin", 5L));
        repo.save(new AdminNotification(AdminNotification.TYPE_BOOKING_REQUEST, "Ben", "requested", "/admin", 6L));
        save(AdminNotification.TYPE_AI_OFFLINE, "no booking", now, false);

        assertThat(repo.findByTypeInAndResolvedFalseAndVisitorIdIsNotNull(AdminNotification.REQUEST_TYPES)).hasSize(2);
        assertThat(repo.findByVisitorIdAndTypeInAndResolvedFalse(5L, AdminNotification.REQUEST_TYPES))
            .extracting(AdminNotification::getId).containsExactly(request.getId());

        assertThat(repo.deleteByVisitorId(5L)).isEqualTo(2);
        assertThat(repo.findById(feedback.getId())).isEmpty();
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void theNightlyCleanUpDeletesOnlyOldNotifications() {
        LocalDateTime now = LocalDateTime.now();
        save(AdminNotification.TYPE_BOOKING_REQUEST, "old", now.minusDays(31), true);
        save(AdminNotification.TYPE_BOOKING_REQUEST, "recent", now.minusDays(29), false);
        assertThat(repo.deleteOlderThan(now.minusDays(30))).isEqualTo(1);
        assertThat(repo.findAll()).extracting(AdminNotification::getMessage).containsExactly("recent");
    }

    @Test
    void dedupeKeysAreFound() {
        AdminNotification n = new AdminNotification(AdminNotification.TYPE_SLOT_FILLING, "Morning slot", "on Mon is full", "/admin", null);
        n.setDedupeKey("SLOT:2026-10-12:Morning:FULL");
        repo.save(n);
        assertThat(repo.existsByDedupeKey("SLOT:2026-10-12:Morning:FULL")).isTrue();
        assertThat(repo.existsByDedupeKey("SLOT:2026-10-12:Morning:LOW")).isFalse();
    }

    @Test
    void createdAtIsFilledInOnSaveAndFlagsStartFalse() {
        AdminNotification n = repo.save(new AdminNotification(AdminNotification.TYPE_ARRIVING_TODAY, "x", "/admin"));
        assertThat(n.getCreatedAt()).isNotNull();
        assertThat(n.isRead()).isFalse();
        assertThat(n.isSeen()).isFalse();
        assertThat(n.isResolved()).isFalse();
    }
}
