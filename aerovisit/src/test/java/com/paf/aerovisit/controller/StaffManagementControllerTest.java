package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
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
 * Director-only staff account management: every change waits for the security code mailed to the museum inbox, and
 * only then is saved (as a BCrypt hash). No database: the repository and OtpService are mocks; BCrypt is the real one.
 */
@WebMvcTest(StaffManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "PAFMuseumOfficial@gmail.com", roles = "DIRECTOR")
class StaffManagementControllerTest {

    private static final String INBOX = "PAFMuseumOfficial@gmail.com";

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private StaffManagementController controller;

    @MockitoBean private StaffUserRepository staffRepo;
    @MockitoBean private OtpService otpService;

    private StaffUser director;
    private StaffUser guard;

    @BeforeEach
    void accounts() {
        // The controller is shared by the whole test class: start every test with no change waiting
        ((AtomicReference<?>) ReflectionTestUtils.getField(controller, "pending")).set(null);

        director = new StaffUser(INBOX, encoder.encode("director-old-1"), StaffUser.DIRECTOR);
        director.setId(1L);
        guard = new StaffUser("guard", encoder.encode("guard-old-1"), StaffUser.GUARD);
        guard.setId(2L);
        when(staffRepo.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(staffRepo.findByUsernameIgnoreCase(INBOX)).thenReturn(Optional.of(director));
        when(staffRepo.findByUsernameIgnoreCase("pafmuseumofficial@gmail.com")).thenReturn(Optional.of(director));
        when(staffRepo.findByUsernameIgnoreCase("guard")).thenReturn(Optional.of(guard));
        when(staffRepo.findFirstByRoleOrderByIdAsc(StaffUser.GUARD)).thenReturn(Optional.of(guard));
        when(otpService.sendStaffCode(anyString(), anyString(), anyString())).thenReturn(new SendResult(SendOutcome.SENT, 60));
    }

    private static MockHttpServletRequestBuilder request(String... params) {
        MockHttpServletRequestBuilder post = post("/admin/api/staff-accounts/request").with(csrf());
        for (int i = 0; i < params.length; i += 2) post.param(params[i], params[i + 1]);
        return post;
    }

    private static MockHttpServletRequestBuilder confirm(String code) {
        return post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", code);
    }

    private StaffUser saved() {
        ArgumentCaptor<StaffUser> saved = ArgumentCaptor.forClass(StaffUser.class);
        verify(staffRepo).save(saved.capture());
        return saved.getValue();
    }

    // --- Access ---

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void theGuardCannotUseAnyStaffAccountAction() throws Exception {
        mvc.perform(get("/admin/api/staff-accounts")).andExpect(status().isForbidden());
        mvc.perform(request("target", "guard", "newPassword", "NewGuard#123", "confirmPassword", "NewGuard#123")).andExpect(status().isForbidden());
        mvc.perform(confirm("123456")).andExpect(status().isForbidden());
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
        verify(staffRepo, never()).save(any());
    }

    @Test
    void stateChangingCallsNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/admin/api/staff-accounts/request").param("target", "self")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/staff-accounts/confirm").param("code", "123456")).andExpect(status().isForbidden());
    }

    @Test
    void thePanelShowsTheGuardUsernameAndTheSecurityInbox() throws Exception {
        mvc.perform(get("/admin/api/staff-accounts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.guardUsername").value("guard"))
            .andExpect(jsonPath("$.securityInbox").value(INBOX));
    }

    // --- The Director's own password ---

    @Test
    void ownPasswordChangeIsSavedOnlyAfterTheEmailedCode() throws Exception {
        mvc.perform(request("target", "self", "currentPassword", "director-old-1", "newPassword", "Director#2026", "confirmPassword", "Director#2026"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nothing has been changed yet")));
        verify(otpService).sendStaffCode(eq(INBOX), anyString(), eq("change the Director's password"));
        verify(staffRepo, never()).save(any()); // nothing saved before the code

        when(otpService.verifyStaff(INBOX, "111111")).thenReturn(VerifyOutcome.WRONG_CODE);
        mvc.perform(confirm("111111")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false));
        verify(staffRepo, never()).save(any());

        when(otpService.verifyStaff(INBOX, "482913")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("482913")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        StaffUser changed = saved();
        assertThat(changed.getUsername()).isEqualTo(INBOX);
        assertThat(changed.getPassword()).startsWith("$2").isNotEqualTo("Director#2026");
        assertThat(encoder.matches("Director#2026", changed.getPassword())).isTrue();
        verify(otpService).consumeStaff(INBOX);

        // The same code cannot apply it a second time
        mvc.perform(confirm("482913")).andExpect(status().isConflict());
    }

    @Test
    void ownPasswordChangeNeedsTheCurrentPassword() throws Exception {
        mvc.perform(request("target", "self", "currentPassword", "wrong-password", "newPassword", "Director#2026", "confirmPassword", "Director#2026"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Your current password is incorrect."));
        mvc.perform(request("target", "self", "newPassword", "Director#2026", "confirmPassword", "Director#2026"))
            .andExpect(status().isBadRequest());
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
    }

    @Test
    void weakOrMismatchedNewPasswordsAreRefusedBeforeAnyCodeIsSent() throws Exception {
        String[][] cases = {
            {"Director#2026", "Different#2026", "The new password and its confirmation do not match."},
            {"short", "short", "The new password must be at least 8 characters long."},
            {"x".repeat(73), "x".repeat(73), "The new password is too long (72 characters at most)."},
            {"", "", "Enter the new password twice."}};
        for (String[] c : cases) {
            mvc.perform(request("target", "self", "currentPassword", "director-old-1", "newPassword", c[0], "confirmPassword", c[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(c[2]));
        }
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
    }

    // --- The Guard account ---

    @Test
    void aWrongCodeNeverChangesTheGuardPasswordAndTheRightCodeThenDoes() throws Exception {
        mvc.perform(request("target", "guard", "newPassword", "GuardNew#26", "confirmPassword", "GuardNew#26"))
            .andExpect(status().isOk());
        verify(otpService).sendStaffCode(eq(INBOX), anyString(), eq("change the Guard account's password"));

        when(otpService.verifyStaff(INBOX, "000000")).thenReturn(VerifyOutcome.WRONG_CODE);
        mvc.perform(confirm("000000"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("That code is not correct. Please check the email and try again."));
        verify(staffRepo, never()).save(any());
        verify(otpService, never()).consumeStaff(anyString());

        // The change is still waiting: the right code then applies it, hashed
        when(otpService.verifyStaff(INBOX, "730154")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("730154")).andExpect(status().isOk());
        StaffUser changed = saved();
        assertThat(changed.getRole()).isEqualTo(StaffUser.GUARD);
        assertThat(changed.getPassword()).startsWith("$2");
        assertThat(encoder.matches("GuardNew#26", changed.getPassword())).isTrue();
    }

    @Test
    void guardUsernameAndPasswordChangeTogetherAfterTheCode() throws Exception {
        mvc.perform(request("target", "guard", "newUsername", "gate.guard", "newPassword", "GuardNew#26", "confirmPassword", "GuardNew#26"))
            .andExpect(status().isOk());
        verify(otpService).sendStaffCode(eq(INBOX), anyString(),
                eq("change the Guard account's username to \"gate.guard\" and its password"));
        verify(staffRepo, never()).save(any());

        when(otpService.verifyStaff(INBOX, "730154")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("730154")).andExpect(status().isOk()).andExpect(jsonPath("$.guardUsername").value("gate.guard"));
        StaffUser changed = saved();
        assertThat(changed.getId()).isEqualTo(2L);
        assertThat(changed.getRole()).isEqualTo(StaffUser.GUARD);
        assertThat(changed.getUsername()).isEqualTo("gate.guard");
        assertThat(encoder.matches("GuardNew#26", changed.getPassword())).isTrue();
    }

    @Test
    void guardPasswordAloneKeepsTheUsername() throws Exception {
        mvc.perform(request("target", "guard", "newUsername", "", "newPassword", "GuardNew#26", "confirmPassword", "GuardNew#26"))
            .andExpect(status().isOk());
        verify(otpService).sendStaffCode(eq(INBOX), anyString(), eq("change the Guard account's password"));
        when(otpService.verifyStaff(INBOX, "730154")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("730154")).andExpect(status().isOk());
        StaffUser changed = saved();
        assertThat(changed.getUsername()).isEqualTo("guard");
        assertThat(encoder.matches("GuardNew#26", changed.getPassword())).isTrue();
    }

    @Test
    void guardUsernameAloneKeepsThePassword() throws Exception {
        String oldHash = guard.getPassword();
        mvc.perform(request("target", "guard", "newUsername", "north.gate")).andExpect(status().isOk());
        verify(otpService).sendStaffCode(eq(INBOX), anyString(), eq("change the Guard account's username to \"north.gate\""));
        when(otpService.verifyStaff(INBOX, "730154")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("730154")).andExpect(status().isOk());
        StaffUser changed = saved();
        assertThat(changed.getUsername()).isEqualTo("north.gate");
        assertThat(changed.getPassword()).isEqualTo(oldHash);
    }

    @Test
    void invalidOrTakenGuardUsernamesAndEmptyRequestsAreRefused() throws Exception {
        mvc.perform(request("target", "guard", "newUsername", "PAFMuseumOfficial@gmail.com"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("That username is already used by another staff account."));
        mvc.perform(request("target", "guard", "newUsername", "bad name!"))
            .andExpect(status().isBadRequest());
        mvc.perform(request("target", "guard", "newUsername", "ab"))
            .andExpect(status().isBadRequest());
        mvc.perform(request("target", "guard", "newUsername", "guard"))   // unchanged and no password: nothing to do
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Enter a new username, a new password, or both."));
        mvc.perform(request("target", "nobody")).andExpect(status().isBadRequest());
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
    }

    // --- The code ---

    @Test
    void aConfirmationWithoutAWaitingChangeSavesNothing() throws Exception {
        mvc.perform(confirm("123456")).andExpect(status().isConflict());
        verify(otpService, never()).verifyStaff(anyString(), anyString());
        verify(staffRepo, never()).save(any());
    }

    @Test
    void anExpiredCodeDropsTheChange() throws Exception {
        mvc.perform(request("target", "guard", "newPassword", "GuardNew#26", "confirmPassword", "GuardNew#26")).andExpect(status().isOk());
        when(otpService.verifyStaff(INBOX, "123456")).thenReturn(VerifyOutcome.EXPIRED);
        mvc.perform(confirm("123456")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Nothing was changed")));
        mvc.perform(confirm("123456")).andExpect(status().isConflict());
        verify(staffRepo, never()).save(any());
    }

    @Test
    void aCooldownKeepsTheChangeTheMailedCodeBelongsTo() throws Exception {
        mvc.perform(request("target", "guard", "newUsername", "first.guard")).andExpect(status().isOk());
        when(otpService.sendStaffCode(anyString(), anyString(), anyString())).thenReturn(new SendResult(SendOutcome.COOLDOWN, 42));
        mvc.perform(request("target", "guard", "newUsername", "second.guard"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.retryAfter").value(42));

        when(otpService.verifyStaff(INBOX, "730154")).thenReturn(VerifyOutcome.VERIFIED);
        mvc.perform(confirm("730154")).andExpect(status().isOk());
        assertThat(saved().getUsername()).isEqualTo("first.guard"); // the change the emailed code named
    }

    @Test
    void whenTheCodeCannotBeEmailedNothingWaits() throws Exception {
        when(otpService.sendStaffCode(anyString(), anyString(), anyString())).thenReturn(new SendResult(SendOutcome.MAIL_FAILED, 0));
        mvc.perform(request("target", "guard", "newPassword", "GuardNew#26", "confirmPassword", "GuardNew#26"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(confirm("123456")).andExpect(status().isConflict());
        verify(staffRepo, never()).save(any());
    }
}
