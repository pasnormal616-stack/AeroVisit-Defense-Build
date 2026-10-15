package com.paf.aerovisit.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;

import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;
import com.paf.aerovisit.security.ClientIp;
import com.paf.aerovisit.service.OtpService;

import jakarta.servlet.http.HttpServletRequest;

// 🔑 FORGOT PASSWORD (public, signed out): the Director sets a new password with a code mailed to the Director's
// account email. Three steps: GET /forgot-password (the page), POST /forgot-password/request (mail a code),
// POST /forgot-password/reset (code + new password -> BCrypt hash saved).
// 🕵️ Zero enumeration: /request gives the same answer, in the same time, whatever was typed. Only an account with
// role DIRECTOR whose username is a valid email address ever gets a code (never the Guard, even one renamed to an
// address), and the email goes out on a background thread so a real match can't be told apart by a slower reply.
// /reset gives one message for every failure (wrong code, expired code, unknown account).
// Limits: RateLimitFilter "reset-request" (3 per 15 min per IP) and "reset-confirm" (10 per 15 min per IP), plus
// OtpService's own (60 s between codes, 5 per hour, 5 wrong tries, 5 minutes).
@Controller
public class ForgotPasswordController {

    static final String REQUEST_REPLY = "If an account exists for that email address, a reset code has been sent to it. "
            + "The code is valid for 5 minutes.";
    static final String CODE_FAILED = "That code is not correct or has expired. Check the email, or request a new code.";

    private final StaffUserRepository staffRepo;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final Executor resetMail;

    @Autowired
    public ForgotPasswordController(StaffUserRepository staffRepo, PasswordEncoder passwordEncoder, OtpService otpService) {
        this(staffRepo, passwordEncoder, otpService, resetMailPool());
    }

    ForgotPasswordController(StaffUserRepository staffRepo, PasswordEncoder passwordEncoder, OtpService otpService, Executor resetMail) {
        this.staffRepo = staffRepo;
        this.passwordEncoder = passwordEncoder;
        this.otpService = otpService;
        this.resetMail = resetMail;
    }

    // One small daemon thread ("reset-mail") with a short queue: requests are already rate-limited, and a burst beyond
    // the queue is dropped instead of piling up threads (the reply is the same either way)
    private static Executor resetMailPool() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20), task -> {
            Thread t = new Thread(task, "reset-mail");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
    }

    // The page (forgot-password.html): a two-step form that talks to the two POSTs below with fetch
    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "forgot-password";
    }

    // Step 1: mail a reset code if, and only if, the address belongs to the Director. Same reply in every case.
    @PostMapping("/forgot-password/request")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> requestCode(@RequestParam(value = "email", required = false) String email,
                                                           HttpServletRequest request) {
        if (email == null || email.isBlank()) return reply(HttpStatus.BAD_REQUEST, false, "Enter your email address.");
        Optional<StaffUser> director = directorFor(email);
        if (director.isPresent()) {
            String inbox = director.get().getUsername();
            String clientKey = ClientIp.of(request);
            resetMail.execute(() -> {
                try {
                    otpService.sendResetCode(inbox, clientKey); // a failed or refused send is logged by the services
                } catch (RuntimeException e) {
                    System.err.println("❌ Password reset code not sent: " + e.getMessage());
                }
            });
        }
        return reply(HttpStatus.OK, true, REQUEST_REPLY);
    }

    // Step 2: the code from the email and the new password. The password is checked first, so a typo in it never
    // uses up one of the code's 5 tries.
    @PostMapping("/forgot-password/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestParam(value = "email", required = false) String email,
                                                             @RequestParam(value = "code", required = false) String code,
                                                             @RequestParam(value = "newPassword", required = false) String newPassword,
                                                             @RequestParam(value = "confirmPassword", required = false) String confirmPassword) {
        if (email == null || email.isBlank() || code == null || code.isBlank()) {
            return reply(HttpStatus.BAD_REQUEST, false, "Enter your email address and the code from the email.");
        }
        String problem = StaffManagementController.passwordProblem(newPassword, confirmPassword);
        if (problem != null) return reply(HttpStatus.BAD_REQUEST, false, problem);

        Optional<StaffUser> director = directorFor(email);
        if (director.isEmpty()) return reply(HttpStatus.BAD_REQUEST, false, CODE_FAILED); // no account: same as a wrong code
        String inbox = director.get().getUsername();
        if (otpService.verifyReset(inbox, code) != OtpService.VerifyOutcome.VERIFIED) {
            return reply(HttpStatus.BAD_REQUEST, false, CODE_FAILED);
        }
        otpService.consumeReset(inbox); // one code = one new password
        director.get().setPassword(passwordEncoder.encode(newPassword));
        staffRepo.save(director.get());
        System.out.println("🔑 Director password reset (Forgot Password code confirmed)");
        return reply(HttpStatus.OK, true, "Your password has been reset. You can now sign in with the new password.");
    }

    // The Director account for this address: role DIRECTOR and a username that is a real email address, else empty
    private Optional<StaffUser> directorFor(String email) {
        String typed = email.trim();
        if (typed.length() > 254) return Optional.empty(); // longer than any email address
        return staffRepo.findByUsernameIgnoreCase(typed)
                .filter(staff -> StaffUser.DIRECTOR.equals(staff.getRole()))
                .filter(staff -> OtpService.isValidEmail(staff.getUsername().trim().toLowerCase(java.util.Locale.ROOT)));
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, boolean ok, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
