package com.paf.aerovisit.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paf.aerovisit.security.ClientIp;
import com.paf.aerovisit.service.OtpService;

import jakarta.servlet.http.HttpServletRequest;

// 📧 Booking-form email verification (register.html step 02). Public: permitted in SecurityConfig.
@RestController
@RequestMapping("/otp")
public class OtpController {

    private final OtpService otpService;

    public OtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestParam(name = "email", required = false) String email,
                                                    HttpServletRequest request) {
        OtpService.SendResult result = otpService.sendCode(email, clientKey(request));
        return switch (result.outcome()) {
            case SENT -> reply(HttpStatus.OK, true, "We sent a 6-digit code to your email. It expires in 5 minutes. Not in your inbox? Check your Spam or Junk folder.",
                    result.retryAfterSeconds());
            case INVALID_EMAIL -> reply(HttpStatus.BAD_REQUEST, false, "Please enter a valid email address first.", 0);
            case COOLDOWN -> reply(HttpStatus.TOO_MANY_REQUESTS, false,
                    "A code was just sent. You can request a new one in " + result.retryAfterSeconds() + " seconds.",
                    result.retryAfterSeconds());
            case TOO_MANY_REQUESTS -> reply(HttpStatus.TOO_MANY_REQUESTS, false,
                    "Too many codes requested. Please try again later.", result.retryAfterSeconds());
            case MAIL_FAILED -> reply(HttpStatus.SERVICE_UNAVAILABLE, false,
                    "We couldn't send the email right now. Please check the address and try again in a moment.", 0);
        };
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam(name = "email", required = false) String email,
                                                      @RequestParam(name = "code", required = false) String code) {
        return switch (otpService.verify(email, code)) {
            case VERIFIED -> reply(HttpStatus.OK, true, "Email verified.", 0);
            case WRONG_CODE -> reply(HttpStatus.BAD_REQUEST, false, "That code is not correct. Please check the email and try again.", 0);
            case EXPIRED -> reply(HttpStatus.BAD_REQUEST, false, "That code has expired. Please request a new one.", 0);
            case NO_CODE -> reply(HttpStatus.BAD_REQUEST, false, "Please request a code for this email address first.", 0);
            case TOO_MANY_ATTEMPTS -> reply(HttpStatus.BAD_REQUEST, false, "Too many wrong attempts. Please request a new code.", 0);
        };
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, boolean ok, String message, long retryAfter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", ok);
        body.put("message", message);
        body.put("retryAfter", retryAfter);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        return builder.body(body);
    }

    // The shared client-IP rule (ClientIp): behind a proxy, the LAST X-Forwarded-For hop, never a browser-supplied one
    static String clientKey(HttpServletRequest request) {
        return ClientIp.of(request);
    }
}
