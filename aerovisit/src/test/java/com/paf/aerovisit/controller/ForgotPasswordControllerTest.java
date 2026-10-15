package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.OtpService.SendOutcome;
import com.paf.aerovisit.service.OtpService.SendResult;
import com.paf.aerovisit.service.OtpService.VerifyOutcome;

/**
 * Public Forgot Password flow, signed out: one reply for every address, codes only ever for the DIRECTOR account,
 * the email on a background thread, and a new BCrypt hash saved only after the code. No database: mocked repository
 * and OtpService, real SecurityConfig (CSRF, public paths) and BCrypt.
 */
@WebMvcTest(ForgotPasswordController.class)
@Import(SecurityConfig.class)
class ForgotPasswordControllerTest {

    private static final String DIRECTOR = "PAFMuseumOfficial@gmail.com";

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder encoder;

    @MockitoBean private StaffUserRepository staffRepo;
    @MockitoBean private OtpService otpService;

    private StaffUser director;

    @BeforeEach
    void accounts() {
        director = new StaffUser(DIRECTOR, encoder.encode("old-director-1"), StaffUser.DIRECTOR);
        director.setId(1L);
        StaffUser guard = new StaffUser("guard", encoder.encode("old-guard-1"), StaffUser.GUARD);
        guard.setId(2L);
        // A Guard renamed to an email address must still never receive a reset code
        StaffUser emailGuard = new StaffUser("gate.guard@example.com", encoder.encode("old-guard-2"), StaffUser.GUARD);
        emailGuard.setId(3L);
        when(staffRepo.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(staffRepo.findByUsernameIgnoreCase(DIRECTOR)).thenReturn(Optional.of(director));
        when(staffRepo.findByUsernameIgnoreCase("pafmuseumofficial@GMAIL.com")).thenReturn(Optional.of(director));
        when(staffRepo.findByUsernameIgnoreCase("guard")).thenReturn(Optional.of(guard));
        when(staffRepo.findByUsernameIgnoreCase("gate.guard@example.com")).thenReturn(Optional.of(emailGuard));
        when(otpService.sendResetCode(anyString(), anyString())).thenReturn(new SendResult(SendOutcome.SENT, 60));
    }

    private static MockHttpServletRequestBuilder requestCode(String email) {
        return post("/forgot-password/request").with(csrf()).param("email", email);
    }

    private static MockHttpServletRequestBuilder reset(String email, String code, String password, String confirm) {
        return post("/forgot-password/reset").with(csrf())
                .param("email", email).param("code", code).param("newPassword", password).param("confirmPassword", confirm);
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // --- The page ---

    @Test
    void thePageOpensSignedOutWithBothStepsAndTheCsrfToken() throws Exception {
        String html = mvc.perform(get("/forgot-password"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.view().name("forgot-password"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html)
            .contains("<title>AeroVisit - Forgot Password</title>")
            .contains("<meta name=\"_csrf\"", "<meta name=\"_csrf_header\"", "/js/csrf.js")
            .containsSubsequence("<div class=\"col-12 col-md-8 col-lg-6 col-xl-5 mx-auto p-3\">",
                "<section class=\"av-auth-panel mx-auto\">", "<h2>Forgot Password</h2>",
                "id=\"requestForm\"", "name=\"email\"", "Send Reset Code",
                "id=\"resetForm\" class=\"d-none\"", "name=\"code\"", "name=\"newPassword\"", "name=\"confirmPassword\"",
                "Reset Password", "id=\"backToLogin\"")
            .contains("'/forgot-password/request'", "'/forgot-password/reset'", "AeroCsrf.headers(")
            // the same spinner loading state as the Settings page
            .contains("spinner-border spinner-border-sm me-2", "busy(button, 'Sending...')", "busy(button, 'Verifying...')")
            .contains("href=\"/login\"");
        verify(otpService, never()).sendResetCode(anyString(), anyString());
    }

    // --- Step 1: request a code (zero enumeration) ---

    @Test
    void theDirectorGetsACodeAtTheAccountAddressSignedOut() throws Exception {
        mvc.perform(requestCode(DIRECTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value(ForgotPasswordController.REQUEST_REPLY));
        verify(otpService, timeout(2000)).sendResetCode(eq(DIRECTOR), anyString());
    }

    @Test
    void theTypedCaseAndSpacesDoNotMatterButTheCodeGoesToTheStoredAddress() throws Exception {
        body(requestCode("  pafmuseumofficial@GMAIL.com  "));
        verify(otpService, timeout(2000)).sendResetCode(eq(DIRECTOR), anyString());
    }

    @Test
    void everyAddressGetsTheSameReplyAndOnlyTheDirectorIsEverEmailed() throws Exception {
        String directorReply = body(requestCode(DIRECTOR));
        for (String other : new String[] {"nobody@example.com", "guard", "gate.guard@example.com", "not an email", "x".repeat(300)}) {
            assertThat(body(requestCode(other))).as(other).isEqualTo(directorReply);
        }
        verify(otpService, timeout(2000)).sendResetCode(eq(DIRECTOR), anyString());
        verify(otpService, after(500).never()).sendResetCode(eq("guard"), anyString());
        verify(otpService, never()).sendResetCode(eq("gate.guard@example.com"), anyString());
        verify(otpService, never()).sendResetCode(eq("nobody@example.com"), anyString());
    }

    @Test
    void aFailedOrRefusedEmailIsInvisibleToTheCaller() throws Exception {
        String sentReply = body(requestCode(DIRECTOR));
        when(otpService.sendResetCode(anyString(), anyString())).thenReturn(new SendResult(SendOutcome.MAIL_FAILED, 0));
        assertThat(body(requestCode(DIRECTOR))).isEqualTo(sentReply);
        when(otpService.sendResetCode(anyString(), anyString())).thenReturn(new SendResult(SendOutcome.COOLDOWN, 40));
        assertThat(body(requestCode(DIRECTOR))).isEqualTo(sentReply);
        when(otpService.sendResetCode(anyString(), anyString())).thenThrow(new IllegalStateException("SMTP exploded"));
        assertThat(body(requestCode(DIRECTOR))).isEqualTo(sentReply);
    }

    @Test
    void theReplyDoesNotWaitForTheEmail() throws Exception {
        // A slow mail server: the reply for the Director must come back as fast as for an unknown address
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        when(otpService.sendResetCode(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            finished.countDown();
            return new SendResult(SendOutcome.SENT, 60);
        });
        long start = System.nanoTime();
        body(requestCode(DIRECTOR));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis).isLessThan(1_000);
        // It still goes out; wait for it so the shared mail thread is free for the next test
        assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        verify(otpService).sendResetCode(eq(DIRECTOR), anyString());
    }

    @Test
    void aBlankAddressIsAskedFor() throws Exception {
        mvc.perform(requestCode(" ")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Enter your email address."));
        verify(otpService, after(300).never()).sendResetCode(anyString(), anyString());
    }

    // --- Step 2: code + new password ---

    @Test
    void theRightCodeSavesTheNewPasswordAsABCryptHash() throws Exception {
        when(otpService.verifyReset(DIRECTOR, "905521")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(reset(DIRECTOR, "905521", "NewDirector#26", "NewDirector#26"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value("Your password has been reset. You can now sign in with the new password."));

        ArgumentCaptor<StaffUser> saved = ArgumentCaptor.forClass(StaffUser.class);
        verify(staffRepo).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo(DIRECTOR);
        assertThat(saved.getValue().getRole()).isEqualTo(StaffUser.DIRECTOR);
        assertThat(saved.getValue().getPassword()).startsWith("$2").isNotEqualTo("NewDirector#26");
        assertThat(encoder.matches("NewDirector#26", saved.getValue().getPassword())).isTrue();
        verify(otpService).consumeReset(DIRECTOR);
    }

    @Test
    void everyCodeFailureGetsOneMessageAndSavesNothing() throws Exception {
        for (VerifyOutcome outcome : new VerifyOutcome[] {VerifyOutcome.WRONG_CODE, VerifyOutcome.EXPIRED,
                VerifyOutcome.NO_CODE, VerifyOutcome.TOO_MANY_ATTEMPTS}) {
            when(otpService.verifyReset(DIRECTOR, "111111")).thenReturn(outcome);
            mvc.perform(reset(DIRECTOR, "111111", "NewDirector#26", "NewDirector#26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ForgotPasswordController.CODE_FAILED));
        }
        // Unknown addresses and Guard accounts get the very same answer, and no code is even checked for them
        for (String other : new String[] {"nobody@example.com", "guard", "gate.guard@example.com"}) {
            mvc.perform(reset(other, "111111", "NewDirector#26", "NewDirector#26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ForgotPasswordController.CODE_FAILED));
            verify(otpService, never()).verifyReset(eq(other), anyString());
        }
        verify(staffRepo, never()).save(any());
        verify(otpService, never()).consumeReset(anyString());
    }

    @Test
    void aWeakPasswordIsRefusedBeforeTheCodeIsChecked() throws Exception {
        mvc.perform(reset(DIRECTOR, "905521", "short", "short"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The new password must be at least 8 characters long."));
        mvc.perform(reset(DIRECTOR, "905521", "NewDirector#26", "Different#26"))
            .andExpect(jsonPath("$.message").value("The new password and its confirmation do not match."));
        mvc.perform(reset(DIRECTOR, "905521", "x".repeat(73), "x".repeat(73)))
            .andExpect(jsonPath("$.message").value("The new password is too long (72 characters at most)."));
        mvc.perform(reset(DIRECTOR, " ", "NewDirector#26", "NewDirector#26"))
            .andExpect(status().isBadRequest());
        verify(otpService, never()).verifyReset(anyString(), anyString()); // no try of the code was used up
        verify(staffRepo, never()).save(any());
    }

    // --- Public, but CSRF-protected ---

    @Test
    void bothStepsWorkSignedOutButNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/forgot-password/request").param("email", DIRECTOR)).andExpect(status().isForbidden());
        mvc.perform(post("/forgot-password/reset").param("email", DIRECTOR).param("code", "905521")
                .param("newPassword", "NewDirector#26").param("confirmPassword", "NewDirector#26"))
            .andExpect(status().isForbidden());
        verify(otpService, after(300).never()).sendResetCode(anyString(), anyString());
        verify(staffRepo, never()).save(any());
    }
}
