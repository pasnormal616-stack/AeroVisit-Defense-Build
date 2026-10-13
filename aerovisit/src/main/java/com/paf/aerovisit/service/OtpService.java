package com.paf.aerovisit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

// 📧 EMAIL OTP (replaces reCAPTCHA): a 6-digit code mailed to the booking email, valid for 5 minutes.
// In memory only (one server, codes live minutes): no new table, and a restart just means "request a new code".
// Flow: sendCode -> verify -> /save checks isVerified(email) -> consume(email) once the booking is saved.
@Service
public class OtpService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration VERIFIED_TTL = Duration.ofMinutes(30);   // time to finish the form after verifying
    static final int MAX_ATTEMPTS = 5;                              // wrong guesses before the code is burned
    static final int MAX_SENDS_PER_EMAIL = 5;                       // per rolling hour: no inbox bombing
    static final int MAX_SENDS_PER_CLIENT = 30;                     // per rolling hour: one IP can't spray addresses
                                                                    // (30, not 10: a school or venue Wi-Fi shares one IP)
    static final Duration SEND_WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED = 10_000;                  // past this, stale entries are swept

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public enum SendOutcome { SENT, INVALID_EMAIL, COOLDOWN, TOO_MANY_REQUESTS, MAIL_FAILED }
    public enum VerifyOutcome { VERIFIED, WRONG_CODE, EXPIRED, NO_CODE, TOO_MANY_ATTEMPTS }
    public record SendResult(SendOutcome outcome, long retryAfterSeconds) {}

    private static final class Challenge {
        final String code;
        final Instant sentAt;
        final Instant expiresAt;
        int attempts;
        Instant verifiedAt; // null until the right code is entered

        Challenge(String code, Instant sentAt) {
            this.code = code;
            this.sentAt = sentAt;
            this.expiresAt = sentAt.plus(CODE_TTL);
        }
    }

    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> sendsByEmail = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> sendsByClient = new ConcurrentHashMap<>();
    Clock clock = Clock.systemUTC(); // tests use a movable clock

    public OtpService(EmailService emailService) {
        this.emailService = emailService;
    }

    // Lower-cased and trimmed, so "Juan@Mail.com " and "juan@mail.com" are the same inbox
    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    static boolean isValidEmail(String email) {
        return email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    public SendResult sendCode(String rawEmail, String clientKey) {
        String email = normalize(rawEmail);
        if (!isValidEmail(email)) return new SendResult(SendOutcome.INVALID_EMAIL, 0);
        Instant now = clock.instant();
        sweepIfLarge(now);

        Challenge previous = challenges.get(email);
        if (previous != null && now.isBefore(previous.sentAt.plus(RESEND_COOLDOWN))) {
            return new SendResult(SendOutcome.COOLDOWN, secondsUntil(now, previous.sentAt.plus(RESEND_COOLDOWN)));
        }
        long emailWait = waitForSlot(sendsByEmail, email, MAX_SENDS_PER_EMAIL, now);
        long clientWait = waitForSlot(sendsByClient, clientKey == null ? "unknown" : clientKey, MAX_SENDS_PER_CLIENT, now);
        if (emailWait > 0 || clientWait > 0) {
            return new SendResult(SendOutcome.TOO_MANY_REQUESTS, Math.max(emailWait, clientWait));
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        Challenge challenge = new Challenge(code, now);
        challenges.put(email, challenge); // a new code replaces the old one (and any earlier verification)
        record(sendsByEmail, email, now);
        record(sendsByClient, clientKey == null ? "unknown" : clientKey, now);

        if (!emailService.sendOtpEmail(email, code, CODE_TTL.toMinutes())) {
            challenges.remove(email, challenge); // nothing reached the inbox: don't keep a code nobody has
            return new SendResult(SendOutcome.MAIL_FAILED, 0);
        }
        return new SendResult(SendOutcome.SENT, RESEND_COOLDOWN.toSeconds());
    }

    public VerifyOutcome verify(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        Challenge challenge = challenges.get(email);
        if (challenge == null) return VerifyOutcome.NO_CODE;
        synchronized (challenge) {
            Instant now = clock.instant();
            if (challenge.verifiedAt != null) return VerifyOutcome.VERIFIED; // double-click on Verify
            if (!now.isBefore(challenge.expiresAt)) {
                challenges.remove(email, challenge);
                return VerifyOutcome.EXPIRED;
            }
            // Constant-time compare, so response timing can't leak how many digits were right
            if (MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), challenge.code.getBytes(StandardCharsets.UTF_8))) {
                challenge.verifiedAt = now;
                return VerifyOutcome.VERIFIED;
            }
            if (++challenge.attempts >= MAX_ATTEMPTS) {
                challenges.remove(email, challenge);
                return VerifyOutcome.TOO_MANY_ATTEMPTS;
            }
            return VerifyOutcome.WRONG_CODE;
        }
    }

    // True when this email entered its code within the last VERIFIED_TTL and hasn't booked with it yet
    public boolean isVerified(String rawEmail) {
        Challenge challenge = challenges.get(normalize(rawEmail));
        if (challenge == null) return false;
        synchronized (challenge) {
            return challenge.verifiedAt != null && clock.instant().isBefore(challenge.verifiedAt.plus(VERIFIED_TTL));
        }
    }

    // One verification = one booking
    public void consume(String rawEmail) {
        challenges.remove(normalize(rawEmail));
    }

    // Seconds until another send is allowed for this key, 0 if one is allowed now
    private static long waitForSlot(Map<String, Deque<Instant>> sends, String key, int limit, Instant now) {
        Deque<Instant> times = sends.get(key);
        if (times == null) return 0;
        synchronized (times) {
            while (!times.isEmpty() && !now.isBefore(times.peekFirst().plus(SEND_WINDOW))) times.pollFirst();
            return times.size() < limit ? 0 : secondsUntil(now, times.peekFirst().plus(SEND_WINDOW));
        }
    }

    private static void record(Map<String, Deque<Instant>> sends, String key, Instant now) {
        Deque<Instant> times = sends.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
        }
    }

    private static long secondsUntil(Instant now, Instant then) {
        return Math.max(1, (long) Math.ceil(Duration.between(now, then).toMillis() / 1000.0));
    }

    private void sweepIfLarge(Instant now) {
        if (challenges.size() > MAX_TRACKED) {
            challenges.values().removeIf(c -> !now.isBefore(c.expiresAt) && (c.verifiedAt == null
                    || !now.isBefore(c.verifiedAt.plus(VERIFIED_TTL))));
        }
        for (Map<String, Deque<Instant>> sends : java.util.List.of(sendsByEmail, sendsByClient)) {
            if (sends.size() > MAX_TRACKED) {
                sends.values().removeIf(times -> {
                    synchronized (times) {
                        return times.isEmpty() || !now.isBefore(times.peekLast().plus(SEND_WINDOW));
                    }
                });
            }
        }
    }
}
