package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Header notification bell (pending-count API) and the Settings page. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class SettingsAndNotificationsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    // --- Notification bell ---

    @Test
    @SuppressWarnings("unchecked")
    void pendingCountReturnsTheApprovalQueueSizeAsUncachedJson() throws Exception {
        when(repo.countByStatusIn(anyCollection())).thenReturn(4L);

        mvc.perform(get("/admin/api/pending-count"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.count").value(4))
            .andExpect(header().string("Cache-Control", containsString("no-store")));

        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(repo).countByStatusIn(statuses.capture());
        // Same statuses the Pending Approval tab lists
        assertThat(statuses.getValue()).containsExactlyInAnyOrder("PENDING", "VERIFIED", "PENDING_MANUAL_REVIEW");
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardsCannotReadThePendingCount() throws Exception {
        // Booking review is Director-only (least privilege), so the pending queue's size is too
        mvc.perform(get("/admin/api/pending-count")).andExpect(status().isForbidden());
        verify(repo, never()).countByStatusIn(anyCollection());
    }

    @Test
    void signedOutVisitorsCannotReadTheCount() throws Exception {
        mvc.perform(get("/admin/api/pending-count").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("**/login"));
        verify(repo, never()).countByStatusIn(any());
    }

    @Test
    void everyAdminPageCarriesTheBellToastAndPollingScript() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>());
        for (String url : new String[] {"/admin", "/admin/settings"}) {
            mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<button type=\"button\" class=\"av-iconbtn av-bell\" id=\"avBell\" data-bs-toggle=\"dropdown\"")))
                .andExpect(content().string(containsString("<span class=\"av-bell-badge\" id=\"avBellBadge\" hidden></span>")))
                // the pending queue link the bell used to be is now the first row of the dropdown
                .andExpect(content().string(containsString("<a class=\"av-notif-pending\" href=\"/admin?filter=pending&amp;sort=desc\">")))
                .andExpect(content().string(containsString("id=\"avNotifList\"")))
                .andExpect(content().string(containsString("id=\"avBellToast\"")))
                .andExpect(content().string(containsString("New booking request received!")))
                .andExpect(content().string(containsString("fetch('/api/notifications'")))
                .andExpect(content().string(containsString("fetch('/api/notifications/mark-read', { method: 'POST'")))
                .andExpect(content().string(containsString("setInterval(poll, 30000);")));
        }
    }

    // --- Settings page ---

    @Test
    void settingsPageShowsTheThreeCardsForTheDirector() throws Exception {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US));
        String html = mvc.perform(get("/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(view().name("settings"))
            .andExpect(model().attribute("loggedInUser", "director"))
            .andExpect(model().attribute("accountRole", "Director"))
            .andExpect(model().attribute("serverDate", LocalDate.now()))
            .andExpect(content().string(containsString("Account Profile")))
            .andExpect(content().string(containsString("id=\"selfPasswordForm\"")))
            .andExpect(content().string(containsString("System Information")))
            .andExpect(content().string(containsString("<strong class=\"av-mono\">1.0.0</strong>")))
            .andExpect(content().string(containsString("<strong>Production</strong>")))
            .andExpect(content().string(containsString(today)))
            .andExpect(content().string(containsString("<a href=\"/admin/settings\" class=\"av-nav-link is-active\" aria-current=\"page\">")))
            .andReturn().getResponse().getContentAsString();

        // Settings sits at the very bottom of the sidebar, just above Log out (a POST form with the CSRF token, since a
        // plain link to /login?logout never ended the session)
        int foot = html.indexOf("av-sidebar-foot");
        assertThat(foot).isPositive().isLessThan(html.indexOf("href=\"/admin/settings\""));
        assertThat(html.indexOf("href=\"/admin/settings\"")).isLessThan(html.indexOf("action=\"/logout\" method=\"post\""));
        assertThat(html).containsPattern("action=\"/logout\" method=\"post\" class=\"av-logout-form\">" + PostOnlyActionsTest.CSRF_FIELD)
            .doesNotContain("href=\"/login?logout\"");
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardSeesTheirOwnProfile() throws Exception {
        mvc.perform(get("/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("loggedInUser", "guard"))
            .andExpect(model().attribute("accountRole", "Guard"));
    }

    // Password changes moved to the Director's Staff Accounts panel (StaffManagementController, emailed security code)
    @Test
    void theDirectorGetsTheStaffAccountsPanelWithTheSecurityCodeStep() throws Exception {
        String html = mvc.perform(get("/admin/settings")).andReturn().getResponse().getContentAsString();
        assertThat(html)
            .contains("id=\"selfPasswordForm\"", "name=\"currentPassword\"")
            .contains("id=\"staffAccounts\"", "Guard Account", "id=\"guardForm\"", "name=\"newUsername\"")
            .contains("Confirm With Security Code", "id=\"staffCodeForm\"", "autocomplete=\"one-time-code\"")
            .contains("fetch('/admin/api/staff-accounts'", "'/admin/api/staff-accounts/request'", "'/admin/api/staff-accounts/confirm'")
            .contains("AeroCsrf.headers(")
            .doesNotContain("/admin/settings/password").doesNotContain("managed by the system administrator");
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void theGuardSeesNoPasswordFormsAndNoStaffAccountsPanel() throws Exception {
        String html = mvc.perform(get("/admin/settings")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Your password is managed by the Director.")
            .doesNotContain("selfPasswordForm").doesNotContain("staffAccounts").doesNotContain("guardForm")
            .doesNotContain("/admin/api/staff-accounts");
    }

    // --- PAF Museum logo branding (admin header + login) ---

    @Test
    void adminHeaderShowsTheLogoAndKeepsEveryHeaderControl() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>());
        String html = mvc.perform(get("/admin")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<link rel=\"icon\" type=\"image/png\" href=\"/images/paflogo.png\">");
        assertThat(html).containsSubsequence("<header class=\"av-header\">", "data-sidebar-toggle",
            "<a href=\"/admin\" class=\"av-brand\">",
            "<img class=\"paf-logo\" src=\"/images/paflogo.png\" alt=\"Philippine Air Force Museum Logo\"",
            "id=\"avClock\"", "id=\"avBell\" data-bs-toggle=\"dropdown\"", "data-theme-toggle", "</header>");
    }

    @Test
    void loginPageCentresTheTransparentLogoAboveTheForm() throws Exception {
        String html = mvc.perform(get("/login").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<link rel=\"icon\" type=\"image/png\" href=\"/images/paflogo.png\">");
        assertThat(html).containsSubsequence("<div class=\"av-auth-top\">",
            "<span class=\"paf-logo-plate av-auth-logo\"><img class=\"paf-logo\" src=\"/images/paflogo.png\" alt=\"Philippine Air Force Museum Logo\"",
            "<section class=\"av-auth-panel\">", "action=\"/login\"");
        assertThat(html).doesNotContain("paflogowhite.jpg");
        // The Forgot Password link sits under the password field, before the Log in button
        assertThat(html).containsSubsequence("id=\"password\"", "<a href=\"/forgot-password\" class=\"small\">Forgot Password?</a>",
            "type=\"submit\"");
    }
}
