package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paf.aerovisit.service.OtpService.SendOutcome;
import com.paf.aerovisit.service.OtpService.VerifyOutcome;

/** Email OTP that replaced reCAPTCHA: expiry, attempt limits, resend limits, mail failure. No mail server, fake clock. */
class OtpServiceTest {

    private static final String EMAIL = "juan@example.com";
    private static final String IP = "203.0.113.7";

    private EmailService emailService;
    private OtpService otp;
    private Instant now = Instant.parse("2026-10-07T02:00:00Z");

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        when(emailService.sendOtpEmail(anyString(), anyString(), anyLong())).thenReturn(true);
        otp = new OtpService(emailService);
        otp.clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
    }

    private void advance(Duration d) {
        now = now.plus(d);
    }

    private String sendAndReadCode(String email) {
        assertThat(otp.sendCode(email, IP).outcome()).isEqualTo(SendOutcome.SENT);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce()).sendOtpEmail(eq(OtpService.normalize(email)), code.capture(), eq(5L));
        return code.getValue();
    }

    @Test
    void theMailedSixDigitCodeVerifiesTheEmailOnce() {
        String code = sendAndReadCode(EMAIL);
        assertThat(code).matches("[0-9]{6}");
        assertThat(otp.isVerified(EMAIL)).isFalse();

        assertThat(otp.verify(EMAIL, code)).isEqualTo(VerifyOutcome.VERIFIED);
        assertThat(otp.isVerified(EMAIL)).isTrue();

        otp.consume(EMAIL); // the booking was saved
        assertThat(otp.isVerified(EMAIL)).isFalse();
    }

    @Test
    void emailCaseAndSpacesDoNotMatter() {
        String code = sendAndReadCode("  Juan@Example.COM ");
        assertThat(otp.verify("juan@example.com", code)).isEqualTo(VerifyOutcome.VERIFIED);
        assertThat(otp.isVerified("JUAN@example.com")).isTrue();
    }

    @Test
    void aCodeForOneEmailDoesNotVerifyAnother() {
        String code = sendAndReadCode(EMAIL);
        assertThat(otp.verify("someone.else@example.com", code)).isEqualTo(VerifyOutcome.NO_CODE);
        assertThat(otp.isVerified("someone.else@example.com")).isFalse();
    }

    @Test
    void theCodeExpiresAfterFiveMinutes() {
        String code = sendAndReadCode(EMAIL);
        advance(Duration.ofMinutes(5));
        assertThat(otp.verify(EMAIL, code)).isEqualTo(VerifyOutcome.EXPIRED);
        assertThat(otp.isVerified(EMAIL)).isFalse();
    }

    @Test
    void aVerifiedEmailStaysVerifiedForThirtyMinutesToFinishTheForm() {
        String code = sendAndReadCode(EMAIL);
        advance(Duration.ofMinutes(4));
        otp.verify(EMAIL, code);
        advance(Duration.ofMinutes(29));
        assertThat(otp.isVerified(EMAIL)).isTrue();
        advance(Duration.ofMinutes(1));
        assertThat(otp.isVerified(EMAIL)).isFalse();
    }

    @Test
    void fiveWrongGuessesBurnTheCode() {
        String code = sendAndReadCode(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThat(otp.verify(EMAIL, wrong)).isEqualTo(VerifyOutcome.WRONG_CODE);
        }
        assertThat(otp.verify(EMAIL, wrong)).isEqualTo(VerifyOutcome.TOO_MANY_ATTEMPTS);
        assertThat(otp.verify(EMAIL, code)).isEqualTo(VerifyOutcome.NO_CODE); // even the right code is gone now
    }

    @Test
    void blankOrMissingCodeIsWrong() {
        sendAndReadCode(EMAIL);
        assertThat(otp.verify(EMAIL, null)).isEqualTo(VerifyOutcome.WRONG_CODE);
        assertThat(otp.verify(EMAIL, "  ")).isEqualTo(VerifyOutcome.WRONG_CODE);
    }

    @Test
    void resendIsBlockedForSixtySecondsAndReplacesTheOldCode() {
        String first = sendAndReadCode(EMAIL);
        OtpService.SendResult tooSoon = otp.sendCode(EMAIL, IP);
        assertThat(tooSoon.outcome()).isEqualTo(SendOutcome.COOLDOWN);
        assertThat(tooSoon.retryAfterSeconds()).isEqualTo(60);

        advance(Duration.ofSeconds(60));
        String second = sendAndReadCode(EMAIL);
        if (!second.equals(first)) {
            assertThat(otp.verify(EMAIL, first)).isEqualTo(VerifyOutcome.WRONG_CODE);
        }
        assertThat(otp.verify(EMAIL, second)).isEqualTo(VerifyOutcome.VERIFIED);
    }

    @Test
    void oneInboxGetsAtMostFiveCodesPerHour() {
        for (int i = 0; i < OtpService.MAX_SENDS_PER_EMAIL; i++) {
            assertThat(otp.sendCode(EMAIL, "198.51.100." + i).outcome()).isEqualTo(SendOutcome.SENT);
            advance(Duration.ofSeconds(61));
        }
        assertThat(otp.sendCode(EMAIL, "198.51.100.99").outcome()).isEqualTo(SendOutcome.TOO_MANY_REQUESTS);
        advance(Duration.ofHours(1));
        assertThat(otp.sendCode(EMAIL, IP).outcome()).isEqualTo(SendOutcome.SENT);
    }

    @Test
    void oneClientCannotSprayCodesAtManyInboxes() {
        for (int i = 0; i < OtpService.MAX_SENDS_PER_CLIENT; i++) {
            assertThat(otp.sendCode("victim" + i + "@example.com", IP).outcome()).isEqualTo(SendOutcome.SENT);
        }
        assertThat(otp.sendCode("victim99@example.com", IP).outcome()).isEqualTo(SendOutcome.TOO_MANY_REQUESTS);
        assertThat(otp.sendCode("victim99@example.com", "192.0.2.1").outcome()).isEqualTo(SendOutcome.SENT);
    }

    @Test
    void invalidEmailsAreRefusedWithoutSendingMail() {
        for (String bad : new String[] {null, "", "not-an-email", "a@b", "two@@example.com", "x y@example.com",
                "a".repeat(250) + "@example.com"}) {
            assertThat(otp.sendCode(bad, IP).outcome()).as(String.valueOf(bad)).isEqualTo(SendOutcome.INVALID_EMAIL);
        }
        verify(emailService, never()).sendOtpEmail(anyString(), anyString(), anyLong());
    }

    @Test
    void whenGmailFailsNoCodeIsKeptAndTheVisitorMayRetryAtOnce() {
        when(emailService.sendOtpEmail(anyString(), anyString(), anyLong())).thenReturn(false);
        assertThat(otp.sendCode(EMAIL, IP).outcome()).isEqualTo(SendOutcome.MAIL_FAILED);
        assertThat(otp.verify(EMAIL, "123456")).isEqualTo(VerifyOutcome.NO_CODE);

        when(emailService.sendOtpEmail(anyString(), anyString(), anyLong())).thenReturn(true);
        assertThat(otp.sendCode(EMAIL, IP).outcome()).isEqualTo(SendOutcome.SENT); // no 60 s cooldown after a failure
    }

    // --- Staff security codes (password changes): same rules, separate from booking codes ---

    private static final String INBOX = "PAFMuseumOfficial@gmail.com";

    private String sendStaffAndReadCode() {
        when(emailService.sendStaffOtpEmail(anyString(), anyString(), anyLong(), anyString())).thenReturn(true);
        assertThat(otp.sendStaffCode(INBOX, IP, "change the Guard account's password").outcome()).isEqualTo(SendOutcome.SENT);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce()).sendStaffOtpEmail(eq("pafmuseumofficial@gmail.com"), code.capture(),
                eq(5L), eq("change the Guard account's password"));
        return code.getValue();
    }

    @Test
    void staffCodesUseTheStaffEmailAndConfirmOnce() {
        String code = sendStaffAndReadCode();
        verify(emailService, never()).sendOtpEmail(anyString(), anyString(), anyLong()); // never the booking wording
        assertThat(otp.verifyStaff(INBOX, "000000".equals(code) ? "111111" : "000000")).isEqualTo(VerifyOutcome.WRONG_CODE);
        assertThat(otp.verifyStaff(INBOX, code)).isEqualTo(VerifyOutcome.VERIFIED);
        otp.consumeStaff(INBOX);
        assertThat(otp.verifyStaff(INBOX, code)).isEqualTo(VerifyOutcome.NO_CODE);
    }

    @Test
    void staffAndBookingCodesNeverMix() {
        String staffCode = sendStaffAndReadCode();
        // A staff code is not a booking verification of the same inbox, and the booking checks cannot use it
        assertThat(otp.verify(INBOX, staffCode)).isEqualTo(VerifyOutcome.NO_CODE);
        assertThat(otp.verifyStaff(INBOX, staffCode)).isEqualTo(VerifyOutcome.VERIFIED);
        assertThat(otp.isVerified(INBOX)).isFalse();

        // A booking code for the same inbox does not replace or confirm the staff code
        advance(Duration.ofSeconds(61));
        String bookingCode = sendAndReadCode(INBOX);
        assertThat(otp.verifyStaff(INBOX, staffCode)).isEqualTo(VerifyOutcome.VERIFIED); // still the staff code
        assertThat(otp.verify(INBOX, bookingCode)).isEqualTo(VerifyOutcome.VERIFIED);
    }

    @Test
    void staffCodesExpireAndKeepTheResendCooldown() {
        sendStaffAndReadCode();
        assertThat(otp.sendStaffCode(INBOX, IP, "change the Director's password").outcome()).isEqualTo(SendOutcome.COOLDOWN);
        advance(Duration.ofMinutes(6));
        assertThat(otp.verifyStaff(INBOX, "123456")).isEqualTo(VerifyOutcome.EXPIRED);
    }

    @Test
    void aStaffCodeThatCouldNotBeMailedIsNotKept() {
        when(emailService.sendStaffOtpEmail(anyString(), anyString(), anyLong(), anyString())).thenReturn(false);
        assertThat(otp.sendStaffCode(INBOX, IP, "change the Director's password").outcome()).isEqualTo(SendOutcome.MAIL_FAILED);
        assertThat(otp.verifyStaff(INBOX, "123456")).isEqualTo(VerifyOutcome.NO_CODE);
    }

    // --- Forgot Password reset codes (public sign-in page): a third key, separate from booking and staff codes ---

    private String sendResetAndReadCode() {
        when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyLong())).thenReturn(true);
        assertThat(otp.sendResetCode("  PAFMuseumOfficial@Gmail.com ", IP).outcome()).isEqualTo(SendOutcome.SENT);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, org.mockito.Mockito.atLeastOnce()).sendPasswordResetEmail(eq("pafmuseumofficial@gmail.com"),
                code.capture(), eq(5L));
        return code.getValue();
    }

    @Test
    void resetCodesUseTheResetEmailAndWorkOnce() {
        String code = sendResetAndReadCode();
        verify(emailService, never()).sendOtpEmail(anyString(), anyString(), anyLong());
        verify(emailService, never()).sendStaffOtpEmail(anyString(), anyString(), anyLong(), anyString());
        assertThat(otp.verifyReset(INBOX, "000000".equals(code) ? "111111" : "000000")).isEqualTo(VerifyOutcome.WRONG_CODE);
        assertThat(otp.verifyReset(INBOX, code)).isEqualTo(VerifyOutcome.VERIFIED);
        otp.consumeReset(INBOX);
        assertThat(otp.verifyReset(INBOX, code)).isEqualTo(VerifyOutcome.NO_CODE);
    }

    @Test
    void resetCodesNeverMixWithBookingOrStaffCodes() {
        String resetCode = sendResetAndReadCode();
        assertThat(otp.verify(INBOX, resetCode)).isEqualTo(VerifyOutcome.NO_CODE);       // not a booking code
        assertThat(otp.verifyStaff(INBOX, resetCode)).isEqualTo(VerifyOutcome.NO_CODE);  // not a signed-in staff code
        assertThat(otp.isVerified(INBOX)).isFalse();

        // Booking and staff codes for the same inbox neither replace nor confirm the reset code, and its use leaves them alone
        String staffCode = sendStaffAndReadCode();
        String bookingCode = sendAndReadCode(INBOX);
        assertThat(otp.verifyReset(INBOX, staffCode.equals(resetCode) ? "x" : staffCode)).isEqualTo(VerifyOutcome.WRONG_CODE);
        assertThat(otp.verifyReset(INBOX, resetCode)).isEqualTo(VerifyOutcome.VERIFIED);
        otp.consumeReset(INBOX);
        assertThat(otp.verifyStaff(INBOX, staffCode)).isEqualTo(VerifyOutcome.VERIFIED);
        assertThat(otp.verify(INBOX, bookingCode)).isEqualTo(VerifyOutcome.VERIFIED);
    }

    @Test
    void resetCodesKeepTheCooldownTheLimitsAndTheExpiry() {
        sendResetAndReadCode();
        assertThat(otp.sendResetCode(INBOX, IP).outcome()).isEqualTo(SendOutcome.COOLDOWN);
        for (int i = 1; i < 5; i++) {                 // 5 codes per inbox per hour
            advance(Duration.ofSeconds(61));
            assertThat(otp.sendResetCode(INBOX, IP).outcome()).isEqualTo(SendOutcome.SENT);
        }
        advance(Duration.ofSeconds(61));
        assertThat(otp.sendResetCode(INBOX, IP).outcome()).isEqualTo(SendOutcome.TOO_MANY_REQUESTS);
        advance(Duration.ofMinutes(5));
        assertThat(otp.verifyReset(INBOX, "123456")).isEqualTo(VerifyOutcome.EXPIRED);
    }

    @Test
    void fiveWrongResetGuessesBurnTheCode() {
        String code = sendResetAndReadCode();
        String wrong = "000000".equals(code) ? "111111" : "000000";
        for (int i = 1; i < 5; i++) assertThat(otp.verifyReset(INBOX, wrong)).isEqualTo(VerifyOutcome.WRONG_CODE);
        assertThat(otp.verifyReset(INBOX, wrong)).isEqualTo(VerifyOutcome.TOO_MANY_ATTEMPTS);
        assertThat(otp.verifyReset(INBOX, code)).isEqualTo(VerifyOutcome.NO_CODE);
    }

    @Test
    void aResetCodeThatCouldNotBeMailedIsNotKept() {
        when(emailService.sendPasswordResetEmail(anyString(), anyString(), anyLong())).thenReturn(false);
        assertThat(otp.sendResetCode(INBOX, IP).outcome()).isEqualTo(SendOutcome.MAIL_FAILED);
        assertThat(otp.verifyReset(INBOX, "123456")).isEqualTo(VerifyOutcome.NO_CODE);
    }
}
