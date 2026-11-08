package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Staff deletes are POST-only. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class StaffDeletePostAndGroundedRosterTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    // --- Task 3: POST-only staff deletes ---

    @Test
    void staffDeletesNoLongerRunOnAGet() throws Exception {
        for (String path : new String[] {"/admin/staff/delete/1", "/admin/staff/ajax-delete/1", "/admin/employee/delete/1"}) {
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(get("/admin/staff/ajax-bulk-delete").param("ids", "1,2")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(staffRepo, employeeRepo);
    }

    @Test
    void thePostVersionsDeleteAsBefore() throws Exception {
        mvc.perform(post("/admin/staff/delete/1").with(csrf())).andExpect(redirectedUrl("/admin/staff"));
        verify(staffRepo).deleteById(1L);
        mvc.perform(post("/admin/staff/ajax-delete/2").with(csrf())).andExpect(content().string("OK"));
        verify(staffRepo).deleteById(2L);
        // the page's fetch() sends the ids as a form body
        mvc.perform(post("/admin/staff/ajax-bulk-delete").with(csrf()).contentType("application/x-www-form-urlencoded").content("ids=3%2C4"))
            .andExpect(content().string("OK"));
        verify(staffRepo).deleteAllById(List.of(3L, 4L));
        mvc.perform(post("/admin/employee/delete/5").with(csrf())).andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "Officer removed from the official roster."));
        verify(employeeRepo).deleteById(5L);
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void staffDeletesStayDirectorOnly() throws Exception {
        for (String path : new String[] {"/admin/staff/delete/1", "/admin/staff/ajax-delete/1", "/admin/staff/ajax-bulk-delete", "/admin/employee/delete/1"}) {
            mvc.perform(post(path).with(csrf()).param("ids", "1")).andExpect(status().isForbidden());
        }
        verify(staffRepo, never()).deleteById(anyLong());
        verify(employeeRepo, never()).deleteById(anyLong());
    }

    @Test
    void theStaffPageUsesPostForEveryDelete() throws Exception {
        MuseumEmployee emp = new MuseumEmployee();
        emp.setId(7L);
        emp.setName("Sgt. Reyes");
        emp.setRole("Gate Guard");
        when(employeeRepo.findAll()).thenReturn(List.of(emp));

        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
            .containsPattern("<form action=\"/admin/employee/delete/7\" method=\"post\" class=\"d-inline m-0\">" + PostOnlyActionsTest.CSRF_FIELD + "\\s*"
                    + "<button type=\"submit\" class=\"btn btn-sm btn-light text-danger p-1\" title=\"Remove\" aria-label=\"Remove employee\">")
            .doesNotContain("href=\"/admin/employee/delete/")
            .contains("fetch('/admin/staff/ajax-delete/' + id, { method: 'POST', headers: AeroCsrf.headers() })")
            .contains("fetch('/admin/staff/ajax-bulk-delete', {\n            method: 'POST',\n            headers: AeroCsrf.headers(")
            .doesNotContain("ajax-bulk-delete?ids=");
    }
}
