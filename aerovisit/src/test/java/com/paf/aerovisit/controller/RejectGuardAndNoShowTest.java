package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Reject guard (no rejecting a visit that is underway, finished or missed) and how a NO_SHOW booking behaves. No database. */
@WebMvcTest({ManagementController.class, GuardOperationController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class RejectGuardAndNoShowTest {

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

    private static Visitor v(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        v.setReferenceId("PAF-REF" + id);
        if ("CHECKED-IN".equals(status) || "CHECKED-OUT".equals(status)) {
            v.setCheckInTime(LocalDateTime.now().minusHours(1));
            v.setCheckedInBy("Sgt. Ramos");
        }
        return v;
    }

    // --- Reject guard ---

    @Test
    void aVisitThatIsUnderwayFinishedOrMissedCannotBeRejected() throws Exception {
        for (String status : new String[] {"CHECKED-IN", "CHECKED-OUT", "NO_SHOW"}) {
            Visitor visitor = v(5, status);
            when(repo.findById(5L)).thenReturn(Optional.of(visitor));

            mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "5").param("presetReason", "Security clearance failed"))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", Visitor.CANNOT_REJECT_MESSAGE));
            mvc.perform(post("/admin/reject/5").with(csrf()))
                .andExpect(flash().attribute("error", Visitor.CANNOT_REJECT_MESSAGE));

            assertThat(visitor.getStatus()).as(status).isEqualTo(status);
            assertThat(visitor.getRejectionReason()).as(status).isNull();
        }
        verify(repo, never()).save(any());
        verify(emailService, never()).sendRejectionEmail(anyString(), anyString(), anyString(), any());
    }

    @Test
    void pendingAndApprovedBookingsCanStillBeRejected() throws Exception {
        for (String status : new String[] {"PENDING", "PENDING_MANUAL_REVIEW", "APPROVED"}) {
            Visitor visitor = v(6, status);
            when(repo.findById(6L)).thenReturn(Optional.of(visitor));

            mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "6").param("presetReason", "Expired ID presented"))
                .andExpect(flash().attributeExists("success"));
            assertThat(visitor.getStatus()).as(status).isEqualTo("REJECTED");
        }
    }

    @Test
    void theDashboardOnlyOffersRejectOnPendingRows() throws Exception {
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(v(1, "PENDING"), v(2, "CHECKED-IN"), v(3, "CHECKED-OUT"), v(4, "NO_SHOW"))));

        String html = mvc.perform(get("/admin").param("filter", "all")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-bs-target=\"#rejectModal1\"");
        assertThat(html).doesNotContain("data-bs-target=\"#rejectModal2\"")
                        .doesNotContain("data-bs-target=\"#rejectModal3\"")
                        .doesNotContain("data-bs-target=\"#rejectModal4\"");
        assertThat(html).contains("<span class=\"badge-soft badge-checked-out\">No-Show</span>");
    }

    // --- A NO_SHOW booking is a dead pass ---

    @Test
    void aNoShowCannotBeCheckedInManuallyOrByQr() throws Exception {
        Visitor missed = v(8, "NO_SHOW");
        missed.setSecureToken("3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77");
        when(repo.findByReferenceId("PAF-REF8")).thenReturn(Optional.of(missed));
        when(repo.findBySecureToken(missed.getSecureToken())).thenReturn(Optional.of(missed));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF8"))
            .andExpect(flash().attribute("error", "ACCESS DENIED: This booking was marked as a no-show. The visitor must book a new visit."));
        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", missed.getSecureToken()))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Booking is not approved (status: NO_SHOW)."));

        assertThat(missed.getStatus()).isEqualTo("NO_SHOW");
        verify(repo, never()).save(any());
    }

    @Test
    void aNoShowCannotBeApprovedAgain() throws Exception {
        Visitor missed = v(9, "NO_SHOW");
        when(repo.findById(9L)).thenReturn(Optional.of(missed));

        mvc.perform(post("/admin/approve/9").with(csrf()))
            .andExpect(flash().attribute("error", "Invalid state transition: a booking with status NO_SHOW cannot be approved. Only pending requests can be approved."));
        assertThat(missed.getStatus()).isEqualTo("NO_SHOW");
    }

    @Test
    void aNoShowStillCountsAsApprovedDemandOnTheTrafficChart() throws Exception {
        Visitor missed = v(10, "NO_SHOW");
        missed.setVisitDate(LocalDate.now().minusDays(2));
        missed.setGroupSize(4);
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(missed)));

        var model = mvc.perform(get("/admin/analytics")).andExpect(status().isOk()).andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked") List<String> dates = (List<String>) model.get("chartDates");
        @SuppressWarnings("unchecked") List<Integer> booked = (List<Integer>) model.get("chartHistorical");

        // the booked-visitors series keeps the no-show's party of 4 on its visit date
        assertThat(booked.get(dates.indexOf(missed.getVisitDate().toString()))).isEqualTo(4);
    }
}
