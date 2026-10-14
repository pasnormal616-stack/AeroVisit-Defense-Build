package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
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
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** State-changing admin actions accept POST only, and the pages submit them as forms instead of links. No database. */
@WebMvcTest({GuardOperationController.class, ManagementController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class PostOnlyActionsTest {

    // The CSRF token Thymeleaf adds right after every POST form's opening tag (CSRF is on, see SecurityConfig)
    static final String CSRF_FIELD = "<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\"\\s*/?>";

    private static final String[] ACTIONS = {
        "/admin/approve/5", "/admin/reject/5", "/admin/delete/5", "/admin/bans/5/lift", "/admin/slots/delete/5"
    };

    @Autowired
    private MockMvc mvc;

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
    void aPlainLinkOrTypedUrlCanNoLongerChangeData() throws Exception {
        for (String path : ACTIONS) {
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        }
        verifyNoInteractions(repo, faceBanRepo, bookingRepo);
    }

    @Test
    void thePostVersionsStillWork() throws Exception {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setStatus("PENDING");
        v.setEmail("juan@example.com");
        v.setVisitDate(LocalDate.now().plusDays(2));
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/reject/5").with(csrf())).andExpect(redirectedUrl("/admin"));
        assertThat(v.getStatus()).isEqualTo("REJECTED");

        mvc.perform(post("/admin/delete/5").with(csrf())).andExpect(redirectedUrl("/admin"));
        verify(repo).delete(v);

        when(faceBanRepo.existsById(7L)).thenReturn(true);
        mvc.perform(post("/admin/bans/7/lift").with(csrf())).andExpect(redirectedUrl("/admin/blacklist"));
        verify(faceBanRepo).deleteById(7L);

        mvc.perform(post("/admin/slots/delete/9").with(csrf())).andExpect(redirectedUrl("/admin/staff"));
        verify(bookingRepo).deleteById(9L);
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void directorOnlyActionsStayDirectorOnlyAsPost() throws Exception {
        for (String path : new String[] {"/admin/delete/5", "/admin/bans/5/lift", "/admin/slots/delete/5"}) {
            mvc.perform(post(path).with(csrf())).andExpect(status().isForbidden());
        }
        verify(repo, never()).delete(any());
        verify(faceBanRepo, never()).deleteById(anyLong());
        verify(bookingRepo, never()).deleteById(anyLong());
    }

    // --- The pages render forms, not links ---

    @Test
    void dashboardApproveAndDeleteAreForms() throws Exception {
        Visitor v = new Visitor();
        v.setId(3L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setPurpose("Leisure");
        v.setStatus("PENDING");
        v.setVisitDate(LocalDate.now().plusDays(2));
        when(repo.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(new ArrayList<>(List.of(v)));

        String html = mvc.perform(get("/admin").param("filter", "pending")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html)
            .containsPattern("<form action=\"/admin/approve/3\" method=\"post\" class=\"m-0\">" + CSRF_FIELD
                + "\\s*<input type=\"hidden\" name=\"returnTo\" value=\"/admin\\?filter=pending&amp;sort=asc\">"
                + "\\s*<button type=\"submit\" class=\"btn btn-sm btn-outline-success\" title=\"Approve\">Approve</button>")
            .containsPattern("<form action=\"/admin/delete/3\" method=\"post\" class=\"m-0\" onsubmit=\"return confirm\\('Delete this record\\?'\\);\">" + CSRF_FIELD + "\\s*<button type=\"submit\" class=\"dropdown-item text-danger\">")
            .doesNotContain("href=\"/admin/approve/")
            .doesNotContain("href=\"/admin/delete/");
    }

    @Test
    void liftingABanIsAForm() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(7L);
        ban.setReason("Fake ID");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));

        String html = mvc.perform(get("/admin/blacklist")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
            .containsPattern("<form action=\"/admin/bans/7/lift\" method=\"post\" class=\"av-ban-foot\"[^>]*>" + CSRF_FIELD + "\\s*<button type=\"submit\" class=\"av-ban-lift\">")
            .doesNotContain("href=\"/admin/bans/");
    }

    // Screens follow the ROLE, not the username: any Director account gets the Director controls, no Guard account does
    @Test
    void directorControlsFollowTheRoleNotTheUsername() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(7L);
        ban.setReason("Fake ID");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));

        String backupDirector = mvc.perform(get("/admin/blacklist")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("director_backup").roles("DIRECTOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(backupDirector).contains("action=\"/admin/bans/7/lift\"").contains("href=\"/admin/staff\"")
            .doesNotContain("Lifting a ban is Director only.");

        String southGuard = mvc.perform(get("/admin/blacklist")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("guard_south").roles("GUARD")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(southGuard).doesNotContain("action=\"/admin/bans/7/lift\"").doesNotContain("href=\"/admin/staff\"")
            .contains("Lifting a ban is Director only.").contains("href=\"/guard/dashboard\"");
    }

    @Test
    void slotModalPostsTheDelete() throws Exception {
        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
            .contains("<form action=\"/admin/staff\" method=\"post\" id=\"modalSlotDeleteForm\" class=\"w-100 m-0\">")
            .contains("deleteForm.action = '/admin/slots/delete/' + info.event.id;")
            .doesNotContain("deleteBtn.href");
    }
}
