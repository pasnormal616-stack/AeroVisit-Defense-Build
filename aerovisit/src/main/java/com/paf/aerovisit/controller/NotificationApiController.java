package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.AdminNotification;
import com.paf.aerovisit.repository.AdminNotificationRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Header bell dropdown (polled every 30 seconds by the shell script). Staff only (SecurityConfig).
// A Guard sees only Guard-relevant types; the Director sees all of them.
@RestController
public class NotificationApiController {

    private final AdminNotificationRepository notificationRepo;
    private final VisitorRepository visitorRepo;

    public NotificationApiController(AdminNotificationRepository notificationRepo, VisitorRepository visitorRepo) {
        this.notificationRepo = notificationRepo;
        this.visitorRepo = visitorRepo;
    }

    // Latest 10 notifications, the unread count, and the Pending Approval count the bell has always shown
    @GetMapping("/api/notifications")
    public ResponseEntity<Map<String, Object>> latest(Authentication auth) {
        List<String> types = typesFor(auth);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (AdminNotification n : notificationRepo.findTop10ByTypeInOrderByCreatedAtDescIdDesc(types)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.getId());
            item.put("type", n.getType());
            item.put("message", n.getMessage());
            item.put("targetUrl", n.getTargetUrl());
            item.put("read", n.isRead());
            item.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
            item.put("ago", ago(n.getCreatedAt(), now));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", items);
        body.put("unreadCount", notificationRepo.countByTypeInAndIsReadFalse(types));
        // Booking review is Director-only, so a Guard's bell never counts (or links to) pending requests
        body.put("pendingCount", com.paf.aerovisit.config.SecurityConfig.isDirector()
                ? visitorRepo.countByStatusIn(AdminDashboardController.AWAITING_REVIEW_STATUSES) : 0L);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // Opening the bell: this role's unread notifications become read
    @PostMapping("/api/notifications/mark-read")
    public ResponseEntity<Map<String, Object>> markRead(Authentication auth) {
        int marked = notificationRepo.markAllReadByTypeIn(typesFor(auth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("marked", marked, "unreadCount", 0));
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
