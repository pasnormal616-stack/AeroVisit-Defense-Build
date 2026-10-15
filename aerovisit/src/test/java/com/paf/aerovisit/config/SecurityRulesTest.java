package com.paf.aerovisit.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;
import com.paf.aerovisit.security.CustomUserDetailsService;

/**
 * Checks the URL rules in SecurityConfig without a database: a stub controller answers every
 * path with 200, so a 403 or login redirect can only come from the security rules.
 * Staff logins go through the real CustomUserDetailsService and BCrypt encoder over a mocked StaffUserRepository.
 */
@WebMvcTest(controllers = SecurityRulesTest.StubController.class)
@Import({SecurityConfig.class, CustomUserDetailsService.class, SecurityRulesTest.StubController.class})
class SecurityRulesTest {

    @RestController
    static class StubController {
        @RequestMapping({"/admin/**", "/uploads/**", "/check-status", "/leave-feedback", "/guard/**", "/api/booking/**",
                         "/forgot-password", "/forgot-password/**"})
        String ok() {
            return "ok";
        }
    }

    private static final String[] DIRECTOR_ONLY = {
        "/admin/employee/add", "/admin/employee/edit/1", "/admin/employee/delete/1", "/admin/delete/1", "/admin/bans/1/lift",
        "/admin/export-excel", "/admin/inject-mock-data", "/admin/report", "/admin/staff/x", "/admin/slots/x"
    };

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserDetailsService users;

    @Autowired
    private PasswordEncoder encoder;

    @MockitoBean
    private StaffUserRepository staffRepo;

    // The two database accounts, as StaffUserSeeder creates them (BCrypt hashes, roles without the ROLE_ prefix)
    @org.junit.jupiter.api.BeforeEach
    void staffAccounts() {
        StaffUser director = new StaffUser("PAFMuseumOfficial@gmail.com", encoder.encode("director-secret"), StaffUser.DIRECTOR);
        StaffUser guard = new StaffUser("guard", encoder.encode("guard-secret"), StaffUser.GUARD);
        org.mockito.Mockito.when(staffRepo.findByUsernameIgnoreCase(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(staffRepo.findByUsernameIgnoreCase("pafmuseumofficial@gmail.com")).thenReturn(java.util.Optional.of(director));
        org.mockito.Mockito.when(staffRepo.findByUsernameIgnoreCase("PAFMuseumOfficial@gmail.com")).thenReturn(java.util.Optional.of(director));
        org.mockito.Mockito.when(staffRepo.findByUsernameIgnoreCase("guard")).thenReturn(java.util.Optional.of(guard));
    }

    @Test
    void anonymousCannotOpenUploadedIdsOrQrCodes() throws Exception {
        mvc.perform(get("/uploads/QR_5.png"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCanOpenUploads() throws Exception {
        mvc.perform(get("/uploads/QR_5.png")).andExpect(status().isOk());
    }

    @Test
    void anonymousCanUsePublicBookingLookupAndFeedback() throws Exception {
        mvc.perform(get("/check-status")).andExpect(status().isOk());
        mvc.perform(get("/leave-feedback")).andExpect(status().isOk());
    }

    @Test
    void anonymousCanUseForgotPasswordButOnlyWithTheCsrfToken() throws Exception {
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
        mvc.perform(post("/forgot-password/request").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/forgot-password/reset").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/forgot-password/request")).andExpect(status().isForbidden());
        mvc.perform(post("/forgot-password/reset")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsSentToLoginForAdminPages() throws Exception {
        mvc.perform(get("/admin/scanner"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/admin/employee/add", "/admin/employee/edit/1", "/admin/employee/delete/1", "/admin/delete/1", "/admin/bans/1/lift",
        "/admin/export-excel", "/admin/inject-mock-data", "/admin/report", "/admin/staff/x", "/admin/slots/x"
    })
    @WithMockUser(roles = "GUARD")
    void guardIsForbiddenFromDirectorOnlyRoutes(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void directorCanUseDirectorOnlyRoutes() throws Exception {
        for (String path : DIRECTOR_ONLY) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void forceApproveIsDirectorOnly() throws Exception {
        mvc.perform(post("/admin/approve-override/5").with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("guard").roles("GUARD")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/approve-override/5").with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("director").roles("DIRECTOR")))
            .andExpect(status().isOk());
    }

    // --- Least privilege: guards work the gate; booking review (approve, reject, KYC) is the Director's ---

    @ParameterizedTest
    @ValueSource(strings = {"/admin/approve/5", "/admin/reject/5", "/admin/reject-visitor", "/admin/api/pending-count", "/api/booking/verify-id"})
    @WithMockUser(roles = "GUARD")
    void guardIsForbiddenFromBookingReview(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/approve/5", "/admin/reject/5", "/admin/reject-visitor", "/admin/api/pending-count", "/api/booking/verify-id"})
    @WithMockUser(roles = "DIRECTOR")
    void directorKeepsBookingReview(String path) throws Exception {
        mvc.perform(post(path).with(csrf())).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCanUseTheGateActions() throws Exception {
        mvc.perform(get("/guard/dashboard")).andExpect(status().isOk());
        for (String path : new String[] {"/admin/deny-entry", "/admin/manual-checkin", "/admin/biometric-ban/5", "/admin/blacklist"}) {
            mvc.perform(post(path).with(csrf())).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void directorCanOpenTheGuardDashboard() throws Exception {
        mvc.perform(get("/guard/dashboard")).andExpect(status().isOk());
    }

    @Test
    void anonymousIsSentToLoginForTheGuardDashboard() throws Exception {
        mvc.perform(get("/guard/dashboard"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(roles = "VISITOR")
    void otherRolesCannotOpenTheGuardDashboard() throws Exception {
        mvc.perform(get("/guard/dashboard")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCanUseSharedOperationalPages() throws Exception {
        mvc.perform(get("/admin/scanner")).andExpect(status().isOk());
        mvc.perform(post("/admin/api/scan").with(csrf())).andExpect(status().isOk());
    }

    // --- Staff logins come from the database (staff_user) through CustomUserDetailsService + BCrypt ---

    @Test
    void theDirectorLogsInWithTheMuseumEmailAndGetsTheDirectorRole() throws Exception {
        mvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login").user("PAFMuseumOfficial@gmail.com").password("director-secret"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"))
            .andExpect(SecurityMockMvcResultMatchers.authenticated()
                .withUsername("PAFMuseumOfficial@gmail.com").withRoles("DIRECTOR"));
    }

    @Test
    void theGuardLogsInAndGetsOnlyTheGuardRole() throws Exception {
        mvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login").user("guard").password("guard-secret"))
            .andExpect(SecurityMockMvcResultMatchers.authenticated().withUsername("guard").withRoles("GUARD"));
    }

    @Test
    void wrongPasswordsUnknownUsersAndTheOldUsernamesAreRefused() throws Exception {
        mvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login").user("guard").password("director-secret"))
            .andExpect(redirectedUrlPattern("/login?error*"))
            .andExpect(SecurityMockMvcResultMatchers.unauthenticated());
        // The old in-memory accounts and demo passwords no longer exist
        for (String[] old : new String[][] {{"director", "admin123"}, {"guard", "guard123"}, {"admin", "admin123"}}) {
            mvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login").user(old[0]).password(old[1]))
                .andExpect(SecurityMockMvcResultMatchers.unauthenticated());
        }
    }

    @Test
    void theLoginLookupIgnoresCaseAndSurroundingSpaces() {
        assertThat(users.loadUserByUsername("  pafmuseumofficial@gmail.com ").getAuthorities())
            .extracting("authority").containsExactly("ROLE_DIRECTOR");
        assertThat(users.loadUserByUsername("guard").getAuthorities()).extracting("authority").containsExactly("ROLE_GUARD");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.loadUserByUsername("director"))
            .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
    }

    @Test
    void storedPasswordsAreBCryptHashesNeverPlainText() {
        String hash = users.loadUserByUsername("guard").getPassword();
        assertThat(hash).startsWith("$2").isNotEqualTo("guard-secret");
        assertThat(encoder).isInstanceOf(org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.class);
        assertThat(encoder.matches("guard-secret", hash)).isTrue();
    }

    // --- CSRF is on: a state-changing request without the session's token is refused, even for the Director ---

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void aPostWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/admin/approve/5")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/approve/5").with(SecurityMockMvcRequestPostProcessors.csrf().useInvalidToken()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/approve/5").with(SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk());
        // fetch() calls send the token in the X-CSRF-TOKEN header instead of a form field
        mvc.perform(post("/admin/approve/5").with(SecurityMockMvcRequestPostProcessors.csrf().asHeader())).andExpect(status().isOk());
    }

    @Test
    void theAnonymousOtpFormNeedsTheCsrfTokenToo() throws Exception {
        mvc.perform(post("/otp/send").param("email", "a@b.co")).andExpect(status().isForbidden());
    }

    // --- Logout: POST with the token ends the session; a plain link (GET) or a forged POST can't ---

    @Test
    void logoutIsAPostThatEndsTheSession() throws Exception {
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/logout").session(session).with(SecurityMockMvcRequestPostProcessors.user("guard").roles("GUARD"))
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?logout"))
            .andExpect(SecurityMockMvcResultMatchers.unauthenticated());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void logoutWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/logout").with(SecurityMockMvcRequestPostProcessors.user("guard").roles("GUARD")))
            .andExpect(status().isForbidden());
    }
}
