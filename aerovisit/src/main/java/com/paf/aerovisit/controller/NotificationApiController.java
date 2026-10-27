package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Header bell (polled every 30 seconds by the shell script). Staff only (SecurityConfig): a Guard sees only Guard
// types, the Director all of them. Works like a social media feed: opening the bell marks everything SEEN (the red
// badge clears), clicking one item marks it READ (its blue dot goes), and an item can be removed.
@RestController
public class NotificationApiController {

    private final AdminNotificationRepository notificationRepo;
    private final VisitorRepository visitorRepo;

    public NotificationApiController(AdminNotificationRepository notificationRepo, VisitorRepository visitorRepo) {
        this.notificationRepo = notificationRepo;
        this.visitorRepo = visitorRepo;
    }

    // Latest 30 notifications, the badge count (unseen + unread), the unread count, and the Pending Approval count
    @GetMapping("/api/notifications")
    public ResponseEntity<Map<String, Object>> latest(Authentication auth) {
        List<String> types = typesFor(auth);
        if (types.contains(AdminNotification.TYPE_BOOKING_REQUEST)) resolveHandledRequests();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminNotification n : withoutDeletedBookings(notificationRepo.findTop30ByTypeInOrderByCreatedAtDescIdDesc(types))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.getId());
            item.put("type", n.getType());
            item.put("actor", n.getActor());
            item.put("message", n.getMessage());
            item.put("targetUrl", n.getTargetUrl());
            item.put("read", n.isRead());
            item.put("seen", n.isSeen());
            item.put("resolved", n.isResolved());
            item.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
            item.put("ago", ago(n.getCreatedAt(), now));
            // "Today" or "Earlier" heading in the list (server date, like "ago")
            item.put("today", n.getCreatedAt() != null && n.getCreatedAt().toLocalDate().equals(now.toLocalDate()));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", items);
        body.put("badgeCount", notificationRepo.countByTypeInAndIsReadFalseAndSeenFalse(types));
        body.put("unreadCount", notificationRepo.countByTypeInAndIsReadFalse(types));
        // Booking review is Director-only, so a Guard's bell never counts (or links to) pending requests
        body.put("pendingCount", com.paf.aerovisit.config.SecurityConfig.isDirector()
                ? visitorRepo.countByStatusIn(AdminDashboardController.AWAITING_REVIEW_STATUSES) : 0L);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // Opening the bell: this role's notifications are seen, so the red badge clears (blue dots stay until read)
    @PostMapping("/api/notifications/mark-seen")
    public ResponseEntity<Map<String, Object>> markSeen(Authentication auth) {
        int marked = notificationRepo.markAllSeenByTypeIn(typesFor(auth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("marked", marked, "badgeCount", 0));
    }

    // "Mark all as read"
    @PostMapping("/api/notifications/mark-read")
    public ResponseEntity<Map<String, Object>> markRead(Authentication auth) {
        int marked = notificationRepo.markAllReadByTypeIn(typesFor(auth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("marked", marked, "unreadCount", 0));
    }

    // Clicking one notification (or "Mark as read" in its menu)
    @PostMapping("/api/notifications/{id}/read")
    public ResponseEntity<Map<String, Object>> readOne(@PathVariable Long id, Authentication auth) {
        AdminNotification n = ownNotification(id, auth);
        if (n == null) return notFound();
        n.setRead(true);
        n.setSeen(true);
        notificationRepo.save(n);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ok", true));
    }

    // "Remove this notification"
    @PostMapping("/api/notifications/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable Long id, Authentication auth) {
        AdminNotification n = ownNotification(id, auth);
        if (n == null) return notFound();
        notificationRepo.delete(n);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("ok", true));
    }

    // A notification of a type this role sees, or null (another role's, or gone)
    private AdminNotification ownNotification(Long id, Authentication auth) {
        if (id == null) return null;
        return notificationRepo.findById(id).filter(n -> typesFor(auth).contains(n.getType())).orElse(null);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).body(Map.of("ok", false));
    }

    // Booking requests whose booking has left the approval queue (approved, rejected, cancelled, expired) or is gone
    // are resolved, read and seen, so they stop counting without anyone having to click them
    void resolveHandledRequests() {
        List<AdminNotification> open = notificationRepo.findByTypeInAndResolvedFalseAndVisitorIdIsNotNull(AdminNotification.REQUEST_TYPES);
        if (open.isEmpty()) return;
        Map<Long, Visitor> bookings = new HashMap<>();
        for (Visitor v : visitorRepo.findAllById(open.stream().map(AdminNotification::getVisitorId).distinct().toList())) {
            bookings.put(v.getId(), v);
        }
        List<AdminNotification> done = new ArrayList<>();
        for (AdminNotification n : open) {
            Visitor v = bookings.get(n.getVisitorId());
            if (v == null || !v.awaitsApproval()) {
                n.setResolved(true);
                n.setRead(true);
                n.setSeen(true);
                done.add(n);
            }
        }
        if (!done.isEmpty()) notificationRepo.saveAll(done);
    }

    // Notifications about a booking that no longer exists (deleted, or demo data reset) are deleted, never shown
    List<AdminNotification> withoutDeletedBookings(List<AdminNotification> list) {
        List<Long> ids = list.stream().map(AdminNotification::getVisitorId).filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return list;
        java.util.Set<Long> existing = new java.util.HashSet<>();
        visitorRepo.findAllById(ids).forEach(v -> existing.add(v.getId()));
        List<AdminNotification> keep = new ArrayList<>();
        List<AdminNotification> orphans = new ArrayList<>();
        for (AdminNotification n : list) {
            if (n.getVisitorId() != null && !existing.contains(n.getVisitorId())) orphans.add(n); else keep.add(n);
        }
        if (!orphans.isEmpty()) notificationRepo.deleteAll(orphans);
        return keep;
    }

    static List<String> typesFor(Authentication auth) {
        boolean director = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_DIRECTOR".equals(a.getAuthority()));
        return director ? AdminNotification.DIRECTOR_TYPES : AdminNotification.GUARD_TYPES;
    }

    // "Just now", "5m ago", "3h ago", "2d ago" (server clock, so the browser's time zone doesn't matter)
    static String ago(LocalDateTime createdAt, LocalDateTime now) {
        if (createdAt == null) return "";
        long minutes = Math.max(0, Duration.between(createdAt, now).toMinutes());
        if (minutes < 1) return "Just now";
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 60 * 24) return (minutes / 60) + "h ago";
        return (minutes / (60 * 24)) + "d ago";
    }
}
