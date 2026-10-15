package com.paf.aerovisit.controller;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;
import com.paf.aerovisit.security.ClientIp;
import com.paf.aerovisit.service.OtpService;

import jakarta.servlet.http.HttpServletRequest;

// 👤 STAFF ACCOUNT MANAGEMENT (Director only; Settings page "Staff Accounts" panel).
// The Director can change their own password, and the Guard account's username and/or password. Every change is two
// steps: /request checks the form and mails a security code to the museum inbox (PAFMuseumOfficial@gmail.com), then
// /confirm saves the change ONLY after that code is entered. Until then the change waits here in memory, holding the
// new password as a BCrypt hash, never as plain text. One change waits at a time: a newer request replaces it (and its code).
// Limits: SecurityConfig (Director only), RateLimitFilter rule "password-form", and OtpService's own code limits.
@RestController
public class StaffManagementController {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_BYTES = 72; // BCrypt reads at most 72 bytes; a longer password is refused, not cut
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@-]{3,120}");

    // A change waiting for its security code
    record PendingChange(String target, String requestedBy, String newUsername, String newPasswordHash, String summary) {}

    private final StaffUserRepository staffRepo;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final String securityInbox;
    private final AtomicReference<PendingChange> pending = new AtomicReference<>();

    public StaffManagementController(StaffUserRepository staffRepo, PasswordEncoder passwordEncoder, OtpService otpService,
                                     @Value("${aerovisit.mail.from:PAFMuseumOfficial@gmail.com}") String securityInbox) {
        this.staffRepo = staffRepo;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
        this.securityInbox = securityInbox;
    }

    // Usernames shown in the panel
    @GetMapping("/admin/api/staff-accounts")
    public ResponseEntity<Map<String, Object>> accounts() {
        if (!SecurityConfig.isDirector()) return reply(HttpStatus.FORBIDDEN, false, "Only the Director can manage staff accounts.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("guardUsername", staffRepo.findFirstByRoleOrderByIdAsc(StaffUser.GUARD).map(StaffUser::getUsername).orElse(null));
        body.put("securityInbox", securityInbox);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    // Step 1: check the requested change, then mail the security code. target = "self" (the Director's own password,
    // current password required) or "guard" (new username and/or new password).
    @PostMapping("/admin/api/staff-accounts/request")
    public ResponseEntity<Map<String, Object>> requestChange(@RequestParam(value = "target", required = false) String target,
                                                             @RequestParam(value = "currentPassword", required = false) String currentPassword,
                                                             @RequestParam(value = "newUsername", required = false) String newUsername,
                                                             @RequestParam(value = "newPassword", required = false) String newPassword,
                                                             @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
                                                             Authentication auth, HttpServletRequest request) {
        if (!SecurityConfig.isDirector()) return reply(HttpStatus.FORBIDDEN, false, "Only the Director can manage staff accounts.");
        Optional<StaffUser> director = staffRepo.findByUsernameIgnoreCase(auth.getName());
        if (director.isEmpty()) return reply(HttpStatus.CONFLICT, false, "Your staff account was not found. Sign in again.");

        PendingChange change;
        if ("self".equals(target)) {
            if (isBlank(currentPassword) || !passwordEncoder.matches(currentPassword, director.get().getPassword())) {
                return reply(HttpStatus.BAD_REQUEST, false, "Your current password is incorrect.");
            }
            String problem = passwordProblem(newPassword, confirmPassword);
            if (problem != null) return reply(HttpStatus.BAD_REQUEST, false, problem);
            change = new PendingChange("self", auth.getName(), null, passwordEncoder.encode(newPassword),
                    "change the Director's password");
        } else if ("guard".equals(target)) {
            Optional<StaffUser> guard = staffRepo.findFirstByRoleOrderByIdAsc(StaffUser.GUARD);
            if (guard.isEmpty()) return reply(HttpStatus.CONFLICT, false, "There is no Guard account to change.");
            String username = isBlank(newUsername) ? null : newUsername.trim();
            if (username != null && username.equals(guard.get().getUsername())) username = null; // unchanged
            boolean passwordGiven = !isBlank(newPassword) || !isBlank(confirmPassword);
            if (username == null && !passwordGiven) {
                return reply(HttpStatus.BAD_REQUEST, false, "Enter a new username, a new password, or both.");
            }
            if (username != null) {
                String problem = usernameProblem(username, guard.get().getId());
                if (problem != null) return reply(HttpStatus.BAD_REQUEST, false, problem);
            }
            String hash = null;
            if (passwordGiven) {
                String problem = passwordProblem(newPassword, confirmPassword);
                if (problem != null) return reply(HttpStatus.BAD_REQUEST, false, problem);
                hash = passwordEncoder.encode(newPassword);
            }
            String summary = username != null && hash != null ? "change the Guard account's username to \"" + username + "\" and its password"
                           : username != null ? "change the Guard account's username to \"" + username + "\""
                           : "change the Guard account's password";
            change = new PendingChange("guard", auth.getName(), username, hash, summary);
        } else {
            return reply(HttpStatus.BAD_REQUEST, false, "Choose which account to change.");
        }

        OtpService.SendResult sent = otpService.sendStaffCode(securityInbox, ClientIp.of(request), change.summary());
        switch (sent.outcome()) {
            case SENT -> {
                pending.set(change); // the code just mailed belongs to this change
                Map<String, Object> body = body(true, "A security code was sent to " + securityInbox
                        + ". Enter it below to confirm. Nothing has been changed yet.");
                body.put("retryAfter", sent.retryAfterSeconds());
                return ResponseEntity.ok(body);
            }
            case COOLDOWN, TOO_MANY_REQUESTS -> {
                Map<String, Object> body = body(false, "A security code was sent moments ago. Please wait "
                        + sent.retryAfterSeconds() + " seconds before asking for a new one.");
                body.put("retryAfter", sent.retryAfterSeconds());
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(sent.retryAfterSeconds())).body(body);
            }
            default -> {
                return reply(HttpStatus.SERVICE_UNAVAILABLE, false,
                        "The security code could not be emailed right now, so nothing was changed. Please try again later.");
            }
        }
    }

    // Step 2: the code from the museum inbox. Only now is the waiting change saved.
    @PostMapping("/admin/api/staff-accounts/confirm")
    public ResponseEntity<Map<String, Object>> confirmChange(@RequestParam(value = "code", required = false) String code,
                                                             Authentication auth) {
        if (!SecurityConfig.isDirector()) return reply(HttpStatus.FORBIDDEN, false, "Only the Director can manage staff accounts.");
        PendingChange change = pending.get();
        if (change == null || !change.requestedBy().equalsIgnoreCase(auth.getName())) {
            return reply(HttpStatus.CONFLICT, false, "No change is waiting for a code. Please start again.");
        }
        switch (otpService.verifyStaff(securityInbox, code)) {
            case VERIFIED -> { /* saved below */ }
            case WRONG_CODE -> { return reply(HttpStatus.BAD_REQUEST, false, "That code is not correct. Please check the email and try again."); }
            default -> { // expired, used up, or too many wrong tries: this change is dropped
                pending.compareAndSet(change, null);
                return reply(HttpStatus.BAD_REQUEST, false, "That code has expired or can no longer be used. Nothing was changed; please start again.");
            }
        }
        if (!pending.compareAndSet(change, null)) { // a double click, or a newer request replaced it
            return reply(HttpStatus.CONFLICT, false, "This change was already handled or replaced by a newer request.");
        }
        otpService.consumeStaff(securityInbox);

        if ("self".equals(change.target())) {
            Optional<StaffUser> director = staffRepo.findByUsernameIgnoreCase(change.requestedBy());
            if (director.isEmpty()) return reply(HttpStatus.CONFLICT, false, "Your staff account was not found. Nothing was changed.");
            director.get().setPassword(change.newPasswordHash());
            staffRepo.save(director.get());
            System.out.println("🔐 Director password changed (security code confirmed)");
            return reply(HttpStatus.OK, true, "Your password has been changed. Use the new password the next time you sign in.");
        }

        Optional<StaffUser> guard = staffRepo.findFirstByRoleOrderByIdAsc(StaffUser.GUARD);
        if (guard.isEmpty()) return reply(HttpStatus.CONFLICT, false, "There is no Guard account to change. Nothing was changed.");
        if (change.newUsername() != null) {
            String problem = usernameProblem(change.newUsername(), guard.get().getId()); // re-checked: taken meanwhile?
            if (problem != null) return reply(HttpStatus.CONFLICT, false, problem + " Nothing was changed.");
            guard.get().setUsername(change.newUsername());
        }
        if (change.newPasswordHash() != null) guard.get().setPassword(change.newPasswordHash());
        staffRepo.save(guard.get());
        System.out.println("🔐 Guard account updated (security code confirmed)");
        Map<String, Object> body = body(true, "The Guard account has been updated.");
        body.put("guardUsername", guard.get().getUsername());
        return ResponseEntity.ok(body);
    }

    static String passwordProblem(String newPassword, String confirmPassword) {
        if (isBlank(newPassword) || isBlank(confirmPassword)) return "Enter the new password twice.";
        if (!newPassword.equals(confirmPassword)) return "The new password and its confirmation do not match.";
        if (newPassword.length() < MIN_PASSWORD_LENGTH) return "The new password must be at least " + MIN_PASSWORD_LENGTH + " characters long.";
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) return "The new password is too long (72 characters at most).";
        return null;
    }

    private String usernameProblem(String username, Long guardId) {
        if (!USERNAME.matcher(username).matches()) {
            return "The username must be 3 to 120 characters: letters, numbers, dots, dashes, underscores or @.";
        }
        Optional<StaffUser> holder = staffRepo.findByUsernameIgnoreCase(username);
        if (holder.isPresent() && !holder.get().getId().equals(guardId)) return "That username is already used by another staff account.";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> body(boolean ok, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("message", message);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, boolean ok, String message) {
        return ResponseEntity.status(status).body(body(ok, message));
    }
}
