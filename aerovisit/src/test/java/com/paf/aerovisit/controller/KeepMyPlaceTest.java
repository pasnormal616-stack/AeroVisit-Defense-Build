package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * ↩️ Keep my place: after Delete, Ban, manual check-in/out and Deny Entry the officer goes back to the view they acted
 * from (shell.js sends it as "returnTo"), not to Today's Manifest. Only our own dashboards are accepted. No database.
 */
@WebMvcTest({ManagementController.class, GuardOperationController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class KeepMyPlaceTest {

    private static final String ENTIRE_DATABASE = "/admin?filter=all";
    private static final String GUARD_INSIDE = "/guard/dashboard?tab=inside";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    @Test
    void eachActionGoesBackToTheViewItCameFrom() throws Exception {
        when(repo.findById(77L)).thenReturn(Optional.empty()); // unknown visitor: only the redirect is tested
        when(repo.findByReferenceId("PAF-NOPE00")).thenReturn(Optional.empty());
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/delete/77").with(csrf()).param("returnTo", ENTIRE_DATABASE)).andExpect(redirectedUrl(ENTIRE_DATABASE));
        mvc.perform(post("/admin/biometric-ban/77").with(csrf()).param("returnTo", ENTIRE_DATABASE)).andExpect(redirectedUrl(ENTIRE_DATABASE));
        mvc.perform(post("/admin/biometric-ban/77").with(csrf()).param("returnTo", GUARD_INSIDE)).andExpect(redirectedUrl(GUARD_INSIDE));
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-NOPE00").param("returnTo", GUARD_INSIDE))
           .andExpect(redirectedUrl(GUARD_INSIDE));
        mvc.perform(post("/admin/deny-entry").with(csrf()).param("visitorId", "77").param("reason", "Security concern")
                .param("returnTo", GUARD_INSIDE)).andExpect(redirectedUrl(GUARD_INSIDE));
    }

    @Test
    void withoutAReturnAddressOrWithAForeignOneTheyGoToTheDashboardAsBefore() throws Exception {
        when(repo.findById(77L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/delete/77").with(csrf())).andExpect(redirectedUrl("/admin"));
        mvc.perform(post("/admin/delete/77").with(csrf()).param("returnTo", "https://evil.example/admin")).andExpect(redirectedUrl("/admin"));
        mvc.perform(post("/admin/biometric-ban/77").with(csrf()).param("returnTo", "//evil.example")).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void theBrowserSideRemembersThePlaceAndTheRowsCanBeFound() throws Exception {
        Path resources = Path.of("src/main/resources");
        String shell = Files.readString(resources.resolve("static/js/shell.js"));
        assertThat(shell).contains("back.name = 'returnTo'", "sessionStorage.setItem(PLACE_KEY", "e.defaultPrevented",
                "av-row-flash", "av-alert-float");
        String admin = Files.readString(resources.resolve("templates/admin.html"));
        assertThat(admin).contains("data-visitor-id=${visitor.id}", "\"stateSave\": true", "'av-dt' + location.search");
        String guard = Files.readString(resources.resolve("templates/guard.html"));
        assertThat(guard).contains("data-visitor-id=${visitor.id}");
    }
}
